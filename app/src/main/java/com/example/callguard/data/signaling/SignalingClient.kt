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

    override fun disconnect() {
        handler.removeCallbacksAndMessages(null)
    }
}
