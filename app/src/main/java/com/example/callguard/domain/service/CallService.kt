package com.example.callguard.domain.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.callguard.MainActivity
import com.example.callguard.R
import com.example.callguard.data.signaling.SignalingEvent
import com.example.callguard.data.signaling.WsSignalingClient
import com.example.callguard.data.webrtc.WebRtcManager
import com.example.callguard.domain.interfaces.InterventionEvent
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.pipeline.AudioProcessingPipeline
import com.example.callguard.domain.pipeline.LocalLeakDetector
import com.example.callguard.domain.pipeline.MockInterventionEngine
import com.example.callguard.domain.pipeline.MockScamDetector
import com.example.callguard.domain.pipeline.MockSpeechRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CallService : LifecycleService() {
    private val TAG = "CallService"
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "CallServiceChannel"

    private val binder = CallServiceBinder()

    // ── 핵심 컴포넌트 ─────────────────────────────────────────────
    lateinit var webRtcManager: WebRtcManager
        private set
    lateinit var audioPipeline: AudioProcessingPipeline
        private set
    lateinit var localSpeechRecognizer: MockSpeechRecognizer
    lateinit var remoteSpeechRecognizer: MockSpeechRecognizer
    lateinit var scamDetector: MockScamDetector

    private var signalingClient: WsSignalingClient? = null

    // ── 상태 Flow ────────────────────────────────────────────────
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _interventionEvent = MutableSharedFlow<InterventionEvent>(extraBufferCapacity = 8)
    val interventionEvent: SharedFlow<InterventionEvent> = _interventionEvent

    enum class CallState { IDLE, CONNECTING, RINGING, CONNECTED, DISCONNECTED }

    inner class CallServiceBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initComponents()
        Log.d(TAG, "CallService 생성 완료")
    }

    private fun initComponents() {
        localSpeechRecognizer = MockSpeechRecognizer(this)
        remoteSpeechRecognizer = MockSpeechRecognizer(this)
        scamDetector = MockScamDetector()

        val localLeakDetector = LocalLeakDetector { triggerPhrase, partialText ->
            Log.w(TAG, "개인정보 누출 감지! '$triggerPhrase' → 즉시 마이크 차단")
            // 즉시 마이크 뮤트
            webRtcManager.setLocalAudioMuted(true)
            // 개입 이벤트 발행
            lifecycleScope.launch {
                _interventionEvent.emit(InterventionEvent.LocalLeakBlocked(triggerPhrase, partialText))
            }
        }

        val interventionEngine = MockInterventionEngine { riskLevel ->
            if (riskLevel == RiskLevel.SCAM) {
                webRtcManager.setRemoteAudioMuted(true)
            }
        }

        audioPipeline = AudioProcessingPipeline(
            localSpeechRecognizer,
            remoteSpeechRecognizer,
            scamDetector,
            interventionEngine,
            localLeakDetector
        )

        webRtcManager = WebRtcManager(
            context = applicationContext,
            localAudioCallback = { data, rate, ch -> audioPipeline.onLocalAudioFrame(data, rate, ch) },
            remoteAudioCallback = { data, rate, ch -> audioPipeline.onRemoteAudioFrame(data, rate, ch) }
        )

        // Pipeline의 개입 이벤트를 서비스 Flow로 중계
        lifecycleScope.launch {
            audioPipeline.interventionEvent.collect { event ->
                _interventionEvent.emit(event)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // ── 통화 시작 (실제 P2P) ────────────────────────────────────

    /**
     * 시그널링 서버에 연결하고 방에 입장한다.
     * @param serverIp 시그널링 서버 IP (예: "192.168.0.10")
     * @param roomId   공유 방 코드
     */
    fun joinRoom(serverIp: String, roomId: String) {
        _callState.value = CallState.CONNECTING
        val url = "ws://$serverIp:8080"

        signalingClient = WsSignalingClient(url, roomId)

        lifecycleScope.launch {
            signalingClient!!.events.collect { event ->
                handleSignalingEvent(event)
            }
        }

        signalingClient!!.connect()
        Log.d(TAG, "시그널링 서버 연결 시도: $url / 방: $roomId")
    }

    private fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.StartCall -> {
                // 서버가 caller에게 offer 생성 지시 (callee가 입장했음)
                Log.d(TAG, "StartCall 수신 → Offer 생성")
                _callState.value = CallState.RINGING
                webRtcManager.startLocalAudioCapture()
                webRtcManager.createPeerConnection(
                    onIceCandidate = { candidate ->
                        signalingClient?.sendIceCandidate(
                            candidate.sdpMid ?: "",
                            candidate.sdpMLineIndex,
                            candidate.sdp
                        )
                    },
                    onConnected = {
                        Log.d(TAG, "P2P 연결 확립")
                        _callState.value = CallState.CONNECTED
                    },
                    onDisconnected = {
                        Log.d(TAG, "P2P 연결 끊김")
                        hangUpCall()
                    }
                )
                webRtcManager.createOffer(
                    onSuccess = { sdp ->
                        signalingClient?.sendOffer(sdp.description)
                        Log.d(TAG, "Offer 전송 완료")
                    },
                    onFailure = { Log.e(TAG, "Offer 생성 실패: $it") }
                )
            }

            is SignalingEvent.OfferReceived -> {
                // callee: offer 수신 → answer 생성
                Log.d(TAG, "Offer 수신 → Answer 생성")
                _callState.value = CallState.RINGING
                webRtcManager.startLocalAudioCapture()
                webRtcManager.createPeerConnection(
                    onIceCandidate = { candidate ->
                        signalingClient?.sendIceCandidate(
                            candidate.sdpMid ?: "",
                            candidate.sdpMLineIndex,
                            candidate.sdp
                        )
                    },
                    onConnected = {
                        Log.d(TAG, "P2P 연결 확립")
                        _callState.value = CallState.CONNECTED
                    },
                    onDisconnected = {
                        Log.d(TAG, "P2P 연결 끊김")
                        hangUpCall()
                    }
                )
                webRtcManager.setRemoteOfferAndCreateAnswer(
                    sdp = event.sdp,
                    onSuccess = { answer ->
                        signalingClient?.sendAnswer(answer.description)
                        Log.d(TAG, "Answer 전송 완료")
                    },
                    onFailure = { Log.e(TAG, "Answer 생성 실패: $it") }
                )
            }

            is SignalingEvent.AnswerReceived -> {
                Log.d(TAG, "Answer 수신")
                webRtcManager.setRemoteAnswer(event.sdp)
            }

            is SignalingEvent.IceCandidateReceived -> {
                webRtcManager.addIceCandidate(event.sdpMid, event.sdpMLineIndex, event.sdp)
            }

            is SignalingEvent.CallEnded -> {
                Log.d(TAG, "상대방이 통화 종료")
                hangUpCall()
            }
        }
    }

    // ── 통화 제어 ─────────────────────────────────────────────────

    fun muteLocalMic(mute: Boolean) = webRtcManager.setLocalAudioMuted(mute)
    fun muteRemoteAudio(mute: Boolean) = webRtcManager.setRemoteAudioMuted(mute)

    fun hangUpCall() {
        signalingClient?.sendCallEnd()
        signalingClient?.disconnect()
        signalingClient = null
        webRtcManager.stopCall()
        audioPipeline.reset()
        _callState.value = CallState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "통화 종료")
    }

    // ── 테스트용 루프백 ──────────────────────────────────────────

    fun startLoopbackCall() {
        _callState.value = CallState.RINGING
        webRtcManager.startLocalAudioCapture()
        // startLoopbackCall 내부에서 PeerConnection 2개를 생성하고 협상까지 처리
        webRtcManager.startLoopbackCall(
            onConnected = { _callState.value = CallState.CONNECTED },
            onDisconnected = { hangUpCall() }
        )
    }

    // ── 시뮬레이션 (테스트) ──────────────────────────────────────

    fun simulateRemoteSpeech(phrase: String) {
        remoteSpeechRecognizer.injectPhrase(phrase)
    }

    fun simulateLocalSpeech(phrase: String) {
        localSpeechRecognizer.injectPhrase(phrase)
    }

    // ── 알림 ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.call_notification_title))
            .setContentText(getString(R.string.call_notification_desc))
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        hangUpCall()
        super.onDestroy()
    }
}
