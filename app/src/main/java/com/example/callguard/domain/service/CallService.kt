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
import java.io.File
import java.util.Locale
import com.example.callguard.MainActivity
import com.example.callguard.R
import com.example.callguard.data.monitoring.MonitoringClient
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
import com.example.callguard.domain.pipeline.SensitiveDisclosureDetector
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
    private var monitoringClient: MonitoringClient? = null

    // 모니터링 서버가 응답하지 않는 경우를 대비한 기기 내 로컬 백업
    private val localLogFile: File by lazy {
        File(getExternalFilesDir(null) ?: filesDir, "callguard_log_${System.currentTimeMillis()}.json")
    }

    // stt_partial 로컬 로그 쓰로틀 (서버와 동일하게 화자별 500ms 간격)
    private val lastPartialLogTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // hangUpCall() 이중 실행 방지 플래그 (endCall → stopSelf → onDestroy 경로에서 재진입 차단)
    private var isHangingUp = false

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    // TTS 엔진 초기화가 끝나기 전에 speak()가 호출되는 레이스 컨디션 방지용 큐
    // Triple(text, mode, utteranceId) — 초기화 완료 시 utteranceId 유지하며 재생
    private val pendingTtsQueue = mutableListOf<Triple<String, Int, String>>()
    // utteranceId → 발화 완료 시 호출할 콜백 (예: 음성 설문의 리스닝 시작)
    private val ttsDoneCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    // 현재 진행 중인 차단/설문의 원인. null이면 차단/설문이 진행 중이 아님 (중복 트리거 방지 + 해제 시 무엇을 풀어야 하는지 판단)
    // 로컬 누출 감지 코루틴과 원격 피싱 감지 코루틴이 동시에 접근하므로 @Volatile + 동기화로 보호
    @Volatile private var activeBlockReason: BlockReason? = null
    private val blockLock = Any()

    /**
     * 차단 원인을 원자적으로 선점한다. 이미 다른 원인이 진행 중이면 false를 반환해
     * 두 감지 경로가 동시에 차단·설문을 이중 시작하는 race를 막는다.
     */
    private fun tryClaimBlock(reason: BlockReason): Boolean = synchronized(blockLock) {
        if (activeBlockReason != null) return false
        activeBlockReason = reason
        return true
    }

    // 여러 코루틴에서 비동기로 add되므로 스레드 안전 리스트로 감싼다
    private val localLogEntries = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any?>>())

    // ── 상태 Flow ────────────────────────────────────────────────
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _interventionEvent = MutableSharedFlow<InterventionEvent>(extraBufferCapacity = 8)
    val interventionEvent: SharedFlow<InterventionEvent> = _interventionEvent

    // 사용자에게 보여줄 실패/안내 메시지 (예: 방 입장 실패 사유)
    private val _serviceMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val serviceMessage: SharedFlow<String> = _serviceMessage

    // 음성 설문 진행 중 답변/완료 이벤트
    private val _voiceSurveyAnswerEvent = MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 8)
    val voiceSurveyAnswerEvent: SharedFlow<Pair<Int, Boolean>> = _voiceSurveyAnswerEvent

    // true = 반복 무응답으로 강제 종료됨 (통화를 재개하지 않고 끊어야 함), false = 정상 완료
    private val _voiceSurveyCompleted = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val voiceSurveyCompleted: SharedFlow<Boolean> = _voiceSurveyCompleted

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
                // 발화 완료 시점을 정확히 알기 위해 progress listener 등록
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id ->
                            ttsDoneCallbacks.remove(id)?.invoke()
                        }
                    }
                    @Deprecated("deprecated in API level 21")
                    override fun onError(utteranceId: String?) {
                        // 오류 시에도 콜백을 소비해 리스닝이 영영 시작되지 않는 상황을 막는다
                        utteranceId?.let { id -> ttsDoneCallbacks.remove(id)?.invoke() }
                    }
                })
                isTtsReady = true
                Log.d(TAG, "TTS 초기화 완료 — 대기 중이던 ${pendingTtsQueue.size}건 재생")
                pendingTtsQueue.forEach { (text, mode, uttId) -> tts?.speak(text, mode, null, uttId) }
                pendingTtsQueue.clear()
            } else {
                Log.e(TAG, "TTS 초기화 실패 (status=$status) — 이 기기엔 음성 경고가 출력되지 않습니다")
            }
        }
    }

    /**
     * 큐를 거쳐 TTS를 재생한다. 엔진이 아직 준비되지 않았으면 큐에 쌓아두고
     * 초기화 완료 시 순서대로 재생한다(레이스 컨디션으로 경고음이 조용히 사라지는 것 방지).
     *
     * @param onDone 발화가 실제로 끝난 시점에 호출되는 콜백 (예: 음성 설문 리스닝 시작).
     *               TTS가 준비 안 됐거나 엔진 초기화 실패 시에도 재생 완료 시점에 호출된다.
     */
    fun speak(text: String, flush: Boolean = false, onDone: (() -> Unit)? = null) {
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "tts_${System.nanoTime()}"
        onDone?.let { ttsDoneCallbacks[utteranceId] = it }
        if (isTtsReady) {
            tts?.speak(text, mode, null, utteranceId)
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                monitoringClient?.sendEvent("tts_played", mapOf("text" to text))
            }
            appendLocalLog("tts_played", mapOf("text" to text))
        } else {
            Log.w(TAG, "TTS 아직 준비 안 됨 — 큐에 저장: $text")
            pendingTtsQueue.add(Triple(text, mode, utteranceId))
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
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            monitoringClient?.sendEvent("vibration_triggered", mapOf("pattern" to "SOS_3x600ms"))
        }
        appendLocalLog("vibration_triggered", mapOf("pattern" to "SOS_3x600ms"))

        // TTS 경고 — 화면을 보도록 유도
        speak(message, flush = true)
    }

    // 상대방 발화가 SUSPICIOUS 단계일 때 반복 경고를 막기 위한 1회성 플래그.
    // resumeAfterFalseAlarm()/reset 시점에 다시 false로 되돌려 다음 위험 감지에서도 경고가 나가게 한다.
    @Volatile private var suspiciousWarned = false

    /**
     * 서비스 인스턴스가 통화 간 재사용될 수 있으므로(joinRoom/startLoopbackCall 모두 재호출 가능),
     * 이전 통화에서 남은 1회성 플래그를 새 통화 시작 시 함께 리셋한다.
     * 새 플래그가 추가되면 여기 한 곳만 수정하면 되도록 모아둔다.
     */
    private fun resetForNewCall() {
        isHangingUp = false  // 재통화 시 hangUpCall 무력화 방지
        suspiciousWarned = false  // 이전 통화에서 SUSPICIOUS 경고가 나간 채 종료됐다면 다음 통화를 위해 리셋
    }

    /**
     * 개인정보 유출(직접 발화 또는 공격자 요구 직후 숫자 발화) 감지 시 공통 차단 경로.
     * LocalLeakDetector와 SensitiveDisclosureDetector 양쪽에서 호출된다.
     */
    private fun triggerLocalLeakBlock(triggerPhrase: String, partialText: String) {
        // 이미 차단/설문 진행 중이면 원자적으로 선점 실패 → 중복 트리거 방지
        if (!tryClaimBlock(BlockReason.LOCAL_LEAK)) return
        Log.w(TAG, "개인정보 누출 감지! '$triggerPhrase' → 즉시 양쪽 음성 차단")

        // ── 개인정보 발화 감지 기록 ──
        // 발화 여부(utterance)와 제공 시도(disclosure)는 감지 시점에 항상 동시 성립하므로
        // (감지 순간 마이크가 아직 활성 → 일부 프레임이 상대에게 전달됐을 수 있음) 단일 이벤트로 기록하고,
        // 마이크 활성 여부를 필드로 남겨 연구 분석 시 두 개념을 구분할 수 있게 한다.
        val leakData = mapOf(
            "triggerPhrase" to triggerPhrase,
            "partialText" to partialText,
            "micWasActiveAtDetection" to true  // 감지 시점 마이크 활성 = 제공 시도로 간주 가능
        )
        appendLocalLog("personal_info_utterance", leakData)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            monitoringClient?.sendEvent("personal_info_utterance", leakData)
        }
        // SCAM/SUSPICIOUS 경로와 동일한 스키마(level/keywords/action)로도 남겨,
        // 분석 시 "intervention" 이벤트 하나만 집계해도 개입 종류(원격 위험/로컬 유출) 구분 없이 전부 잡히게 한다.
        val interventionData = mapOf(
            "level" to "LOCAL_LEAK",
            "keywords" to listOf(triggerPhrase),
            "action" to "BLOCKED"
        )
        appendLocalLog("intervention", interventionData)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            monitoringClient?.sendEvent("intervention", interventionData)
        }

        // activeBlockReason은 tryClaimBlock에서 이미 LOCAL_LEAK로 설정됨
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

    private fun initComponents() {
        localSpeechRecognizer = MockSpeechRecognizer(this)
        remoteSpeechRecognizer = MockSpeechRecognizer(this)
        scamDetector = MockScamDetector()

        voiceSurveyController = VoiceSurveyController(
            speak = { text, onDone -> speak(text, onDone = onDone) },
            onAnswer = { questionIndex, answer ->
                lifecycleScope.launch { _voiceSurveyAnswerEvent.emit(questionIndex to answer) }
            },
            onCompleted = { timedOut ->
                lifecycleScope.launch { _voiceSurveyCompleted.emit(timedOut) }
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
            triggerLocalLeakBlock(triggerPhrase, partialText)
        }

        // 공격자가 민감정보를 요구한 직후 피해자가 숫자로 답하는 "실제 유출 순간"도
        // 동일한 LOCAL_LEAK 차단 경로로 처리한다 (트리거 문구만 구분).
        val sensitiveDisclosureDetector = SensitiveDisclosureDetector { label, partialText ->
            triggerLocalLeakBlock("$label(발화 감지)", partialText)
        }

        // SCAM 확정은 아래 riskScoreFlow 콜렉터에서 양쪽 음성 차단 + 설문까지 전부 처리하므로
        // 여기서는 더 이상 개입하지 않는다 (MockInterventionEngine은 SUSPICIOUS 경고 emit용으로만 사용).
        val interventionEngine = MockInterventionEngine { }

        audioPipeline = AudioProcessingPipeline(
            localSpeechRecognizer,
            remoteSpeechRecognizer,
            scamDetector,
            interventionEngine,
            localLeakDetector,
            sensitiveDisclosureDetector
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

        // ── 모니터링 이벤트 수집 (개입 경로와 완전히 분리된 별도 코루틴) ──

        // STT Final: 말풍선 스트림
        lifecycleScope.launch {
            audioPipeline.transcriptFlow.collect { (speaker, text) ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    monitoringClient?.sendEvent("stt_final", mapOf("speaker" to speaker, "text" to text))
                }
                appendLocalLog("stt_final", mapOf("speaker" to speaker, "text" to text))
            }
        }

        // STT Partial: 500ms 쓰로틀 (고빈도 억제)
        lifecycleScope.launch {
            audioPipeline.partialTranscriptFlow.collect { (speaker, text) ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    monitoringClient?.sendThrottled(
                        "partial_$speaker", "stt_partial",
                        mapOf("speaker" to speaker, "text" to text), 500
                    )
                }
                // 로컬 로그도 화자별로 500ms 쓰로틀 적용 (파일 크기 제한).
                // 단일 타임스탬프를 쓰면 LOCAL·REMOTE가 서로의 창을 잡아먹어 한쪽이 누락되므로 화자별 맵 사용.
                val now = System.currentTimeMillis()
                if (now - (lastPartialLogTime[speaker] ?: 0L) >= 500) {
                    lastPartialLogTime[speaker] = now
                    appendLocalLog("stt_partial", mapOf("speaker" to speaker, "text" to text))
                }
            }
        }

        // 리스크 점수 변화: 200ms 쓰로틀
        lifecycleScope.launch {
            audioPipeline.riskScoreFlow.collect { risk ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    monitoringClient?.sendThrottled(
                        "risk", "risk_update",
                        mapOf(
                            "level" to risk.level.name,
                            "probability" to risk.probability,
                            "keywords" to risk.matchedKeywords
                        ), 200
                    )
                }
            }
        }

        // 상대방 발화가 SCAM으로 확정되면: 내 마이크 + 상대 음성 모두 차단 → 경고 → 음성 설문 시작
        lifecycleScope.launch {
            audioPipeline.riskScoreFlow.collect { risk ->
                if (activeBlockReason != null) return@collect
                when (risk.level) {
                    RiskLevel.SCAM -> {
                        // 원자적 선점 실패 시(다른 경로가 방금 차단 시작) 조용히 무시
                        if (!tryClaimBlock(BlockReason.REMOTE_PHISHING)) return@collect
                        Log.w(TAG, "상대방 발화 보이스피싱 확정 (키워드: ${risk.matchedKeywords}) → 양쪽 음성 차단")
                        webRtcManager.setLocalAudioMuted(true)
                        webRtcManager.setRemoteAudioMuted(true)
                        alertUserWithVibrationAndTts(
                            "주의! 보이스피싱이 의심되는 통화입니다. 마이크와 상대방 음성이 차단되었습니다. 설문에 답해주세요."
                        )
                        _interventionEvent.emit(InterventionEvent.RemotePhishingBlocked(risk, remoteAlsoMuted = true))
                        voiceSurveyController.start()
                        launch(kotlinx.coroutines.Dispatchers.IO) {
                            monitoringClient?.sendEvent("intervention", mapOf(
                                "level" to "SCAM",
                                "keywords" to risk.matchedKeywords,
                                "action" to "BLOCKED"
                            ))
                        }
                        appendLocalLog("intervention", mapOf(
                            "level" to "SCAM",
                            "keywords" to risk.matchedKeywords,
                            "action" to "BLOCKED"
                        ))
                    }
                    RiskLevel.SUSPICIOUS -> {
                        // 아직 SCAM 확정 전 의심 단계 — 차단·설문 없이 경고만 한다.
                        // (마이크/상대 음성 차단은 SCAM 확정 또는 민감정보 실제 발화 감지 시에만 수행)
                        // 같은 통화에서 반복 경고로 방해하지 않도록 1회만 알린다.
                        if (suspiciousWarned) return@collect
                        suspiciousWarned = true
                        Log.w(TAG, "상대방 발화 보이스피싱 의심 (키워드: ${risk.matchedKeywords}) → 경고만 표시")
                        speak(
                            "주의하세요! 방금 상대방의 말에서 보이스피싱이 의심되는 표현이 감지되었습니다. " +
                                "통화를 계속하시더라도 계좌번호, 비밀번호 등 개인정보는 알려주지 마세요."
                        )
                        _interventionEvent.emit(InterventionEvent.RemotePhishingDetected(risk))
                        launch(kotlinx.coroutines.Dispatchers.IO) {
                            monitoringClient?.sendEvent("intervention", mapOf(
                                "level" to "SUSPICIOUS",
                                "keywords" to risk.matchedKeywords,
                                "action" to "WARNED"
                            ))
                        }
                        appendLocalLog("intervention", mapOf(
                            "level" to "SUSPICIOUS",
                            "keywords" to risk.matchedKeywords,
                            "action" to "WARNED"
                        ))
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
        resetForNewCall()
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

        // 모니터링 클라이언트 — 포트 8081, 시그널링과 별개 연결
        val monitorUrl = url.replace(":8080", ":8081")
        monitoringClient = MonitoringClient(monitorUrl, roomId).also {
            it.connect()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(500) // 연결 완료 대기
                it.sendEvent("call_started", mapOf("room" to roomId))
            }
            appendLocalLog("call_started", mapOf("room" to roomId))
        }

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

            is SignalingEvent.RoomError -> {
                // 방 입장 자체가 거부된 경우 (아직 통화가 시작되지 않았으므로 hangUpCall의
                // call_ended 로깅/저장 절차는 건너뛰고 연결만 정리한다)
                Log.e(TAG, "방 입장 실패: ${event.message}")
                signalingClient?.disconnect()
                signalingClient = null
                monitoringClient?.disconnect()
                monitoringClient = null
                _callState.value = CallState.IDLE
                _serviceMessage.tryEmit(event.message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            is SignalingEvent.TypedSpeechReceived -> {
                // 상대(공격자 역) 기기가 음성 대신 텍스트로 보낸 발화.
                // 1) 실제 원격 STT 결과와 동일하게 감지 파이프라인에 주입하고
                // 2) TTS로 그대로 읽어줘서 참여자에게는 실제로 상대가 말하는 것처럼 들리게 한다.
                Log.d(TAG, "상대방 텍스트 발화 수신: ${event.text}")
                remoteSpeechRecognizer.injectPhrase(event.text)
                speak(event.text)
            }
        }
    }

    // ── 통화 제어 ─────────────────────────────────────────────────

    fun muteLocalMic(mute: Boolean) = webRtcManager.setLocalAudioMuted(mute)
    fun muteRemoteAudio(mute: Boolean) = webRtcManager.setRemoteAudioMuted(mute)

    private fun appendLocalLog(type: String, data: Map<String, Any?>) {
        val entry = mapOf("time" to System.currentTimeMillis(), "type" to type, "data" to data)
        localLogEntries.add(entry)
    }

    fun logSurveyAnswer(question: String, answer: Boolean) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            monitoringClient?.sendEvent("survey_answer", mapOf("question" to question, "answer" to answer))
        }
        appendLocalLog("survey_answer", mapOf("question" to question, "answer" to answer))
    }

    fun logUserAction(action: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            monitoringClient?.sendEvent("user_action", mapOf("action" to action))
        }
        appendLocalLog("user_action", mapOf("action" to action))
    }

    fun startVoiceSurvey() = voiceSurveyController.start()
    fun stopVoiceSurvey() = voiceSurveyController.stop()

    /**
     * 설문 결과 보이스피싱이 아니라고 판단되어 통화를 재개할 때 호출.
     *
     * LocalLeakDetector/SensitiveDisclosureDetector 콜백은 AudioProcessingPipeline의
     * Dispatchers.Default 스레드에서 tryClaimBlock()을 호출하므로, 이 함수의 리셋→언뮤트→
     * activeBlockReason 해제 시퀀스 전체를 tryClaimBlock()과 동일한 blockLock으로 감싼다.
     * 마지막 대입 한 줄만 락을 걸면 "디텍터는 이미 리셋됐지만 activeBlockReason은 아직 이전 값"인
     * 틈에 다른 스레드의 tryClaimBlock()이 끼어들 수 있다(새 유출이 조용히 무시되거나, 반대로
     * 리셋 전 keyword 상태로 즉시 재차단되는 두 방향 모두 가능) — 전체를 락으로 묶어야 닫힌다.
     * WebRTC의 setLocalAudioMuted/setRemoteAudioMuted는 I/O 없는 빠른 제어 호출이라 락 안에서
     * 실행해도 스레드 블로킹(ANR) 위험이 없다.
     *
     * localLeakDetector는 의도적으로 리셋하지 않는다: 같은 통화 안에서 이미 "실제 상황"으로
     * 확인된 문구(예: "주민번호")를 또 말했다고 매번 설문을 다시 띄우면 사용자 경험만 나빠지고
     * 안전성에는 도움이 안 된다. alreadyTriggered를 유지하면 같은 트리거는 재발동하지 않되
     * (Set.add가 false 반환) 이번 통화에서 아직 안 나온 새로운 패턴은 그대로 감지된다.
     * 다음 통화에서는 hangUpCall() → audioPipeline.reset()에서 정상적으로 초기화된다.
     */
    fun resumeAfterFalseAlarm() {
        synchronized(blockLock) {
            audioPipeline.scamDetector.reset()
            audioPipeline.sensitiveDisclosureDetector.reset()
            suspiciousWarned = false
            webRtcManager.setLocalAudioMuted(false)
            webRtcManager.setRemoteAudioMuted(false)
            activeBlockReason = null
        }
    }

    fun hangUpCall() {
        if (isHangingUp) return
        isHangingUp = true

        // 통화 종료 시점에 음성 설문이 진행 중이었다면 대기 중인 무응답 타이머를 반드시
        // 취소한다. 이걸 빼먹으면 통화가 끝난 뒤에도 타이머가 살아남아 "응답이 없으시네요"
        // TTS가 뒤늦게 재생되는 문제가 생긴다.
        voiceSurveyController.stop()

        signalingClient?.sendCallEnd()
        signalingClient?.disconnect()
        signalingClient = null

        val endReason = activeBlockReason?.name ?: "user_ended"
        val callEndedPayload = mapOf("reason" to endReason)

        // call_ended를 먼저 동기 전송해 OkHttp send 큐에 넣는다.
        monitoringClient?.sendEvent("call_ended", callEndedPayload)
        appendLocalLog("call_ended", callEndedPayload)

        try {
            val arr = org.json.JSONArray(localLogEntries.map { org.json.JSONObject(it) })
            localLogFile.writeText(arr.toString(2))
            Log.d(TAG, "로컬 로그 저장: ${localLogFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "로컬 로그 저장 실패: ${e.message}")
        }

        // OkHttp WebSocket.close()는 큐에 남은 메시지(방금 보낸 call_ended)를 모두
        // 전송한 뒤 close 프레임을 보내므로, 여기서 동기 disconnect해도 유실되지 않는다.
        // (stopSelf()가 lifecycleScope를 취소하므로 지연 코루틴에 맡기면 안 됨)
        monitoringClient?.disconnect()
        monitoringClient = null

        webRtcManager.stopCall()
        audioPipeline.reset()
        activeBlockReason = null
        _callState.value = CallState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "통화 종료")
    }

    // ── 테스트용 루프백 ──────────────────────────────────────────

    fun startLoopbackCall(monitorServerUrl: String? = null) {
        resetForNewCall()
        monitorServerUrl?.let { url ->
            monitoringClient?.disconnect()
            monitoringClient = MonitoringClient(url, "loopback").also { it.connect() }
        }
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
        tts = null
        ttsDoneCallbacks.clear()
        // 시스템에 의한 강제 종료 시 안전망으로 hangUpCall 호출.
        // isHangingUp 가드로 정상 종료 경로에서는 무시됨.
        hangUpCall()
        // STT 코루틴·네이티브 모델 자원 해제 (서비스가 실제로 파괴될 때만)
        if (::audioPipeline.isInitialized) audioPipeline.release()
        super.onDestroy()
    }
}
