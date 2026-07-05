package com.example.callguard.data.monitoring

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 모니터링 전용 WebSocket 클라이언트 (포트 8081).
 *
 * 시그널링 클라이언트(포트 8080)와 완전히 별개 연결을 유지하여
 * 모니터링 이벤트 전송이 WebRTC 시그널링 큐에 영향을 주지 않도록 한다.
 *
 * 모든 전송은 fire-and-forget: 실패해도 예외를 전파하지 않아
 * 모니터링 장애가 통화 차단 동작에 영향을 줄 수 없다.
 */
class MonitoringClient(
    private val serverUrl: String,
    private val roomId: String
) {
    private val TAG = "MonitoringClient"

    private var ws: WebSocket? = null
    private val throttleMap = HashMap<String, Long>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        try {
            val request = Request.Builder().url(serverUrl).build()
            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "모니터링 서버 연결 성공 → 방[$roomId]")
                    webSocket.send(buildJson("type" to "admin_join", "room" to roomId))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "모니터링 서버 연결 실패 (통화에 영향 없음): ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "모니터링 연결 종료: $reason")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "모니터링 connect() 예외 (통화에 영향 없음): ${e.message}")
        }
    }

    /** 즉시 전송. 개입 이벤트처럼 빠짐없이 전달해야 하는 이벤트에 사용. */
    fun sendEvent(type: String, payload: Map<String, Any>) {
        try {
            val data = JSONObject()
            payload.forEach { (k, v) ->
                when (v) {
                    is List<*> -> data.put(k, JSONArray(v))
                    is Number  -> data.put(k, v)
                    else       -> data.put(k, v.toString())
                }
            }
            val full = JSONObject()
            full.put("type", type)
            full.put("room", roomId)
            full.put("ts", System.currentTimeMillis())
            full.put("data", data)
            ws?.send(full.toString())
        } catch (e: Exception) {
            Log.v(TAG, "sendEvent 예외 무시: ${e.message}")
        }
    }

    /**
     * 쓰로틀 전송. Partial STT처럼 고빈도 이벤트에 사용.
     * [key]로 마지막 전송 시각을 추적하여 [intervalMs] 이내 중복 전송을 억제한다.
     */
    fun sendThrottled(
        key: String,
        type: String,
        payload: Map<String, Any>,
        intervalMs: Long = 500
    ) {
        val now = System.currentTimeMillis()
        if (now - (throttleMap[key] ?: 0L) < intervalMs) return
        throttleMap[key] = now
        sendEvent(type, payload)
    }

    fun disconnect() {
        try {
            ws?.close(1000, "통화 종료")
        } catch (_: Exception) {}
        ws = null
        throttleMap.clear()
    }

    private fun buildJson(vararg pairs: Pair<String, Any>): String {
        val obj = JSONObject()
        pairs.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
