package com.example.callguard.experiment.domain.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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
import com.example.callguard.data.monitoring.MonitoringClient
import com.example.callguard.data.signaling.SignalingEvent
import com.example.callguard.data.signaling.WsSignalingClient
import com.example.callguard.data.webrtc.WebRtcManager
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.pipeline.AudioProcessingPipeline
import com.example.callguard.domain.pipeline.LocalLeakDetector
import com.example.callguard.domain.pipeline.MockInterventionEngine
import com.example.callguard.domain.pipeline.MockScamDetector
import com.example.callguard.domain.pipeline.MockSpeechRecognizer
import com.example.callguard.domain.pipeline.SensitiveDisclosureDetector
import com.example.callguard.domain.pipeline.VoiceSurveyController
import com.example.callguard.experiment.ExperimentMainActivity
import com.example.callguard.experiment.R
import com.example.callguard.experiment.domain.pipeline.LocalDigitLeakDetector
import com.example.callguard.experiment.domain.pipeline.TextSurveyController
import com.example.callguard.experiment.domain.scenario.NoticeChannel
import com.example.callguard.experiment.domain.scenario.NoticeTiming
import com.example.callguard.experiment.domain.scenario.ScenarioCatalog
import com.example.callguard.experiment.domain.scenario.ScenarioConfig
import com.example.callguard.experiment.domain.scenario.ScenarioController
import com.example.callguard.experiment.domain.scenario.SurveyType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 실험용 통화 서비스 — 원본 CallService에서 검증된 컴포넌트(WebRTC, STT, TTS, 뮤트,
 * 음성 설문)를 그대로 조립하되, 하드코딩된 자동 개입 경로 대신 [ScenarioController]
 * 시나리오 엔진에 연결한다 (설계서 §4).
 *
 * 개입 발동 경로 (둘 다 동일한 fire() 공유, §4.3):
 *  1) 자동 탐지: LocalLeakDetector / SensitiveDisclosureDetector / MockScamDetector(SCAM)
 *  2) 수동 트리거: 온디바이스 연구자 패널 또는 원격 콘솔(admin-dashboard)의 manual_command
 */
class ExperimentCallService : LifecycleService() {
    private val TAG = "ExperimentCallService"
    private val NOTIFICATION_ID = 102
    private val CHANNEL_ID = "ExperimentCallServiceChannel"

    // 원격 위험도가 이 값 이상이면 무장된 시나리오를 자동 발동한다(키워드 2개≈0.65).
    private val AUTO_FIRE_PROBABILITY = 0.6f

    private val binder = ExperimentCallServiceBinder()

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
    lateinit var textSurveyController: TextSurveyController
        private set
    lateinit var scenarioController: ScenarioController
        private set

    // 맥락 없는 연속 숫자(피해자 발화) 유출 탐지기 (§ 사용자 요청) — 아래 initComponents에서 배선
    private val localDigitLeakDetector = LocalDigitLeakDetector(minDigits = 3) { digits ->
        onLocalDigitLeak(digits)
    }

    private var signalingClient: WsSignalingClient? = null
    private var monitoringClient: MonitoringClient? = null

    // 모니터링 서버가 응답하지 않는 경우를 대비한 기기 내 로컬 백업
    private val localLogFile: File by lazy {
        File(getExternalFilesDir(null) ?: filesDir, "experiment_log_${System.currentTimeMillis()}.json")
    }

    private val lastPartialLogTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var isHangingUp = false

    // ── 뮤트 상태 분리 ────────────────────────────────────────────
    // 참가자의 음소거 버튼과 시나리오 엔진의 차단은 서로 독립이어야 한다.
    // 하나의 플래그로 합치면 참가자가 음소거 버튼을 두 번 누르는 것만으로
    // 시나리오 차단(S5~S9)이 해제되어 실험이 오염된다.
    @Volatile private var participantMuted = false
    @Volatile private var scenarioLocalBlocked = false
    @Volatile private var scenarioRemoteBlocked = false

    private fun applyLocalMuteState() {
        webRtcManager.setLocalAudioMuted(participantMuted || scenarioLocalBlocked)
    }

    /** 현재 로컬 음성이 실제로 상대에게 전달되고 있는지 (탐지 이벤트의 '제공 시도' 판정용) */
    private fun isMicTransmitting(): Boolean = !(participantMuted || scenarioLocalBlocked)

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsFailed = false
    private val pendingTtsQueue = mutableListOf<Triple<String, Int, String>>()
    private val ttsDoneCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    private val localLogEntries = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any?>>())

    // ── 상태 Flow ────────────────────────────────────────────────
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _serviceMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val serviceMessage: SharedFlow<String> = _serviceMessage

    /** (설문종류 "VOICE"/"TEXT", 질문 인덱스, 답변) */
    private val _surveyAnswerEvent = MutableSharedFlow<Triple<String, Int, Boolean>>(extraBufferCapacity = 8)
    val surveyAnswerEvent: SharedFlow<Triple<String, Int, Boolean>> = _surveyAnswerEvent

    /** 텍스트 안내 배너 상태 — null이면 표시 안 함 (§4.5 NoticeBanner) */
    data class BannerState(val message: String, val requireAck: Boolean)
    private val _noticeBanner = MutableStateFlow<BannerState?>(null)
    val noticeBanner: StateFlow<BannerState?> = _noticeBanner

    /**
     * 시나리오가 참가자 마이크를 차단 중인지 — 참가자 화면의 '차단됨' 표시용.
     * 참가자 음소거 버튼(participantMuted)과는 별개다. 마이크가 차단되는 시나리오
     * (S4~S10)에서 참가자가 자신의 목소리가 상대에게 전달되지 않음을 인지할 수 있게 한다.
     */
    private val _scenarioMicBlocked = MutableStateFlow(false)
    val scenarioMicBlocked: StateFlow<Boolean> = _scenarioMicBlocked

    enum class CallState { IDLE, CONNECTING, RINGING, CONNECTED, DISCONNECTED }

    inner class ExperimentCallServiceBinder : Binder() {
        fun getService(): ExperimentCallService = this@ExperimentCallService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initComponents()
        initTts()
        Log.d(TAG, "ExperimentCallService 생성 완료")
    }

    // ── TTS (원본 CallService와 동일한 큐/콜백 패턴) ──────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS 한국어 데이터 없음 (result=$result) — 기기에 한국어 TTS 음성 설치 필요")
                }
                // 통화 중(MODE_IN_COMMUNICATION)에는 기본 미디어 스트림 TTS가 이어피스로 밀려
                // 거의 안 들린다. 통화 오디오와 같은 '음성 통신' 경로로 라우팅해 스피커폰으로
                // 크게 들리도록 오디오 속성을 지정한다 (공격자 대사·AI 안내 모두 이 speak를 탄다).
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id -> ttsDoneCallbacks.remove(id)?.invoke() }
                    }
                    @Deprecated("deprecated in API level 21")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { id -> ttsDoneCallbacks.remove(id)?.invoke() }
                    }
                })
                isTtsReady = true
                Log.d(TAG, "TTS 초기화 완료 — 대기 중이던 ${pendingTtsQueue.size}건 재생")
                pendingTtsQueue.forEach { (text, mode, uttId) -> tts?.speak(text, mode, null, uttId) }
                pendingTtsQueue.clear()
            } else {
                // 초기화 실패 시 대기 큐의 onDone 콜백을 모두 소비한다 — S6처럼
                // "TTS 완료 후 차단"하는 시나리오가 영영 차단되지 않는 상황 방지.
                isTtsFailed = true
                Log.e(TAG, "TTS 초기화 실패 (status=$status) — 음성 안내 없이 진행")
                pendingTtsQueue.forEach { (_, _, uttId) -> ttsDoneCallbacks.remove(uttId)?.invoke() }
                pendingTtsQueue.clear()
            }
        }
    }

    /** 원자 동작 1: 음성 안내 (§4.2). TTS 사용 불가 시에도 onDone은 반드시 호출된다. */
    fun speak(text: String, flush: Boolean = false, onDone: (() -> Unit)? = null) {
        if (isTtsFailed || text.isBlank()) {
            onDone?.invoke()
            return
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "tts_${System.nanoTime()}"
        onDone?.let { ttsDoneCallbacks[utteranceId] = it }
        if (isTtsReady) {
            tts?.speak(text, mode, null, utteranceId)
        } else {
            Log.w(TAG, "TTS 아직 준비 안 됨 — 큐에 저장: $text")
            pendingTtsQueue.add(Triple(text, mode, utteranceId))
        }
        // 파일럿 확인 항목 "TTS 정상 출력" — 큐에 대기시킨 발화도 로그에 남긴다
        logEvent("tts_played", mapOf("text" to text, "queued" to !isTtsReady))
    }

    /**
     * 통화 연결 시 오디오를 '통신 모드 + 스피커폰'으로 전환한다.
     * 실험에서는 참가자가 폰을 귀에 붙이지 않고 관찰자와 함께 듣는 경우가 많으므로
     * 스피커폰을 켜 공격자 대사(typed_speech→TTS)와 AI 개입 음성이 크게 들리게 한다.
     */
    private fun configureAudioForCall() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            Log.d(TAG, "통화 오디오 설정: MODE_IN_COMMUNICATION + 스피커폰 ON")
        } catch (e: Exception) {
            Log.w(TAG, "통화 오디오 설정 실패: ${e.message}")
        }
    }

    /** 통화 종료 시 오디오 모드를 원상 복구한다. */
    private fun restoreAudioMode() {
        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    /** 참가자 화면의 스피커 버튼 → 스피커폰 on/off 토글 */
    fun toggleSpeakerphone(): Boolean {
        return try {
            val next = !audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = next
            logEvent("user_action", mapOf("action" to if (next) "스피커폰 ON" else "스피커폰 OFF"))
            next
        } catch (e: Exception) {
            Log.w(TAG, "스피커폰 토글 실패: ${e.message}"); false
        }
    }

    /** 원자 동작 2: SOS 진동만 (§4.2 — 원본의 진동+TTS 결합에서 분리) */
    fun vibrateSOS() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 600, 250, 600, 250, 600)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        logEvent("vibration_triggered", mapOf("pattern" to "SOS_3x600ms"))
    }

    /** 원자 동작 3: 텍스트 안내 배너 (§4.5) */
    fun showTextBanner(message: String, requireAck: Boolean) {
        _noticeBanner.value = BannerState(message, requireAck)
        logEvent("text_banner_shown", mapOf("message" to message))
    }

    fun dismissTextBanner() {
        if (_noticeBanner.value != null) {
            _noticeBanner.value = null
            logEvent("user_action", mapOf("action" to "텍스트 안내 배너 확인"))
        }
    }

    /** 세션 정리용 배너 제거 — 참가자의 '확인' 행동이 아니므로 user_action을 남기지 않는다 */
    private fun clearTextBanner() {
        _noticeBanner.value = null
    }

    /**
     * 피해자 발화에서 맥락 없는 연속 숫자(3자리+)가 감지된 순간의 처리.
     * 개인정보가 실제로 발화된 강한 신호이므로 (1) 유출 기록, (2) 위험도 게이지 급상승,
     * (3) 무장된 시나리오 즉시 발동(차단 시나리오면 즉시 차단)을 한다.
     */
    private fun onLocalDigitLeak(digits: String) {
        logEvent("personal_info_utterance", mapOf(
            "triggerPhrase" to "연속 숫자 발화($digits)",
            "partialText" to digits,
            "micWasActiveAtDetection" to isMicTransmitting()
        ))
        // 위험도 게이지 100%로 급상승 — 대시보드 표시용 (개인정보가 실제로 발화된 순간)
        logEvent("risk_update", mapOf(
            "level" to "SCAM",
            "probability" to 1.0,
            "keywords" to listOf("개인정보 발화(연속 숫자)")
        ))
        Log.w(TAG, "연속 숫자 유출 감지: $digits → 개입 발동")
        scenarioController.onAutoDetectionTriggered("local_digits:$digits")
    }

    // ── 컴포넌트 조립 ─────────────────────────────────────────────

    private fun initComponents() {
        localSpeechRecognizer = MockSpeechRecognizer(this)
        remoteSpeechRecognizer = MockSpeechRecognizer(this)
        scamDetector = MockScamDetector()

        voiceSurveyController = VoiceSurveyController(
            speak = { text, onDone -> speak(text, onDone = onDone) },
            onAnswer = { questionIndex, answer ->
                logSurveyAnswer("VOICE", questionIndex, answer)
            },
            onCompleted = { timedOut ->
                // 실험에서는 무응답이어도 통화를 강제 종료하지 않는다 (§8) —
                // 세션 진행/종료는 연구자(세션 매니저)가 결정하고 여기서는 기록만 한다.
                logEvent("survey_completed", mapOf("surveyType" to "VOICE", "timedOut" to timedOut))
            },
            timeoutExitMessage = "응답이 없어 설문을 마칩니다."
        )

        textSurveyController = TextSurveyController(
            onAnswer = { questionIndex, answer ->
                logSurveyAnswer("TEXT", questionIndex, answer)
            },
            onCompleted = {
                logEvent("survey_completed", mapOf("surveyType" to "TEXT", "timedOut" to false))
            }
        )

        // 차단 중에도 로컬 STT 피드는 유지되므로 음성 설문 응답을 같은 스트림으로 받는다.
        // 설문 중이 아닐 때는 "맥락 없는 연속 숫자" 유출을 검사한다(연구자가 채팅으로 숫자를
        // 주입하면 여기 Final 스트림으로 들어온다).
        lifecycleScope.launch {
            localSpeechRecognizer.transcript.collect { text ->
                if (voiceSurveyController.isActive.value) {
                    voiceSurveyController.onLocalTranscript(text)
                } else {
                    localDigitLeakDetector.analyze(text)
                }
            }
        }
        // Partial STT로도 검사해 실제 음성 발화 시 더 빠르게(말하는 도중) 감지한다
        lifecycleScope.launch {
            localSpeechRecognizer.partialTranscript.collect { text ->
                if (!voiceSurveyController.isActive.value) localDigitLeakDetector.analyze(text)
            }
        }

        // ── 자동 탐지 → 시나리오 엔진 연결 ──
        // 원본처럼 탐지기가 직접 차단하지 않고, 무장된 시나리오의 fire()를 부른다.
        // micWasActiveAtDetection: 감지 순간 마이크가 실제 송신 중이었는지를 기록한다.
        // 차단 중 발화(상대에게 전달 안 됨)와 송신 중 발화(제공 시도)를 로그에서 구분해야
        // 파일럿 확인 항목 "개인정보 발화 여부"를 올바르게 해석할 수 있다.
        val localLeakDetector = LocalLeakDetector { triggerPhrase, partialText ->
            logEvent("personal_info_utterance", mapOf(
                "triggerPhrase" to triggerPhrase,
                "partialText" to partialText,
                "micWasActiveAtDetection" to isMicTransmitting()
            ))
            scenarioController.onAutoDetectionTriggered("local_leak:$triggerPhrase")
        }
        val sensitiveDisclosureDetector = SensitiveDisclosureDetector { label, partialText ->
            logEvent("personal_info_utterance", mapOf(
                "triggerPhrase" to "$label(발화 감지)",
                "partialText" to partialText,
                "micWasActiveAtDetection" to isMicTransmitting()
            ))
            scenarioController.onAutoDetectionTriggered("disclosure:$label")
        }
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

        scenarioController = ScenarioController(
            setLocalMuted = { muted ->
                scenarioLocalBlocked = muted
                // 참가자 차단 인지 표시. 단, S5(무통보 차단)처럼 안내 채널이 전혀 없는
                // '침묵 차단' 시나리오는 표시하지 않는다 — S5의 실험 목적이 '무통보 차단의
                // UX 영향 측정'이므로 차단을 알리면 조건이 무너진다.
                val armed = scenarioController.armedScenario.value
                val silentBlock = armed != null &&
                    armed.noticeChannels.isEmpty() &&
                    armed.noticeTiming == NoticeTiming.NONE
                _scenarioMicBlocked.value = muted && !silentBlock
                applyLocalMuteState()
            },
            setRemoteMuted = { muted ->
                scenarioRemoteBlocked = muted
                webRtcManager.setRemoteAudioMuted(muted)
            },
            speak = { text, flush, onDone -> speak(text, flush, onDone) },
            vibrateSOS = { vibrateSOS() },
            showTextBanner = { message, requireAck -> showTextBanner(message, requireAck) },
            startVoiceSurvey = { voiceSurveyController.start() },
            startTextSurvey = { textSurveyController.start() },
            stopSurveys = {
                voiceSurveyController.stop()
                textSurveyController.stop()
            },
            onLog = { event, data -> logEvent(event, data) },
            // S10(통화 강제 종료) — TTS 콜백 스레드에서 불리므로 메인으로 넘긴다
            endCall = {
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) { hangUpCall() }
            },
            hideTextBanner = { clearTextBanner() }
        )

        // 원격(공격자) 발화 위험도가 일정 수준(키워드 2개, prob≥0.6) 이상이면 무장된 시나리오를
        // 자동 발동한다. base처럼 SCAM(다수 키워드) 확정까지 기다리면 짧은 대사에서는 발동이
        // 안 걸려 "개입이 안 된다"고 느껴지므로, 실험에서는 문턱을 낮춰 반응성을 확보한다.
        // (무장된 시나리오가 없으면 무시됨. 정밀한 타이밍은 연구자의 "지금 개입 실행"으로.)
        lifecycleScope.launch {
            audioPipeline.riskScoreFlow.collect { risk ->
                if (risk.probability >= AUTO_FIRE_PROBABILITY) {
                    scenarioController.onAutoDetectionTriggered(
                        "remote_risk:${risk.matchedKeywords.joinToString(",")}"
                    )
                }
            }
        }

        // ── 모니터링 이벤트 수집 (원본과 동일 스키마) ──
        lifecycleScope.launch {
            audioPipeline.transcriptFlow.collect { (speaker, text) ->
                logEvent("stt_final", mapOf("speaker" to speaker, "text" to text))
            }
        }
        lifecycleScope.launch {
            audioPipeline.partialTranscriptFlow.collect { (speaker, text) ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    monitoringClient?.sendThrottled(
                        "partial_$speaker", "stt_partial",
                        mapOf("speaker" to speaker, "text" to text), 500
                    )
                }
                val now = System.currentTimeMillis()
                if (now - (lastPartialLogTime[speaker] ?: 0L) >= 500) {
                    lastPartialLogTime[speaker] = now
                    appendLocalLog("stt_partial", mapOf("speaker" to speaker, "text" to text))
                }
            }
        }
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
    }

    // ── 시나리오/세션 제어 (온디바이스 패널·원격 콘솔 공통 명령 집합, §5.2) ──

    fun armScenario(scenarioId: String, overrides: JSONObject? = null): Boolean {
        val base = ScenarioCatalog.findById(scenarioId) ?: run {
            Log.w(TAG, "알 수 없는 시나리오: $scenarioId")
            return false
        }
        scenarioController.arm(applyOverrides(base, overrides))
        return true
    }

    /**
     * 파일럿 중 실행 시점 오버라이드 (§6 권장) — 예: S6 안내 채널을 음성→텍스트로 전환.
     * params 예시: {"noticeChannels":["TEXT_BANNER"], "noticeTiming":"BEFORE_BLOCK",
     *              "noticeMessage":"...", "surveyType":"TEXT"}
     */
    private fun applyOverrides(base: ScenarioConfig, overrides: JSONObject?): ScenarioConfig {
        if (overrides == null || overrides.length() == 0) return base
        return try {
            var config = base
            overrides.optJSONArray("noticeChannels")?.let { arr ->
                val channels = mutableSetOf<NoticeChannel>()
                for (i in 0 until arr.length()) {
                    runCatching { channels.add(NoticeChannel.valueOf(arr.getString(i))) }
                }
                config = config.copy(noticeChannels = channels)
            }
            overrides.optString("noticeTiming").takeIf { it.isNotBlank() }?.let {
                runCatching { config = config.copy(noticeTiming = NoticeTiming.valueOf(it)) }
            }
            overrides.optString("noticeMessage").takeIf { it.isNotBlank() }?.let {
                config = config.copy(noticeMessage = it)
            }
            overrides.optString("surveyType").takeIf { it.isNotBlank() }?.let {
                runCatching { config = config.copy(surveyType = SurveyType.valueOf(it)) }
            }
            config
        } catch (e: Exception) {
            Log.w(TAG, "시나리오 오버라이드 파싱 실패 — 기본값 사용: ${e.message}")
            base
        }
    }

    fun triggerNow() = scenarioController.onManualTrigger()

    /**
     * 다음 시나리오를 위해 상태를 초기화한다 — 통화는 유지한 채 (연구자 "초기화 버튼").
     *
     * 실험은 한 통화 안에서 10개 시나리오를 연속 진행하므로, 앞 시나리오에서 쌓인
     * 위험도(누적 키워드)를 그대로 두면 다음 시나리오가 시작하자마자 높은 민감도로
     * 자동 발동돼 매끄러운 진행이 안 된다. 이 초기화는 다음을 모두 되돌린다:
     *  - 무장 시나리오 해제 + 발동 플래그 리셋 + 시나리오 뮤트 해제 + 설문 중단 + 배너 제거(disarm)
     *  - 참가자 음소거(participantMuted) 해제 → 마이크 정상 송신 상태로 복귀
     *  - 진행 중이던 TTS(안내/설문 음성) 즉시 중단 + 대기 큐/완료콜백 정리
     *  - 누적 위험도(scam 키워드) + 로컬 누출/요구직후숫자/연속숫자 탐지기 + STT 인식 버퍼
     *  - 대시보드 위험도 게이지·키워드 배지 표시 초기화(SAFE 강제 송신 + session_reset)
     *
     * 통화 연결(WebRTC/시그널링/모니터링)만 유지하고, 그 외 상태는 통화 시작 직후와
     * 동일한 초기 상태로 되돌린다.
     */
    fun resetSession() {
        // 1) 시나리오 상태(무장/발동/시나리오 뮤트/설문/배너) 초기화
        scenarioController.disarm()
        _noticeBanner.value = null

        // 2) 참가자 음소거까지 해제 → 다음 시나리오에서 마이크가 확실히 송신되게 한다
        participantMuted = false
        applyLocalMuteState()

        // 3) 진행 중이던 TTS 중단 (앞 시나리오 음성이 다음으로 새지 않게)
        tts?.stop()
        pendingTtsQueue.clear()
        ttsDoneCallbacks.clear()

        // 4) 누적 위험도·모든 탐지기·STT 인식 버퍼까지 완전 초기화
        //    (audioPipeline.reset()이 인식기 + scam/leak/disclosure 탐지기를 한 번에 리셋)
        audioPipeline.reset()
        localDigitLeakDetector.reset()

        // 5) 대시보드 게이지를 즉시 SAFE로(쓰로틀 우회 직접 송신) + 배지·무장 표시 초기화 신호
        logEvent("risk_update", mapOf(
            "level" to "SAFE", "probability" to 0.05, "keywords" to emptyList<String>()
        ))
        logEvent("session_reset", emptyMap())
        Log.d(TAG, "세션 초기화 완료 — 통화만 유지, 나머지 상태는 초기 상태로 복귀")
    }

    fun forceSurveyAnswer(index: Int, value: Boolean) {
        when {
            voiceSurveyController.isActive.value -> voiceSurveyController.forceAnswer(index, value)
            textSurveyController.isActive.value -> textSurveyController.forceAnswer(index, value)
            else -> Log.w(TAG, "forceSurveyAnswer 무시 — 진행 중인 설문 없음")
        }
    }

    fun answerTextSurvey(value: Boolean) = textSurveyController.answer(value)

    fun logObservationNote(text: String) = logEvent("observation_note", mapOf("text" to text))

    /**
     * 온디바이스 연구자 패널의 수동 조작 기록.
     * (원격 콘솔의 조작은 서버가 manual_command 수신 시점에 origin=remote로 이미 기록)
     */
    fun logManualOverride(action: String, params: Map<String, Any?> = emptyMap()) {
        logEvent("manual_override", mapOf("action" to action, "params" to params))
    }

    // ── 원격 콘솔 수동 명령 처리 (§5.3) ──────────────────────────

    private fun handleManualCommand(action: String, params: JSONObject) {
        // OkHttp WebSocket 스레드에서 호출되므로 메인 스레드로 넘긴다
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            Log.d(TAG, "원격 수동 명령 실행: $action")
            when (action) {
                "arm_scenario" -> armScenario(params.optString("scenarioId"), params.optJSONObject("overrides"))
                "trigger_now" -> triggerNow()
                "reset_session" -> resetSession()
                "inject_remote" -> params.optString("phrase").takeIf { it.isNotBlank() }?.let { phrase ->
                    simulateRemoteSpeech(phrase)
                    // 참가자에게 실제 상대 발화처럼 들리도록 재생할지 여부 (기본 false — 탐지 주입만).
                    // 상대 음성 차단 중에는 typed_speech와 동일하게 재생을 생략한다.
                    if (params.optBoolean("speak", false) && !scenarioRemoteBlocked) speak(phrase)
                }
                "inject_local" -> params.optString("phrase").takeIf { it.isNotBlank() }?.let {
                    simulateLocalSpeech(it)
                }
                "force_answer" -> forceSurveyAnswer(params.optInt("index", -1), params.optBoolean("value"))
                "end_call" -> hangUpCall()
                else -> Log.w(TAG, "알 수 없는 수동 명령: $action")
            }
        }
    }

    // ── 통화 시작 (실제 P2P — 원본 CallService와 동일 흐름) ─────

    fun joinRoom(serverAddress: String, roomId: String) {
        resetForNewCall()
        _callState.value = CallState.CONNECTING
        val url = when {
            serverAddress.startsWith("ws://") || serverAddress.startsWith("wss://") -> {
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

        // 모니터링 클라이언트 — 포트 8081, device_join으로 등록해 manual_command 수신
        val monitorUrl = url.replace(":8080", ":8081")
        monitoringClient = MonitoringClient(monitorUrl, roomId, onCommand = { action, params ->
            handleManualCommand(action, params)
        }).also {
            it.connect()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(500)
                it.sendEvent("call_started", mapOf("room" to roomId))
            }
            appendLocalLog("call_started", mapOf("room" to roomId))
        }

        Log.d(TAG, "시그널링 서버 연결 시도: $url / 방: $roomId")
    }

    private fun resetForNewCall() {
        isHangingUp = false
    }

    private fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.StartCall -> {
                Log.d(TAG, "StartCall 수신 → Offer 생성")
                _callState.value = CallState.RINGING
                webRtcManager.startLocalAudioCapture()
                createPeerConnection()
                webRtcManager.createOffer(
                    onSuccess = { sdp ->
                        signalingClient?.sendOffer(sdp.description)
                        Log.d(TAG, "Offer 전송 완료")
                    },
                    onFailure = { Log.e(TAG, "Offer 생성 실패: $it") }
                )
            }

            is SignalingEvent.OfferReceived -> {
                Log.d(TAG, "Offer 수신 → Answer 생성")
                _callState.value = CallState.RINGING
                webRtcManager.startLocalAudioCapture()
                createPeerConnection()
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
                // 공격자(client.html)가 텍스트로 보낸 대본 — STT 신뢰도 문제 없는 경로 (§5.1)
                // 탐지 파이프라인 주입은 항상 하되, 시나리오가 상대 음성을 차단 중이면
                // TTS 재생은 생략한다 — 재생하면 "상대 음성 차단"(S3·S4·S8·S9)이 무력화된다.
                Log.d(TAG, "상대방 텍스트 발화 수신: ${event.text}")
                remoteSpeechRecognizer.injectPhrase(event.text)
                if (!scenarioRemoteBlocked) {
                    speak(event.text)
                } else {
                    logEvent("remote_speech_suppressed", mapOf("text" to event.text))
                }
            }
        }
    }

    private fun createPeerConnection() {
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
                configureAudioForCall()
            },
            onDisconnected = {
                Log.d(TAG, "P2P 연결 끊김")
                hangUpCall()
            }
        )
    }

    // ── 테스트용 루프백 (노트북 없이 단독 벤치 테스트) ────────────

    fun startLoopbackCall(monitorServerUrl: String? = null) {
        resetForNewCall()
        monitorServerUrl?.let { url ->
            monitoringClient?.disconnect()
            monitoringClient = MonitoringClient(url, "loopback", onCommand = { action, params ->
                handleManualCommand(action, params)
            }).also { it.connect() }
        }
        _callState.value = CallState.RINGING
        webRtcManager.startLocalAudioCapture()
        webRtcManager.startLoopbackCall(
            onConnected = {
                _callState.value = CallState.CONNECTED
                configureAudioForCall()
            },
            onDisconnected = { hangUpCall() }
        )
    }

    // ── 통화 제어 / 로깅 ─────────────────────────────────────────

    /**
     * 참가자의 음소거 버튼 경로 — 시나리오 차단(scenarioLocalBlocked)과 독립적으로 동작한다.
     * 참가자가 음소거를 해제해도 시나리오 차단이 걸려 있으면 마이크는 계속 차단 상태다.
     * 파일럿 관찰 항목 "행동 변화" 분석을 위해 버튼 조작을 user_action으로 기록한다.
     */
    fun muteLocalMic(mute: Boolean) {
        participantMuted = mute
        applyLocalMuteState()
        logEvent("user_action", mapOf("action" to if (mute) "참가자 음소거 버튼 ON" else "참가자 음소거 버튼 OFF"))
    }

    fun muteRemoteAudio(mute: Boolean) = webRtcManager.setRemoteAudioMuted(mute)

    private fun appendLocalLog(type: String, data: Map<String, Any?>) {
        val entry = mapOf("time" to System.currentTimeMillis(), "type" to type, "data" to data)
        localLogEntries.add(entry)
    }

    /** 로컬 백업 + 모니터링 서버 전송을 한 번에 처리하는 공통 로깅 경로 */
    private fun logEvent(type: String, data: Map<String, Any?>) {
        appendLocalLog(type, data)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            monitoringClient?.sendEvent(type, data)
        }
    }

    private fun logSurveyAnswer(surveyType: String, questionIndex: Int, answer: Boolean) {
        val question = textSurveyController.questions.getOrNull(questionIndex) ?: "질문 ${questionIndex + 1}"
        logEvent("survey_answer", mapOf(
            "question" to question,
            "answer" to answer,
            "surveyType" to surveyType
        ))
        lifecycleScope.launch { _surveyAnswerEvent.emit(Triple(surveyType, questionIndex, answer)) }
    }

    fun logUserAction(action: String) = logEvent("user_action", mapOf("action" to action))

    fun hangUpCall() {
        if (isHangingUp) return
        isHangingUp = true

        voiceSurveyController.stop()
        textSurveyController.stop()
        _noticeBanner.value = null

        // 진행 중이던 안내/설문 TTS를 즉시 중단하고 큐/콜백을 비운다 — 통화가 끝난 뒤
        // 이전 시나리오 음성이 이어 재생되거나, 곧바로 재연결한 다음 통화로 새는 것을 막는다.
        tts?.stop()
        pendingTtsQueue.clear()
        ttsDoneCallbacks.clear()

        signalingClient?.sendCallEnd()
        signalingClient?.disconnect()
        signalingClient = null

        val callEndedPayload = mapOf(
            "reason" to "session_ended",
            "scenarioId" to (scenarioController.armedScenario.value?.id)
        )
        monitoringClient?.sendEvent("call_ended", callEndedPayload)
        appendLocalLog("call_ended", callEndedPayload)

        try {
            val arr = org.json.JSONArray(localLogEntries.map { org.json.JSONObject(it) })
            localLogFile.writeText(arr.toString(2))
            Log.d(TAG, "로컬 로그 저장: ${localLogFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "로컬 로그 저장 실패: ${e.message}")
        }

        monitoringClient?.disconnect()
        monitoringClient = null

        restoreAudioMode()
        webRtcManager.stopCall()
        audioPipeline.reset()
        localDigitLeakDetector.reset()
        scenarioController.disarm()
        participantMuted = false
        scenarioLocalBlocked = false
        scenarioRemoteBlocked = false
        _scenarioMicBlocked.value = false
        _callState.value = CallState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "통화 종료")
    }

    // ── 시뮬레이션 (발화 강제 주입 — STT 우회, §5.2) ──────────────

    fun simulateRemoteSpeech(phrase: String) {
        remoteSpeechRecognizer.injectPhrase(phrase)
    }

    fun simulateLocalSpeech(phrase: String) {
        localSpeechRecognizer.injectPhrase(phrase)
    }

    // ── 서비스 수명주기 / 알림 ───────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

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
        val intent = Intent(this, ExperimentMainActivity::class.java)
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
        hangUpCall()
        if (::audioPipeline.isInitialized) audioPipeline.release()
        super.onDestroy()
    }
}
