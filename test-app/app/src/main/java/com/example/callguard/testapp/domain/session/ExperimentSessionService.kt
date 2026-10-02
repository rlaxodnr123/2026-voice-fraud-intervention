package com.example.callguard.testapp.domain.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.callguard.testapp.TestAppActivity
import com.example.callguard.testapp.domain.audio.AlertTone
import com.example.callguard.testapp.domain.audio.MicCapture
import com.example.callguard.testapp.domain.intervention.InterventionCatalog
import com.example.callguard.testapp.domain.intervention.InterventionConfig
import com.example.callguard.testapp.domain.intervention.InterventionController
import com.example.callguard.testapp.domain.intervention.InterventionId
import com.example.callguard.testapp.domain.log.ExperimentLogger
import com.example.callguard.testapp.domain.log.SessionMeta
import com.example.callguard.testapp.domain.script.AttackerScript
import com.example.callguard.testapp.domain.script.AttackerScriptCatalog
import com.example.callguard.testapp.domain.script.AttackerScriptPlayer
import com.example.callguard.testapp.domain.script.AttackerVoice
import com.example.callguard.testapp.domain.script.CallerRelationship
import com.example.callguard.testapp.domain.script.PlaybackMode
import com.example.callguard.testapp.domain.script.ScriptLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.callguard.testapp.domain.stt.LeakDetector
import com.example.callguard.testapp.domain.stt.ParticipantStt
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실험 세션 전체를 조립하는 서비스.
 *
 * 서버도 WebRTC도 쓰지 않는다. 상대방 음성은 이 기기에서 재생되고, 참가자 음성은
 * 이 기기에서만 인식·녹음된다 — 앱에 INTERNET 권한 자체가 없어 참가자 음성이
 * 기기 밖으로 나갈 수 없다는 것이 매니페스트 수준에서 보장된다.
 *
 * 포그라운드 승격은 **통화를 시작할 때만** 한다. 서비스 생성 시점에 승격하면
 * 마이크 권한을 아직 받지 못한 첫 실행에서 마이크 타입 포그라운드 서비스 제한에 걸린다.
 */
class ExperimentSessionService : LifecycleService(), TextToSpeech.OnInitListener {

    private val TAG = "SessionService"
    private val CHANNEL_ID = "CallGuardTestAppSession"
    private val NOTIFICATION_ID = 301

    enum class CallState { IDLE, IN_CALL, ENDED }

    /** TTS 화자 역할 — 상대방과 경고 음성을 청각적으로 구분한다 */
    enum class VoiceRole { ATTACKER, GUARD }

    inner class LocalBinder : Binder() {
        fun getService(): ExperimentSessionService = this@ExperimentSessionService
    }

    private val binder = LocalBinder()

    // ── 외부 관찰 상태 ────────────────────────────────────────────
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _callDuration = MutableStateFlow("00:00")
    val callDuration: StateFlow<String> = _callDuration

    private val _micBlocked = MutableStateFlow(false)
    val micBlocked: StateFlow<Boolean> = _micBlocked

    /** 개입이 상대방 음성을 끊은 상태인가 (스피커 차단) */
    private val _remoteAudioBlocked = MutableStateFlow(false)
    val remoteAudioBlocked: StateFlow<Boolean> = _remoteAudioBlocked

    private val _participantMuted = MutableStateFlow(false)
    val participantMuted: StateFlow<Boolean> = _participantMuted

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn

    /**
     * 개입 화면 — 개입이 발동한 순간부터 세션이 끝날 때까지 **하나의 화면**으로 유지된다.
     * 안내 중과 안내 후에 다른 화면을 띄우면 참가자가 화면 전환 자체에 반응하게 된다.
     */
    data class InterventionScreen(
        val message: String,
        /** 통화 이어가기/종료 버튼을 보여 주는가 (개입 1) */
        val offerChoice: Boolean,
        /** 안내 음성이 끝났는가. 이때부터 화면의 버튼이 눌린다. */
        val announcementDone: Boolean
    )

    private val _interventionScreen = MutableStateFlow<InterventionScreen?>(null)
    val interventionScreen: StateFlow<InterventionScreen?> = _interventionScreen

    /** 지인 조건이면 저장된 이름, 모르는 사람이면 빈 문자열 */
    private val _callerName = MutableStateFlow("")
    val callerName: StateFlow<String> = _callerName

    private val _callerNumber = MutableStateFlow("")
    val callerNumber: StateFlow<String> = _callerNumber

    /** 연구자 패널 전용 — 현재 대사 위치와 내용 */
    private val _currentLineIndex = MutableStateFlow(-1)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex

    private val _currentLineText = MutableStateFlow("")
    val currentLineText: StateFlow<String> = _currentLineText

    private val _interventionFired = MutableStateFlow(false)
    val interventionFired: StateFlow<Boolean> = _interventionFired

    /** 연구자 패널 전용 — 최근 인식된 참가자 발화 */
    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript

    private val _leakFlag = MutableStateFlow<String?>(null)
    val leakFlag: StateFlow<String?> = _leakFlag

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message: SharedFlow<String> = _message

    @Volatile var activeScript: AttackerScript? = null; private set
    @Volatile var activeIntervention: InterventionConfig? = null; private set
    @Volatile var activeMode: PlaybackMode = PlaybackMode.AUTO_TTS; private set

    /** 이 세션의 상대방 목소리 — 연구자 패널이 현재 자극을 확인하는 데 쓴다 */
    @Volatile var activeVoice: AttackerVoice = AttackerVoice.M30; private set

    val sttReady: StateFlow<Boolean> get() = stt.ready
    val sttStatus: StateFlow<String> get() = stt.status

    // ── 구성 요소 ─────────────────────────────────────────────────
    private lateinit var stt: ParticipantStt
    private lateinit var logger: ExperimentLogger
    private lateinit var scriptPlayer: AttackerScriptPlayer
    private lateinit var intervention: InterventionController
    private var micCapture: MicCapture? = null
    private val leakDetector = LeakDetector()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private data class PendingUtterance(
        val text: String,
        val role: VoiceRole,
        val flush: Boolean,
        val utteranceId: String
    )

    private val pendingUtterances = mutableListOf<PendingUtterance>()

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var durationJob: Job? = null
    private var callStartedAt = 0L
    private var isForeground = false

    /**
     * 스피커로 소리가 나가는 중인 출력 개수.
     *
     * boolean 하나로 두면 상대방 음성과 경고 음성이 겹칠 때 먼저 끝난 쪽이 게이트를 열어,
     * 경고 음성이 마이크로 되들어와 참가자 발화로 기록된다. 경고 문구에는 "보이스피싱"이
     * 들어 있어 거부 표현 오탐까지 만든다. 카운터라야 마지막 출력이 끝날 때만 열린다.
     */
    private val audioOutputs = AtomicInteger(0)
    private val isAudioPlaying: Boolean get() = audioOutputs.get() > 0

    @Volatile private var speechOnsetLogged = false

    val sessionRoot: File
        get() = File(getExternalFilesDir(null), "sessions")

    // ── 수명주기 ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        stt = ParticipantStt(this)
        stt.onFinal = { text -> onTranscript(text, isFinal = true) }
        stt.onPartial = { text -> onTranscript(text, isFinal = false) }
        stt.load()

        logger = ExperimentLogger(sessionRoot)
        tts = TextToSpeech(this, this)

        scriptPlayer = AttackerScriptPlayer(
            context = this,
            scope = lifecycleScope,
            speak = { text, onDone -> speak(text, VoiceRole.ATTACKER, flush = false, onDone = onDone) },
            onSpeakingChanged = { speaking -> updateAudioOutputs(speaking) },
            onLineChanged = { index, line ->
                _currentLineIndex.value = index
                _currentLineText.value = line?.text.orEmpty()
            },
            onInterventionPointReached = { intervention.fireAuto("intervention_point_line_finished") },
            onScriptFinished = { logger.log("script_finished") },
            onLog = { event, data -> logger.log(event, data) }
        )

        intervention = InterventionController(
            setRemoteAudioBlocked = { blocked ->
                // 상태 표시만이 아니라 실제로 재생을 멈춘다 —
                // 상대가 계속 말하는 위에 경고음과 안내가 겹치면 셋 다 못 알아듣는다.
                if (blocked) scriptPlayer.stopPlayback()
                _remoteAudioBlocked.value = blocked
            },
            setMicBlocked = { blocked -> _micBlocked.value = blocked },
            vibrate = { vibrateAlert() },
            playWarningTone = { AlertTone.play() },
            speak = { text, onDone -> speak(text, VoiceRole.GUARD, flush = true) { onDone?.invoke() } },
            showInterventionScreen = { message, offerChoice ->
                _interventionScreen.value =
                    InterventionScreen(message, offerChoice, announcementDone = false)
            },
            setAnnouncementDone = {
                _interventionScreen.value = _interventionScreen.value?.copy(announcementDone = true)
            },
            hideInterventionScreen = { _interventionScreen.value = null },
            endCall = { terminateByApp() },
            onLog = { event, data ->
                logger.log(event, data)
                if (event == "intervention_fired") _interventionFired.value = true
            },
            postDelayed = { delayMs, action -> mainHandler.postDelayed(action, delayMs) }
        )
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        durationJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        scriptPlayer.stop()
        micCapture?.stop()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        stt.release()
        logger.release()
        restoreAudioMode()
        super.onDestroy()
    }

    // ── TTS ──────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            emit("TTS 엔진을 초기화하지 못했습니다. 기기의 음성 엔진 설정을 확인하세요.")
            return
        }
        val result = tts?.setLanguage(Locale.KOREAN)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            emit("한국어 TTS 음성이 없습니다. 설정 → 접근성 → TTS 출력에서 한국어를 설치하세요.")
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = complete(utteranceId)

            @Deprecated("deprecated in API 21")
            override fun onError(utteranceId: String?) = complete(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // 경고 음성이 상대방 음성을 끊고 들어오면(QUEUE_FLUSH) 끊긴 쪽은 onDone이
                // 오지 않는다. 여기서 대신 완료 처리하지 않으면 대본 재생이 영영 멈춘다.
                complete(utteranceId)
            }

            private fun complete(utteranceId: String?) {
                utteranceId?.let { ttsCallbacks.remove(it)?.invoke() }
            }
        })
        ttsReady = true
        val queued = synchronized(pendingUtterances) {
            val copy = pendingUtterances.toList()
            pendingUtterances.clear()
            copy
        }
        // 엔진 준비 전에 들어온 발화를 **같은 utteranceId로** 내보낸다.
        // 새 id를 만들면 원래 완료 콜백이 고아가 되어 대본이 실제 발화 길이와 무관하게 넘어간다.
        queued.forEach { enqueue(it.text, it.role, it.flush, it.utteranceId) }
    }

    private fun speak(text: String, role: VoiceRole, flush: Boolean, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) { onDone?.invoke(); return }
        val id = "u_" + System.nanoTime()
        onDone?.let { ttsCallbacks[id] = it }

        // TTS 엔진이 완료 콜백을 끝내 보내지 않는 기기가 있다. 그대로 두면 대본 재생이
        // 멈춘 채 세션이 통째로 날아가므로, 넉넉한 상한을 두고 강제로 완료 처리한다.
        val watchdogMs = 4000L + text.length * 130L
        lifecycleScope.launch {
            delay(watchdogMs)
            ttsCallbacks.remove(id)?.let {
                logger.log("tts_watchdog_fired", mapOf("utteranceId" to id, "afterMs" to watchdogMs))
                it.invoke()
            }
        }

        if (!ttsReady) {
            synchronized(pendingUtterances) {
                pendingUtterances.add(PendingUtterance(text, role, flush, id))
            }
            return
        }
        enqueue(text, role, flush, id)
    }

    private fun enqueue(text: String, role: VoiceRole, flush: Boolean, utteranceId: String) {
        val engine = tts ?: return
        // 경고 음성과 상대방 음성은 반드시 다르게 들려야 한다. 같은 목소리면 참가자가
        // 경고를 "상대가 하는 말"로 오인해 개입이 사기의 일부처럼 받아들여진다.
        when (role) {
            VoiceRole.ATTACKER -> { engine.setPitch(1.0f); engine.setSpeechRate(1.0f) }
            VoiceRole.GUARD -> { engine.setPitch(0.82f); engine.setSpeechRate(0.92f) }
        }
        if (role == VoiceRole.GUARD) {
            updateAudioOutputs(true)
            val existing = ttsCallbacks[utteranceId]
            ttsCallbacks[utteranceId] = {
                updateAudioOutputs(false)
                existing?.invoke()
            }
        }
        engine.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            utteranceId
        )
    }

    private fun updateAudioOutputs(playing: Boolean) {
        if (playing) audioOutputs.incrementAndGet()
        else audioOutputs.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }

    // ── 세션 제어 ─────────────────────────────────────────────────

    fun startSession(
        participantId: String,
        trialOrder: Int,
        interventionId: InterventionId,
        scriptId: String,
        mode: PlaybackMode,
        voice: AttackerVoice,
        recordAudio: Boolean,
        callerNameOverride: String = ""
    ) {
        if (_callState.value == CallState.IN_CALL) {
            emit("이미 세션이 진행 중입니다.")
            return
        }
        if (!hasMicPermission()) {
            emit("마이크 권한이 없어 세션을 시작할 수 없습니다. 앱 권한에서 마이크를 허용해 주세요.")
            return
        }
        val script = AttackerScriptCatalog.findById(scriptId) ?: run {
            emit("시나리오를 찾을 수 없습니다: " + scriptId)
            return
        }
        val config = InterventionCatalog.findById(interventionId)

        activeScript = script
        activeIntervention = config
        activeMode = mode
        activeVoice = voice

        // 고른 목소리는 녹음본을 재생할 때만 소리에 반영된다. 나머지 모드는 시스템 TTS가
        // 읽으므로 목소리 선택이 아무 효과가 없는데, 그 사실을 모른 채 목소리 조건으로
        // 묶어 분석하면 존재하지 않는 조건을 비교하게 된다.
        val voiceApplied = mode == PlaybackMode.RECORDING
        if (!voiceApplied) {
            emit("'" + mode.label + "' 모드에서는 고른 목소리(" + voice.label + ")가 적용되지 않습니다.")
        }

        // 지인 조건에서만 이름을 띄운다. 참가자 나이·가족 구성에 맞춰 연구자가 바꿀 수 있다.
        val displayName = if (script.relationship == CallerRelationship.ACQUAINTANCE) {
            callerNameOverride.ifBlank { script.defaultCallerName }
        } else {
            ""
        }
        _callerName.value = displayName
        _callerNumber.value = script.callerNumber

        callStartedAt = System.currentTimeMillis()
        val dir = logger.start(
            SessionMeta(
                participantId = participantId,
                trialOrder = trialOrder,
                interventionId = config.id.name,
                interventionLabel = config.label,
                scriptId = script.id,
                scriptLabel = script.label,
                scamLevel = script.scamLevel.name,
                relationship = script.relationship.name,
                callerDisplayed = displayName.ifBlank { script.callerNumber },
                playbackMode = mode.name,
                attackerVoice = voice.id,
                attackerVoiceLabel = voice.label,
                attackerVoiceApplied = voiceApplied,
                startedAt = callStartedAt
            )
        )

        leakDetector.reset()
        stt.reset()
        speechOnsetLogged = false
        audioOutputs.set(0)
        _interventionFired.value = false
        _interventionScreen.value = null
        _micBlocked.value = false
        _remoteAudioBlocked.value = false
        _participantMuted.value = false
        _leakFlag.value = null
        _lastTranscript.value = ""
        _callDuration.value = "00:00"
        _callState.value = CallState.IN_CALL

        promoteToForeground("세션 진행 중 — " + config.label)
        configureAudioForCall()
        startDurationTimer()

        val recordFile = if (recordAudio) File(dir, "recording.wav") else null
        micCapture = MicCapture(
            onPcm = { pcm, len -> stt.feed(pcm, len, MicCapture.SAMPLE_RATE) },
            onError = { msg ->
                emit(msg)
                logger.log("mic_error", mapOf("message" to msg))
            }
        ).also { capture ->
            val ok = capture.start(recordFile)
            logger.log(
                "mic_capture_started",
                mapOf("ok" to ok, "recording" to (recordFile != null), "sttReady" to stt.ready.value)
            )
        }

        intervention.arm(config)

        // 벨소리 없이 바로 통화 중 상태로 시작한다. 이번 실험의 관심사는 수신 판단이
        // 아니라 "대사가 끝난 순간의 개입"이므로, 앞단을 길게 두면 세션마다 편차만 커진다.
        scriptPlayer.start(script, mode, voice)
    }

    /** 라이브 모드 — 연구자가 방금 읽은 대사를 완료 처리 */
    fun advanceLiveLine() = scriptPlayer.advance()

    /** 개입 후 후속 대사 재생 (참가자가 통화를 계속할 때) */
    fun playFollowUp(index: Int) = scriptPlayer.playFollowUp(index)

    /**
     * 연구자 패널에서 개입 조건을 바꾼다 (재무장).
     *
     * 이미 발동한 뒤에는 바꾸지 않는다 — 발동된 개입을 사후에 다른 조건으로 갈아끼우면
     * 그 세션이 어떤 조건이었는지 로그로 말할 수 없게 된다.
     * @return 실제로 바뀌었는지
     */
    fun rearmIntervention(interventionId: InterventionId): Boolean {
        if (intervention.fired) {
            emit("이미 개입이 발동해 조건을 바꿀 수 없습니다.")
            return false
        }
        val config = InterventionCatalog.findById(interventionId)
        activeIntervention = config
        intervention.arm(config)
        logger.log("intervention_rearmed", mapOf("interventionId" to config.id.name))
        if (logger.isActive) updateNotification("세션 진행 중 — " + config.label)
        emit("개입 조건을 바꿨습니다: " + config.label)
        return true
    }

    /** 연구자 수동 개입 */
    fun triggerInterventionNow() {
        // 수동 발동은 대본 중간에 들어올 수 있다. 상대방이 계속 말하는 상태로 두면
        // 경고 음성과 겹쳐 참가자가 둘 다 못 알아듣는다.
        scriptPlayer.stop()
        intervention.fireManual()
    }

    /** 참가자가 팝업에서 [통화 계속하기]를 선택 — 마이크 차단이 풀린다 */
    fun resumeAfterIntervention() {
        if (_callState.value != CallState.IN_CALL) return
        intervention.resumeCall()
    }

    /**
     * 참가자가 개입 화면에서 [통화 종료]를 선택.
     *
     * 조건 1은 통화가 아직 살아 있으므로 여기서 끊는다. 조건 2는 앱이 이미 끊어 놓았으므로
     * 참가자가 안내를 확인하고 화면을 벗어난 시각만 남긴다 — 강제 종료 조건에서 참가자가
     * 그 화면을 얼마나 오래 보고 있었는지가 이 이벤트로만 남는다.
     * 어느 쪽이든 개입 화면을 걷어내 통화 종료 화면으로 넘어간다.
     */
    fun endCallFromInterventionScreen() {
        intervention.dismissScreen()
        if (_callState.value == CallState.IN_CALL) {
            endCallByParticipant("intervention_screen_end")
        } else {
            logger.log(
                "participant_confirmed_termination",
                mapOf("sinceInterventionMs" to sinceIntervention())
            )
        }
    }

    fun toggleParticipantMute() {
        val next = !_participantMuted.value
        _participantMuted.value = next
        logger.log("user_action", mapOf("action" to if (next) "mute_on" else "mute_off"))
    }

    fun toggleSpeaker() {
        val next = !_speakerOn.value
        _speakerOn.value = next
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = next
        }
        logger.log("user_action", mapOf("action" to if (next) "speaker_on" else "speaker_off"))
    }

    /** 참가자가 통화를 끊음 — 개입 효과의 핵심 결과 중 하나 */
    fun endCallByParticipant(how: String = "call_button") {
        if (_callState.value != CallState.IN_CALL) return
        logger.log(
            "call_ended_by_participant",
            mapOf("how" to how, "sinceInterventionMs" to sinceIntervention())
        )
        stopCall()
    }

    /**
     * 앱이 강제 종료 (개입 2).
     * 개입 화면은 **걷어내지 않는다** — 통화가 끊겼다고 다른 화면으로 넘어가면
     * "안내부터 끝까지 한 화면"이라는 설계가 깨진다.
     */
    private fun terminateByApp() {
        if (_callState.value != CallState.IN_CALL) return
        logger.log("call_ended_by_app", mapOf("sinceInterventionMs" to sinceIntervention()))
        stopCall()
    }

    private fun stopCall() {
        scriptPlayer.stop()
        durationJob?.cancel()
        _callState.value = CallState.ENDED
        updateNotification("통화 종료 — 세션 저장 대기")
    }

    /**
     * 세션을 닫고 로그·녹음을 확정한다.
     * 통화가 끝난 뒤에도 마이크를 계속 잡고 있는 이유: 강제 종료 직후 참가자의 반응
     * (혼잣말·재발신 시도 언급 등)이 관찰 대상이기 때문이다.
     */
    fun finishSession(reason: String): File? {
        if (!logger.isActive) {
            // 저장할 로그가 없어도 화면 상태는 반드시 되돌린다. 그러지 않으면 종료 화면이나
            // 개입 화면에 갇혀 다음 시나리오를 시작할 방법이 없어진다.
            resetToIdle()
            return null
        }
        scriptPlayer.stop()
        durationJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { tts?.stop() }

        val recording = micCapture?.stop()
        micCapture = null

        val dir = logger.finish(
            reason,
            mapOf(
                "recordingFile" to recording?.first?.name,
                "recordingDurationMs" to recording?.second
            )
        )
        resetToIdle()
        return dir
    }

    /** 세션 종료 후 처음 화면(설정)으로 돌아가기 위한 상태 초기화 */
    private fun resetToIdle() {
        intervention.reset()
        restoreAudioMode()
        demoteFromForeground()
        audioOutputs.set(0)
        _callState.value = CallState.IDLE
        _micBlocked.value = false
        _remoteAudioBlocked.value = false
        _participantMuted.value = false
        _interventionScreen.value = null
        _currentLineIndex.value = -1
        _currentLineText.value = ""
        activeScript = null
        activeIntervention = null
    }

    // ── 관찰·수동 기록 ────────────────────────────────────────────

    /** 연구자 원클릭 관찰 기록 — STT가 놓친 것을 현장에서 즉시 남긴다 */
    fun logObservation(note: String) {
        logger.log(
            "observation",
            mapOf("note" to note, "sinceInterventionMs" to sinceIntervention())
        )
        emit("기록됨: " + note)
    }

    /** 연구자가 유출 여부를 직접 확정 (STT 오인식 대비) */
    fun markLeak(leaked: Boolean, detail: String) {
        if (leaked) {
            logger.log(
                "leak_detected",
                mapOf(
                    "source" to "researcher",
                    "confidence" to "CONFIRMED",
                    "matched" to detail,
                    "sinceInterventionMs" to sinceIntervention()
                )
            )
            _leakFlag.value = "연구자 확정: " + detail
        } else {
            logger.log("leak_marked_none", mapOf("source" to "researcher", "detail" to detail))
            _leakFlag.value = null
        }
        emit(if (leaked) "유출로 기록했습니다." else "유출 없음으로 기록했습니다.")
    }

    /**
     * STT가 동작하지 않을 때 연구자가 참가자 발화를 대신 주입한다.
     *
     * 발화 인식과 **다른 이벤트 타입**으로 남긴다. 연구자가 타이핑을 끝낸 시각은
     * 참가자가 말한 시각이 아니므로, 같은 타입으로 섞으면 반응시간이 타이핑 속도로 오염된다.
     */
    fun injectParticipantSpeech(text: String) {
        logger.log("participant_speech_manual", mapOf("text" to text, "source" to "researcher_injected"))
        analyzeParticipantText(text, source = "researcher_injected", countAsReaction = false)
    }

    // ── STT 처리 ─────────────────────────────────────────────────

    private fun onTranscript(text: String, isFinal: Boolean) {
        if (!logger.isActive) return
        if (isFinal) _lastTranscript.value = text

        // 스피커로 나간 상대방·경고 음성이 마이크로 되들어온 구간은 참가자 발화가 아니다.
        // 기록은 남기되(사후 검증용) 유출 판정과 반응시간에서는 제외한다.
        if (isAudioPlaying) {
            if (isFinal) {
                logger.log("participant_speech_gated", mapOf("text" to text, "source" to "stt"))
            }
            return
        }

        if (isFinal) {
            logger.log("participant_speech", mapOf("text" to text, "source" to "stt"))
        }
        analyzeParticipantText(
            text,
            source = if (isFinal) "stt" else "stt_partial",
            countAsReaction = true
        )
    }

    private fun analyzeParticipantText(text: String, source: String, countAsReaction: Boolean) {
        if (countAsReaction) markSpeechOnset()

        leakDetector.analyzeLeak(text)?.let { leak ->
            logger.log(
                "leak_detected",
                mapOf(
                    "source" to source,
                    "confidence" to leak.confidence.name,
                    "matched" to leak.matched,
                    "text" to text,
                    "sinceInterventionMs" to sinceIntervention()
                )
            )
            _leakFlag.value = leak.confidence.name + ": " + leak.matched
            if (leak.confidence == LeakDetector.Confidence.SUSPECTED) {
                emit("유출 의심 감지 — 연구자 패널에서 확인해 주세요: " + leak.matched)
            }
        }
        leakDetector.analyzeRefusal(text)?.let { phrase ->
            logger.log(
                "refusal_detected",
                mapOf("phrase" to phrase, "text" to text, "sinceInterventionMs" to sinceIntervention())
            )
        }
    }

    /**
     * 개입 이후 참가자가 **말을 시작한** 시각을 한 번만 기록한다.
     *
     * 중간 인식(partial)에서 잡는 이유: 최종 인식은 발화가 끝나고 무음이 이어져야 나오므로
     * 그 시각을 쓰면 반응시간에 발화 길이와 무음 대기가 통째로 더해진다.
     */
    private fun markSpeechOnset() {
        if (speechOnsetLogged) return
        val firedAt = intervention.firedAt ?: return
        speechOnsetLogged = true
        logger.log(
            "participant_speech_onset",
            mapOf("delayMs" to (System.currentTimeMillis() - firedAt))
        )
    }

    private fun sinceIntervention(): Long? =
        intervention.firedAt?.let { System.currentTimeMillis() - it }

    // ── 포그라운드 승격 ───────────────────────────────────────────

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 통화 중에는 화면이 꺼지거나 참가자가 홈으로 나가도 캡처·재생이 끊기면 안 된다.
     * 마이크 타입 포그라운드 서비스는 RECORD_AUDIO가 있어야 승격할 수 있으므로,
     * 권한을 확보한 세션 시작 시점에만 승격한다.
     */
    private fun promoteToForeground(text: String) {
        if (isForeground) { updateNotification(text); return }
        runCatching {
            // bind만으로 만들어진 서비스는 화면이 내려가 언바인드되는 순간 소멸한다.
            // 통화 중 세션이 통째로 사라지지 않도록, 시작된 서비스로도 승격해 둔다.
            startService(Intent(this, ExperimentSessionService::class.java))
            startForeground(NOTIFICATION_ID, buildNotification(text))
            isForeground = true
        }.onFailure {
            Log.e(TAG, "포그라운드 승격 실패", it)
            logger.log("foreground_promote_failed", mapOf("error" to (it.message ?: "unknown")))
            emit("백그라운드 유지 권한 확보에 실패했습니다. 실험 중 화면을 끄지 마세요.")
        }
    }

    private fun demoteFromForeground() {
        if (!isForeground) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        isForeground = false
    }

    // ── 오디오·진동 ───────────────────────────────────────────────

    private fun configureAudioForCall() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            _speakerOn.value = false
        }
    }

    private fun restoreAudioMode() {
        runCatching {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            _speakerOn.value = false
        }
    }

    private fun vibrateAlert() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        // 3회 강한 진동 — 통화 중 손에 쥔 상태에서 확실히 느껴지는 길이
        val pattern = longArrayOf(0, 600, 250, 600, 250, 600)
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { Log.e(TAG, "진동 실패", it) }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - callStartedAt) / 1000
                _callDuration.value = String.format(
                    Locale.KOREA, "%02d:%02d", elapsed / 60, elapsed % 60
                )
                delay(500)
            }
        }
    }

    private fun emit(msg: String) {
        lifecycleScope.launch { _message.emit(msg) }
    }

    // ── 알림 ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "실험 세션",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, TestAppActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("CallGuard 실험")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!isForeground) return
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /** 연구자 패널에서 대본 전체를 보여주기 위한 조회 */
    fun currentScriptLines(): List<ScriptLine> = activeScript?.mainLines ?: emptyList()
}
