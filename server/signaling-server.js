/**
 * CallGuard WebRTC Signaling Server
 *
 * 역할: 두 Android 기기 사이에서 WebRTC SDP/ICE 메시지를 중계한다.
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

wss.on('connection', (ws) => {
    let currentRoom = null;
    let myRole = null;

    ws.on('message', (rawData) => {
        let msg;
        try {
            msg = JSON.parse(rawData);
        } catch {
            return;
        }

        switch (msg.type) {

            // ── 방 입장 ──────────────────────────────────────────
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
                    // 두 사람 모두 입장했으니 caller에게 offer 생성 지시
                    send(room.caller, { type: 'start_call' });
                } else {
                    send(ws, { type: 'error', message: '방이 꽉 찼습니다.' });
                    ws.close();
                }
                break;
            }

            // ── SDP / ICE 중계 (상대방에게 그대로 전달) ──────────
            case 'offer':
            case 'answer':
            case 'ice_candidate':
            case 'call_end': {
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
        if (!currentRoom) return;
        const room = rooms.get(currentRoom);
        if (!room) return;

        // 상대방에게 통화 종료 알림
        const peer = myRole === 'caller' ? room.callee : room.caller;
        if (peer && peer.readyState === WebSocket.OPEN) {
            send(peer, { type: 'call_end' });
        }

        // 방 정리
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

function send(ws, obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

console.log(`CallGuard 시그널링 서버 실행 중 → ws://localhost:${PORT}`);
