/**
 * CallGuard WebRTC Signaling Server (온프레미스용)
 *
 * 실행: node signaling-server.js
 * 포트: 8080 (환경변수 PORT로 변경 가능)
 *
 * 방(room) 구조:
 *   - 최대 2명 입장
 *   - 첫 번째 입장자 = caller (offer 생성)
 *   - 두 번째 입장자 = callee (answer 생성)
 */

const WebSocket = require('ws');
const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

// roomId -> { caller: ws, callee: ws }
const rooms = new Map();

function send(ws, obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

wss.on('connection', (ws) => {
    let currentRoom = null;
    let myRole = null;

    // 30초마다 ping으로 연결 유지
    const pingInterval = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.ping();
    }, 30000);

    ws.on('message', (rawData) => {
        let msg;
        try {
            msg = JSON.parse(rawData);
        } catch {
            return;
        }

        switch (msg.type) {

            case 'join': {
                const roomId = msg.room;
                if (!roomId) return;

                if (!rooms.has(roomId)) {
                    rooms.set(roomId, { caller: null, callee: null });
                }
                const room = rooms.get(roomId);

                if (!room.caller) {
                    room.caller = ws;
                    myRole = 'caller';
                    currentRoom = roomId;
                    send(ws, { type: 'joined', role: 'caller' });
                    console.log(`[${roomId}] caller 입장`);
                } else if (!room.callee) {
                    room.callee = ws;
                    myRole = 'callee';
                    currentRoom = roomId;
                    send(ws, { type: 'joined', role: 'callee' });
                    console.log(`[${roomId}] callee 입장 → 통화 준비 완료`);
                    send(room.caller, { type: 'start_call' });
                } else {
                    send(ws, { type: 'error', message: '방이 꽉 찼습니다.' });
                    ws.close();
                }
                break;
            }

            case 'offer':
            case 'answer':
            case 'ice_candidate':
            case 'call_end':
            case 'typed_speech': {
                if (!currentRoom) return;
                const room = rooms.get(currentRoom);
                if (!room) return;
                const peer = myRole === 'caller' ? room.callee : room.caller;
                if (peer && peer.readyState === WebSocket.OPEN) {
                    send(peer, msg);
                }
                break;
            }

            default:
                break;
        }
    });

    ws.on('close', () => {
        clearInterval(pingInterval);
        if (!currentRoom) return;
        const room = rooms.get(currentRoom);
        if (!room) return;

        const peer = myRole === 'caller' ? room.callee : room.caller;
        if (peer && peer.readyState === WebSocket.OPEN) {
            send(peer, { type: 'call_end' });
        }

        if (myRole === 'caller') room.caller = null;
        else room.callee = null;

        if (!room.caller && !room.callee) {
            rooms.delete(currentRoom);
            console.log(`[${currentRoom}] 방 삭제`);
        }
        console.log(`[${currentRoom}] ${myRole} 퇴장`);
    });

    ws.on('error', (err) => console.error('WS error:', err.message));
});

console.log(`CallGuard 시그널링 서버 실행 중 → ws://localhost:${PORT}`);

// ── 모니터링 서버 (포트 8081) ─────────────────────────────────────

const fs   = require('fs');
const path = require('path');
const MONITOR_PORT = process.env.MONITOR_PORT || 8081;
const monitorWss   = new WebSocket.Server({ port: MONITOR_PORT });
const LOG_DIR      = path.join(__dirname, 'logs');
if (!fs.existsSync(LOG_DIR)) fs.mkdirSync(LOG_DIR);

// roomId → Set<WebSocket> (관리자 브라우저)
const adminsByRoom = new Map();

// roomId → 세션 로그 객체
const sessionLogs  = new Map();

function getOrCreateSession(room) {
    if (!sessionLogs.has(room)) {
        sessionLogs.set(room, {
            room,
            callStarted:  null,
            callEnded:    null,
            callEndReason: null,
            detections:   [],   // { time, type, keywords, alertTime, detectionLatencyMs }
            surveyAnswers: [],   // { time, question, answer }
            userActions:  [],   // { time, action }
            personalInfoEvents: [], // { time, type: 'utterance'|'disclosure_attempt', triggerPhrase, ... }
            alertCount:   0,
            interventionSucceeded: null,   // null = no intervention yet
            lastInterventionTime: null
        });
    }
    return sessionLogs.get(room);
}

function saveSessionLog(room) {
    const log = sessionLogs.get(room);
    if (!log) return;
    const filename = `${room}_${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
    fs.writeFile(path.join(LOG_DIR, filename), JSON.stringify(log, null, 2), () => {
        console.log(`[monitor][${room}] 로그 저장: ${filename}`);
    });
}

function broadcastToAdmins(room, msg) {
    const admins = adminsByRoom.get(room);
    if (!admins) return;
    const json = JSON.stringify(msg);
    admins.forEach(a => { if (a.readyState === WebSocket.OPEN) a.send(json); });
}

monitorWss.on('connection', (ws) => {
    let joinedRoom = null;

    ws.on('message', (rawData) => {
        let msg;
        try { msg = JSON.parse(rawData); } catch { return; }

        const room = msg.room;
        const data = msg.data || {};
        const now  = new Date().toISOString();

        if (msg.type === 'admin_join' && room) {
            joinedRoom = room;
            if (!adminsByRoom.has(room)) adminsByRoom.set(room, new Set());
            adminsByRoom.get(room).add(ws);
            // 기존 세션 로그 즉시 전송 (대시보드 새로고침 복구)
            const existing = sessionLogs.get(room);
            if (existing) ws.send(JSON.stringify({ type: 'session_log', room, data: existing }));
            console.log(`[monitor][${room}] 관리자 연결`);
            return;
        }

        if (!room) return;

        // ── 세션 로그 누적 ───────────────────────────────────────
        const session = getOrCreateSession(room);

        switch (msg.type) {
            case 'call_started':
                session.callStarted = now;
                break;

            case 'intervention':
                const detectionLatencyMs = session.callStarted
                    ? (new Date(now) - new Date(session.callStarted))
                    : null;
                session.detections.push({
                    time:             now,
                    type:             data.level || 'UNKNOWN',
                    keywords:         Array.isArray(data.keywords) ? data.keywords : JSON.parse(data.keywords || '[]'),
                    alertTime:        now,
                    detectionLatencyMs
                });
                session.alertCount++;
                session.lastInterventionTime = now;
                session.interventionSucceeded = false; // reset; will be set on call_ended
                break;

            case 'survey_answer':
                session.surveyAnswers.push({
                    time:     now,
                    question: data.question || '',
                    answer:   data.answer === 'true' || data.answer === true
                });
                break;

            case 'user_action':
                session.userActions.push({ time: now, action: data.action || '' });
                break;

            case 'personal_info_utterance':
                // 개인정보 발화 감지. micWasActiveAtDetection=true면 감지 순간 마이크가 활성이었으므로
                // 일부 프레임이 상대에게 전달됐을 가능성 = '제공 시도'로 해석 가능.
                session.personalInfoEvents.push({
                    time: now,
                    type: 'utterance',
                    triggerPhrase: data.triggerPhrase || '',
                    partialText: data.partialText || '',
                    micWasActiveAtDetection: data.micWasActiveAtDetection === true
                });
                break;

            case 'call_ended':
                session.callEnded     = now;
                session.callEndReason = data.reason || 'unknown';
                if (session.lastInterventionTime !== null) {
                    const elapsed = new Date(now) - new Date(session.lastInterventionTime);
                    // Success = call ended ≤ 120s after intervention
                    session.interventionSucceeded = elapsed <= 120000;
                }
                saveSessionLog(room);
                // 로그 저장 후 세션 초기화 (다음 통화를 위해)
                sessionLogs.delete(room);
                break;
        }

        // 관리자에게 실시간 중계 + 업데이트된 세션 로그 함께 전송
        broadcastToAdmins(room, msg);
        if (['intervention','survey_answer','user_action','call_started','call_ended',
             'personal_info_utterance'].includes(msg.type)) {
            broadcastToAdmins(room, { type: 'session_log', room, data: sessionLogs.get(room) || session });
        }
    });

    ws.on('close', () => {
        if (joinedRoom) {
            adminsByRoom.get(joinedRoom)?.delete(ws);
            console.log(`[monitor][${joinedRoom}] 관리자 연결 해제`);
        }
    });

    ws.on('error', (err) => console.error('[monitor] WS error:', err.message));
});

console.log(`CallGuard 모니터링 서버 실행 중 → ws://localhost:${MONITOR_PORT}`);
