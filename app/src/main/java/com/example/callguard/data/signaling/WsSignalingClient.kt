package com.example.callguard.data.signaling

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WsSignalingClient — OkHttp WebSocket을 이용해 시그널링 서버와 통신한다.
 *
 * 사용법:
 *   val client = WsSignalingClient("ws://192.168.0.10:8080", "room-1234")
 *   client.connect()
 *   client.events.collect { event -> ... }
 */
class WsSignalingClient(
    private val serverUrl: String,
    private val roomId: String
) : SignalingClient {

    private val TAG = "WsSignalingClient"

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SignalingEvent> = _events

    /** 서버가 부여한 역할 (caller / callee) */
    var role: String = "caller"
        private set

    /** caller 여부 — WebRtcManager가 offer 생성 여부를 판단할 때 사용 */
    val isCaller: Boolean get() = role == "caller"

    private var ws: WebSocket? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket은 timeout 없이 유지
        .build()

    override fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 연결 성공 → 방[$roomId] 입장 요청")
                webSocket.send(json("type" to "join", "room" to roomId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "수신: $text")
                try {
                    val msg = JSONObject(text)
                    when (msg.getString("type")) {
                        "joined" -> {
                            role = msg.optString("role", "caller")
                            Log.d(TAG, "방 입장 완료. 역할: $role")
                        }
                        "start_call" -> {
                            // 서버가 caller에게 offer 생성 지시
                            _events.tryEmit(SignalingEvent.StartCall)
                        }
                        "offer" -> {
                            _events.tryEmit(SignalingEvent.OfferReceived(msg.getString("sdp")))
                        }
                        "answer" -> {
                            _events.tryEmit(SignalingEvent.AnswerReceived(msg.getString("sdp")))
                        }
                        "ice_candidate" -> {
                            _events.tryEmit(
                                SignalingEvent.IceCandidateReceived(
                                    sdpMid = msg.getString("sdpMid"),
                                    sdpMLineIndex = msg.getInt("sdpMLineIndex"),
                                    sdp = msg.getString("sdp")
                                )
                            )
                        }
                        "call_end" -> {
                            _events.tryEmit(SignalingEvent.CallEnded)
                        }
                        "error" -> {
                            Log.e(TAG, "서버 에러: ${msg.optString("message")}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "메시지 파싱 오류: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 오류: ${t.message}")
                _events.tryEmit(SignalingEvent.CallEnded)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 종료: $reason")
            }
        })
    }

    override fun sendOffer(sdp: String) {
        ws?.send(json("type" to "offer", "sdp" to sdp))
    }

    override fun sendAnswer(sdp: String) {
        ws?.send(json("type" to "answer", "sdp" to sdp))
    }

    override fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        ws?.send(
            json(
                "type" to "ice_candidate",
                "sdpMid" to sdpMid,
                "sdpMLineIndex" to sdpMLineIndex,
                "sdp" to sdp
            )
        )
    }

    override fun sendCallEnd() {
        ws?.send(json("type" to "call_end"))
    }

    override fun disconnect() {
        ws?.close(1000, "정상 종료")
        ws = null
    }

    private fun json(vararg pairs: Pair<String, Any>): String {
        val obj = JSONObject()
        pairs.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
