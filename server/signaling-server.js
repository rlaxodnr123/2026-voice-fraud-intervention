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
const http = require('http');
const os   = require('os');
const PORT = process.env.PORT || 8080;

// ── LAN IP 자동 감지 (아이폰 핫스팟 172.20.10.x / Windows 핫스팟 192.168.137.x 등) ──
function getLanIPv4s() {
    const result = [];
    const ifaces = os.networkInterfaces();
    for (const [name, addrs] of Object.entries(ifaces)) {
        for (const a of addrs || []) {
            if (a.family === 'IPv4' && !a.internal) {
                let label = '';
                if (a.address.startsWith('172.20.10.'))   label = '아이폰 핫스팟';
                else if (a.address.startsWith('192.168.137.')) label = 'Windows 모바일 핫스팟';
                result.push({ name, address: a.address, label });
            }
        }
    }
    return result;
}

// 폰 브라우저에서 http://<PC IP>:8080 접속 시 연결 확인 페이지 제공 (방화벽/핫스팟 점검용)
function healthHandler(serverName) {
    return (req, res) => {
        const hostIp = (req.headers.host || '').split(':')[0];
        const rows = getLanIPv4s().map(ip =>
            `<li><code>ws://${ip.address}</code> (${ip.name}${ip.label ? ' — ' + ip.label : ''})</li>`).join('');
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(`<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CallGuard 서버 연결 확인</title>
<style>body{font-family:sans-serif;max-width:480px;margin:24px auto;padding:0 16px}
.ok{color:#0a7d32;font-size:1.3em}code{background:#eee;padding:2px 6px;border-radius:4px}</style></head>
<body><p class="ok">✅ ${serverName} 정상 동작 중</p>
<p>이 페이지가 보이면 폰과 PC가 같은 네트워크에 있고 방화벽도 열려 있습니다.</p>
<p><b>앱 서버 주소 입력란에:</b> <code>ws://${hostIp}</code></p>
<p>PC의 사용 가능한 주소 목록:</p><ul>${rows}</ul></body></html>`);
    };
}

const httpServer = http.createServer(healthHandler('CallGuard 시그널링 서버 (포트 ' + PORT + ')'));
const wss = new WebSocket.Server({ server: httpServer });
httpServer.listen(PORT);

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

// ── 모니터링 서버 (포트 8081) ─────────────────────────────────────

const fs   = require('fs');
const path = require('path');
const MONITOR_PORT = process.env.MONITOR_PORT || 8081;
const monitorHttpServer = http.createServer(healthHandler('CallGuard 모니터링 서버 (포트 ' + MONITOR_PORT + ')'));
const monitorWss   = new WebSocket.Server({ server: monitorHttpServer });
monitorHttpServer.listen(MONITOR_PORT);
const LOG_DIR      = path.join(__dirname, 'logs');
if (!fs.existsSync(LOG_DIR)) fs.mkdirSync(LOG_DIR);

// roomId → Set<WebSocket> (관리자 브라우저)
const adminsByRoom = new Map();

// roomId → WebSocket (실험 앱 기기 — manual_command 수신 대상)
// 기존 CallGuard 앱은 admin_join으로 접속하므로 여기 등록되지 않는다(호환 유지).
// 실험 앱(callguard-experiment)만 device_join을 보내 등록된다.
const deviceByRoom = new Map();

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
            lastInterventionTime: null,
            // ── 실험(시나리오) 확장 필드 ──
            scenarioId:     null,   // 무장된 시나리오 ID ("S1".."S10")
            scenarioParams: null,   // 발동 시점에 실제 적용된 ScenarioConfig 스냅샷
            manualOverrideEvents: [], // { time, action, params, origin: 'remote'|'device' }
            interventionSource: null, // 'auto' | 'manual'
            // 기능별 간단 설문 (파일럿 STEP 7 — 매 기능 종료 후 연구자 콘솔에서 기록)
            postSurveyAnswers: []     // { time, scenarioId, question, answer }
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
    let isDevice   = false;

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
            // 기기가 관리자보다 먼저 접속한 경우를 위해 현재 기기 연결 상태도 즉시 알려준다
            // (device_join 시점의 브로드캐스트만으로는 나중에 접속한 관리자가 상태를 모른다)
            const dev = deviceByRoom.get(room);
            ws.send(JSON.stringify({ type: 'device_status', room,
                data: { connected: !!(dev && dev.readyState === WebSocket.OPEN) } }));
            console.log(`[monitor][${room}] 관리자 연결`);
            return;
        }

        // 실험 앱 기기 등록 — 이후 관리자의 manual_command를 이 소켓으로 중계한다
        if (msg.type === 'device_join' && room) {
            joinedRoom = room;
            isDevice   = true;
            deviceByRoom.set(room, ws);
            console.log(`[monitor][${room}] 실험 기기 연결`);
            broadcastToAdmins(room, { type: 'device_status', room, data: { connected: true } });
            return;
        }

        // 관리자(원격 연구자 콘솔) → 기기 수동 명령 중계
        if (msg.type === 'manual_command' && room) {
            const device = deviceByRoom.get(room);
            const session = getOrCreateSession(room);
            session.manualOverrideEvents.push({
                time:   new Date().toISOString(),
                action: (msg.data && msg.data.action) || '',
                params: (msg.data && msg.data.params) || {},
                origin: 'remote'
            });
            if (device && device.readyState === WebSocket.OPEN) {
                device.send(JSON.stringify(msg));
                console.log(`[monitor][${room}] manual_command 중계: ${msg.data && msg.data.action}`);
            } else {
                console.log(`[monitor][${room}] manual_command 전달 실패 — 기기 미연결`);
                broadcastToAdmins(room, { type: 'command_error', room,
                    data: { message: '기기가 연결되어 있지 않습니다.', action: msg.data && msg.data.action } });
            }
            // 관리자 화면에도 명령 이력이 보이도록 세션 로그 갱신 중계
            broadcastToAdmins(room, { type: 'session_log', room, data: session });
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

            // ── 실험(시나리오) 이벤트 ─────────────────────────────
            case 'scenario_armed':
                session.scenarioId     = data.scenarioId || null;
                session.scenarioParams = data.params || null;
                break;

            case 'scenario_fired':
                session.scenarioId         = data.scenarioId || session.scenarioId;
                session.scenarioParams     = data.params || session.scenarioParams;
                session.interventionSource = data.source || null; // 'auto' | 'manual'
                session.detections.push({
                    time:      now,
                    type:      `SCENARIO_${data.scenarioId || '?'}`,
                    keywords:  [data.trigger || ''],
                    alertTime: now,
                    detectionLatencyMs: session.callStarted
                        ? (new Date(now) - new Date(session.callStarted)) : null
                });
                session.alertCount++;
                session.lastInterventionTime = now;
                session.interventionSucceeded = false;
                break;

            case 'manual_override':
                // 온디바이스 연구자 패널에서 실행된 수동 조작 (원격 명령은 위 manual_command에서 기록)
                session.manualOverrideEvents.push({
                    time:   now,
                    action: data.action || '',
                    params: data.params || {},
                    origin: 'device'
                });
                break;

            case 'observation_note':
                session.userActions.push({ time: now, action: `📝 관찰 메모: ${data.text || ''}` });
                break;

            case 'post_survey':
                // 기능별 간단 설문 (STEP 7) — 연구자 콘솔의 체크리스트 응답
                session.postSurveyAnswers.push({
                    time:       now,
                    scenarioId: data.scenarioId || session.scenarioId || null,
                    question:   data.question || '',
                    answer:     data.answer === 'true' || data.answer === true
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
             'personal_info_utterance','scenario_armed','scenario_fired','manual_override',
             'observation_note','post_survey'].includes(msg.type)) {
            broadcastToAdmins(room, { type: 'session_log', room, data: sessionLogs.get(room) || session });
        }
    });

    ws.on('close', () => {
        if (joinedRoom) {
            if (isDevice) {
                if (deviceByRoom.get(joinedRoom) === ws) deviceByRoom.delete(joinedRoom);
                broadcastToAdmins(joinedRoom, { type: 'device_status', room: joinedRoom, data: { connected: false } });
                console.log(`[monitor][${joinedRoom}] 실험 기기 연결 해제`);
            } else {
                adminsByRoom.get(joinedRoom)?.delete(ws);
                console.log(`[monitor][${joinedRoom}] 관리자 연결 해제`);
            }
        }
    });

    ws.on('error', (err) => console.error('[monitor] WS error:', err.message));
});

// ── 시작 배너: 앱에 입력할 주소 안내 ───────────────────────────────

const lanIps = getLanIPv4s();
console.log('');
console.log('══════════════════════════════════════════════════════');
console.log(`  CallGuard 서버 실행 중  (시그널링 :${PORT} / 모니터링 :${MONITOR_PORT})`);
console.log('──────────────────────────────────────────────────────');
if (lanIps.length === 0) {
    console.log('  ⚠ LAN IP를 찾지 못했습니다. 네트워크(핫스팟) 연결을 확인하세요.');
} else {
    for (const ip of lanIps) {
        const tag = ip.label ? `  ← ${ip.label}` : '';
        console.log(`  앱 서버 주소 입력:  ws://${ip.address}${tag}`);
    }
    console.log('──────────────────────────────────────────────────────');
    console.log(`  연결 점검: 폰 브라우저에서 http://${lanIps[0].address}:${PORT} 접속`);
    console.log('  (페이지가 안 뜨면 setup-firewall.bat 를 관리자 권한으로 실행)');
}
console.log('══════════════════════════════════════════════════════');
