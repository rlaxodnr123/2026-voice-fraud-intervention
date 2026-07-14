package com.example.callguard.data.signaling

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Modeling Signaling Events.
 */
sealed class SignalingEvent {
    /** 서버가 caller에게 offer 생성을 지시 (callee가 방에 입장했을 때) */
    object StartCall : SignalingEvent()
    data class OfferReceived(val sdp: String) : SignalingEvent()
    data class AnswerReceived(val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(val sdpMid: String, val sdpMLineIndex: Int, val sdp: String) : SignalingEvent()
    object CallEnded : SignalingEvent()
    /** 서버가 방 입장을 거부함 (예: 방이 이미 꽉 참, 방 코드 불일치로 서로 다른 방에 들어간 경우 등) */
    data class RoomError(val message: String) : SignalingEvent()
    /**
     * 상대 기기가 음성 대신 텍스트로 보낸 "발화". 실험(연구자가 공격자 대본을 음성 대신
     * 텍스트로 입력하는 경우)을 위한 것으로, 수신 측은 이 텍스트를 실제 원격 STT 결과처럼
     * 그대로 분석 파이프라인에 흘려보낸다.
     */
    data class TypedSpeechReceived(val text: String) : SignalingEvent()
}

/**
 * SignalingClient manages call negotiation (SDP exchange and ICE candidates).
 */
interface SignalingClient {
    val events: SharedFlow<SignalingEvent>
    fun connect()
    fun sendOffer(sdp: String)
    fun sendAnswer(sdp: String)
    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String)
    fun sendCallEnd()
    /** 음성 대신 텍스트로 입력한 발화를 상대 기기로 전달한다 (실험용). */
    fun sendTypedSpeech(text: String)
    fun disconnect()
}

/**
 * MockLoopbackSignalingClient simulates call signaling on the same device
 * by looping messages back with a slight delay.
 */
class MockLoopbackSignalingClient : SignalingClient {

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SignalingEvent> = _events

    private val handler = Handler(Looper.getMainLooper())

    override fun connect() {
        // Mock connection success
    }

    override fun sendOffer(sdp: String) {
        // In loopback mode, the offer is treated as an incoming call offer for the receiver peer
        handler.postDelayed({
            _events.tryEmit(SignalingEvent.OfferReceived(sdp))
        }, 300)
    }

    override fun sendAnswer(sdp: String) {
        // The answer is piped back to the caller peer
        handler.postDelayed({
            _events.tryEmit(SignalingEvent.AnswerReceived(sdp))
        }, 300)
    }

    override fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        // Forward ICE candidates back to the peer
        handler.postDelayed({
            _events.tryEmit(SignalingEvent.IceCandidateReceived(sdpMid, sdpMLineIndex, sdp))
        }, 100)
    }

    override fun sendCallEnd() {
        _events.tryEmit(SignalingEvent.CallEnded)
    }

    override fun sendTypedSpeech(text: String) {
        // 루프백(단일 기기)에는 실제 상대가 없으므로 곧바로 같은 이벤트로 되돌린다.
        handler.postDelayed({
            _events.tryEmit(SignalingEvent.TypedSpeechReceived(text))
        }, 100)
    }

    override fun disconnect() {
        handler.removeCallbacksAndMessages(null)
    }
}
