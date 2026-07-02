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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import com.example.callguard.MainActivity
import com.example.callguard.R
import com.example.callguard.data.signaling.SignalingEvent
import com.example.callguard.data.signaling.WsSignalingClient
import com.example.callguard.data.webrtc.WebRtcManager
import com.example.callguard.domain.interfaces.BlockReason
import com.example.callguard.domain.interfaces.InterventionEvent
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.pipeline.AudioProcessingPipeline
import com.example.callguard.domain.pipeline.LocalLeakDetector
import com.example.callguard.domain.pipeline.MockInterventionEngine
import com.example.callguard.domain.pipeline.MockScamDetector
import com.example.callguard.domain.pipeline.MockSpeechRecognizer
import com.example.callguard.domain.pipeline.VoiceSurveyController
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
    lateinit var voiceSurveyController: VoiceSurveyController
        private set

    private var signalingClient: WsSignalingClient? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    // TTS 엔진 초기화가 끝나기 전에 speak()가 호출되는 레이스 컨디션 방지용 큐
    private val pendingTtsQueue = mutableListOf<Pair<String, Int>>()

    // 현재 진행 중인 차단/설문의 원인. null이면 차단/설문이 진행 중이 아님 (중복 트리거 방지 + 해제 시 무엇을 풀어야 하는지 판단)
    private var activeBlockReason: BlockReason? = null

    // ── 상태 Flow ────────────────────────────────────────────────
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _interventionEvent = MutableSharedFlow<InterventionEvent>(extraBufferCapacity = 8)
    val interventionEvent: SharedFlow<InterventionEvent> = _interventionEvent

    // 음성 설문 진행 중 답변/완료 이벤트
    private val _voiceSurveyAnswerEvent = MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 8)
    val voiceSurveyAnswerEvent: SharedFlow<Pair<Int, Boolean>> = _voiceSurveyAnswerEvent

    private val _voiceSurveyCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val voiceSurveyCompleted: SharedFlow<Unit> = _voiceSurveyCompleted

    enum class CallState { IDLE, CONNECTING, RINGING, CONNECTED, DISCONNECTED }

    inner class CallServiceBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initComponents()
        initTts()
        Log.d(TAG, "CallService 생성 완료")
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS 한국어 데이터 없음 (result=$result) — 기기에 한국어 TTS 음성 데이터 설치 필요")
                }
                isTtsReady = true
                Log.d(TAG, "TTS 초기화 완료 — 대기 중이던 ${pendingTtsQueue.size}건 재생")
                pendingTtsQueue.forEach { (text, mode) -> tts?.speak(text, mode, null, "tts_${System.nanoTime()}") }
                pendingTtsQueue.clear()
            } else {
                Log.e(TAG, "TTS 초기화 실패 (status=$status) — 이 기기엔 음성 경고가 출력되지 않습니다")
            }
        }
    }

    /**
     * 큐를 거쳐 TTS를 재생한다. 엔진이 아직 준비되지 않았으면 큐에 쌓아두고
     * 초기화 완료 시 순서대로 재생한다(레이스 컨디션으로 경고음이 조용히 사라지는 것 방지).
     */
    fun speak(text: String, flush: Boolean = false) {
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (isTtsReady) {
            tts?.speak(text, mode, null, "tts_${System.nanoTime()}")
        } else {
            Log.w(TAG, "TTS 아직 준비 안 됨 — 큐에 저장: $text")
            pendingTtsQueue.add(text to mode)
        }
    }

    /**
     * 노인 사용자가 화면을 보지 않더라도 인지할 수 있도록
     * 강한 진동 패턴 + TTS 음성으로 즉시 알린다.
     */
    fun alertUserWithVibrationAndTts(
        message: String = "주의! 개인정보 유출 위험이 감지되었습니다. 마이크가 차단되었습니다. 화면을 확인하세요."
    ) {
        // SOS 진동: 강하게 3회 반복 (500ms on / 300ms off)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 600, 250, 600, 250, 600)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))

        // TTS 경고 — 화면을 보도록 유도
        speak(message, flush = true)
    }

    /** 설문 종료 후 어떤 차단을 해제해야 하는지 판단하기 위해 원인을 꺼내고 초기화한다. */
    fun consumeBlockReason(): BlockReason {
        val reason = activeBlockReason ?: BlockReason.LOCAL_LEAK
        activeBlockReason = null
        return reason
    }

    private fun initComponents() {
        localSpeechRecognizer = MockSpeechRecognizer(this)
        remoteSpeechRecognizer = MockSpeechRecognizer(this)
        scamDetector = MockScamDetector()

        voiceSurveyController = VoiceSurveyController(
            speak = { text -> speak(text) },
            onAnswer = { questionIndex, answer ->
                lifecycleScope.launch { _voiceSurveyAnswerEvent.emit(questionIndex to answer) }
            },
            onCompleted = {
                lifecycleScope.launch { _voiceSurveyCompleted.emit(Unit) }
            }
        )

        // 마이크 차단 중에도 WebRtcManager는 로컬 STT 피드를 계속 보내준다 (상대방 전송만 끊김)
        // → 차단 후 음성 설문 응답을 같은 STT 스트림으로 받을 수 있다.
        lifecycleScope.launch {
            localSpeechRecognizer.transcript.collect { text ->
                if (voiceSurveyController.isActive.value) {
                    voiceSurveyController.onLocalTranscript(text)
                }
            }
        }

        val localLeakDetector = LocalLeakDetector { triggerPhrase, partialText ->
            if (activeBlockReason != null) return@LocalLeakDetector  // 이미 차단/설문 진행 중이면 중복 트리거 방지
            Log.w(TAG, "개인정보 누출 감지! '$triggerPhrase' → 즉시 양쪽 음성 차단")
            activeBlockReason = BlockReason.LOCAL_LEAK
            webRtcManager.setLocalAudioMuted(true)
            // 설문 중 상대방(공격자)이 계속 말을 걸어 피해자를 혼란시키지 못하도록 상대 음성도 함께 차단한다.
            webRtcManager.setRemoteAudioMuted(true)
            // 노인 사용자가 화면을 보지 않더라도 인지하도록 진동 + TTS 알림
            alertUserWithVibrationAndTts(
                "주의! 개인정보 유출 위험이 감지되었습니다. 마이크와 상대방 음성이 차단되었습니다. 설문에 답해주세요."
            )
            lifecycleScope.launch {
                _interventionEvent.emit(InterventionEvent.LocalLeakBlocked(triggerPhrase, partialText))
            }
            // 경고 음성 뒤에 이어서 음성 설문을 시작한다 (QUEUE_ADD로 알림 뒤에 자동 재생)
            voiceSurveyController.start()
        }

        // SCAM 확정은 아래 riskScoreFlow 콜렉터에서 양쪽 음성 차단 + 설문까지 전부 처리하므로
        // 여기서는 더 이상 개입하지 않는다 (MockInterventionEngine은 SUSPICIOUS 경고 emit용으로만 사용).
        val interventionEngine = MockInterventionEngine { }

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

        // 상대방 발화가 SCAM으로 확정되면: 내 마이크 + 상대 음성 모두 차단 → 경고 → 음성 설문 시작
        lifecycleScope.launch {
            audioPipeline.riskScoreFlow.collect { risk ->
                if (activeBlockReason != null) return@collect
                when (risk.level) {
                    RiskLevel.SCAM -> {
                        Log.w(TAG, "상대방 발화 보이스피싱 확정 (키워드: ${risk.matchedKeywords}) → 양쪽 음성 차단")
                        activeBlockReason = BlockReason.REMOTE_PHISHING
                        webRtcManager.setLocalAudioMuted(true)
                        webRtcManager.setRemoteAudioMuted(true)
                        alertUserWithVibrationAndTts(
                            "주의! 보이스피싱이 의심되는 통화입니다. 마이크와 상대방 음성이 차단되었습니다. 설문에 답해주세요."
                        )
                        _interventionEvent.emit(InterventionEvent.RemotePhishingBlocked(risk, remoteAlsoMuted = true))
                        voiceSurveyController.start()
                    }
                    RiskLevel.SUSPICIOUS -> {
                        // 아직 SCAM 확정 전 의심 단계지만, 화면을 못 보는 피해자도 즉시 인지하도록
                        // TTS로 상황을 설명하고, 확인이 끝나기 전까지 양쪽 음성을 선제 차단한다.
                        Log.w(TAG, "상대방 발화 보이스피싱 의심 (키워드: ${risk.matchedKeywords}) → 양쪽 음성 선제 차단")
                        activeBlockReason = BlockReason.SUSPECTED_REMOTE
                        webRtcManager.setLocalAudioMuted(true)
                        webRtcManager.setRemoteAudioMuted(true)
                        alertUserWithVibrationAndTts(
                            "주의하세요! 방금 상대방의 말에서 보이스피싱이 의심되는 표현이 감지되었습니다. " +
                                "확인을 위해 마이크와 상대방 음성을 잠시 차단합니다. 들리는 질문에 예 또는 아니오로 답해주세요."
                        )
                        _interventionEvent.emit(InterventionEvent.RemotePhishingBlocked(risk, remoteAlsoMuted = true))
                        voiceSurveyController.start()
                    }
                    else -> {}
                }
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
    fun joinRoom(serverAddress: String, roomId: String) {
        _callState.value = CallState.CONNECTING
        // 입력이 ws:// 또는 wss://로 시작하면 그대로 사용, 아니면 ws://IP:8080 으로 구성
        val url = when {
            serverAddress.startsWith("ws://") || serverAddress.startsWith("wss://") -> {
                // 포트가 없고 plain IP인 경우 :8080 추가
                if (!serverAddress.contains(Regex(":\\d+$")) &&
                    serverAddress.startsWith("ws://") &&
                    serverAddress.removePrefix("ws://").matches(Regex("[\\d.]+"))) {
                    "$serverAddress:8080"
                } else {
                    serverAddress
                }
            }
            else -> "ws://$serverAddress:8080"
        }

        signalingClient = WsSignalingClient(url, roomId)

        lifecycleScope.launch {
            signalingClient!!.events.collect { event ->
                handleSignalingEvent(event)
            }
        }

        signalingClient!!.connect()
        Log.d(TAG, "시그널링 서버 연결 시도: $url / 방: $roomId (입력: $serverAddress)")
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

    fun startVoiceSurvey() = voiceSurveyController.start()
    fun stopVoiceSurvey() = voiceSurveyController.stop()

    /**
     * 설문 결과 보이스피싱이 아니라고 판단되어 통화를 재개할 때 호출.
     * scamDetector/localLeakDetector에 누적된 키워드를 비우지 않으면 matchedKeywords가
     * 그대로 남아 있어 재개 직후 들어오는 다음 발화(STT)에서 emitRisk()가 같은 위험도를
     * 즉시 재발사 → activeBlockReason이 비워진 상태라 바로 재차단+재설문이 시작되는 루프가 생긴다.
     */
    fun resumeAfterFalseAlarm() {
        audioPipeline.scamDetector.reset()
        audioPipeline.localLeakDetector.reset()
    }

    fun hangUpCall() {
        signalingClient?.sendCallEnd()
        signalingClient?.disconnect()
        signalingClient = null
        webRtcManager.stopCall()
        audioPipeline.reset()
        activeBlockReason = null
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
        tts?.stop()
        tts?.shutdown()
        hangUpCall()
        super.onDestroy()
    }
}
