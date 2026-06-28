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
