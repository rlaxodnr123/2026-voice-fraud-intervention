package com.example.callguard.experiment.presentation.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.callguard.experiment.domain.scenario.ScenarioCatalog
import com.example.callguard.experiment.domain.scenario.ScenarioConfig
import com.example.callguard.experiment.domain.service.ExperimentCallService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TranscriptItem(
    val speaker: String,
    val text: String,
    val isFinal: Boolean
)

class ExperimentViewModel : ViewModel() {

    companion object {
        private const val MAX_TRANSCRIPTS = 100
    }

    private var service: ExperimentCallService? = null
    private var isServiceBound = false

    // ── 서비스 바인딩 ─────────────────────────────────────────────
    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage

    // ── 통화 상태 ─────────────────────────────────────────────────
    private val _callState = MutableStateFlow(ExperimentCallService.CallState.IDLE)
    val callState: StateFlow<ExperimentCallService.CallState> = _callState.asStateFlow()

    private val _callDuration = MutableStateFlow("00:00")
    val callDuration: StateFlow<String> = _callDuration.asStateFlow()
    private var durationJob: Job? = null

    // ── 시나리오 상태 ─────────────────────────────────────────────
    private val _armedScenario = MutableStateFlow<ScenarioConfig?>(null)
    val armedScenario: StateFlow<ScenarioConfig?> = _armedScenario.asStateFlow()

    private val _scenarioFired = MutableStateFlow(false)
    val scenarioFired: StateFlow<Boolean> = _scenarioFired.asStateFlow()

    // ── 개입 UI 상태 ──────────────────────────────────────────────
    private val _noticeBanner = MutableStateFlow<ExperimentCallService.BannerState?>(null)
    val noticeBanner: StateFlow<ExperimentCallService.BannerState?> = _noticeBanner.asStateFlow()

    private val _textSurveyActive = MutableStateFlow(false)
    val textSurveyActive: StateFlow<Boolean> = _textSurveyActive.asStateFlow()

    private val _textSurveyQuestionIndex = MutableStateFlow(-1)
    val textSurveyQuestionIndex: StateFlow<Int> = _textSurveyQuestionIndex.asStateFlow()

    private val _voiceSurveyActive = MutableStateFlow(false)
    val voiceSurveyActive: StateFlow<Boolean> = _voiceSurveyActive.asStateFlow()

    private val _voiceSurveyQuestionIndex = MutableStateFlow(-1)
    val voiceSurveyQuestionIndex: StateFlow<Int> = _voiceSurveyQuestionIndex.asStateFlow()

    /** 연구자 패널 표시용 설문 응답 기록: (설문종류, 질문 인덱스, 답변) 누적 */
    private val _surveyAnswers = MutableStateFlow<List<Triple<String, Int, Boolean>>>(emptyList())
    val surveyAnswers: StateFlow<List<Triple<String, Int, Boolean>>> = _surveyAnswers.asStateFlow()

    // ── 연구자 패널 (숨김 화면, §5.2) ─────────────────────────────
    private val _researcherPanelVisible = MutableStateFlow(false)
    val researcherPanelVisible: StateFlow<Boolean> = _researcherPanelVisible.asStateFlow()

    // 참가자 화면 우상단 5회 연속 탭으로 진입
    private var secretTapCount = 0
    private var lastSecretTapTime = 0L

    fun onSecretCornerTap() {
        val now = System.currentTimeMillis()
        if (now - lastSecretTapTime > 2000) secretTapCount = 0
        lastSecretTapTime = now
        secretTapCount++
        if (secretTapCount >= 5) {
            secretTapCount = 0
            _researcherPanelVisible.value = true
            service?.logManualOverride("researcher_panel_opened")
        }
    }

    fun closeResearcherPanel() {
        _researcherPanelVisible.value = false
        service?.logManualOverride("researcher_panel_closed")
    }

    // ── 실시간 녹취록 (연구자 패널 전용 표시) ─────────────────────
    private val _transcripts = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<TranscriptItem>> = _transcripts.asStateFlow()

    private val _isSttReady = MutableStateFlow(false)
    val isSttReady: StateFlow<Boolean> = _isSttReady.asStateFlow()

    private val _isLocalMuted = MutableStateFlow(false)
    val isLocalMuted: StateFlow<Boolean> = _isLocalMuted

    // 시나리오에 의한 마이크 차단 여부 (참가자 음소거 버튼과 별개) — 참가자 화면 '차단됨' 표시용
    private val _isScenarioMicBlocked = MutableStateFlow(false)
    val isScenarioMicBlocked: StateFlow<Boolean> = _isScenarioMicBlocked.asStateFlow()

    // 통화 연결 시 스피커폰이 자동으로 켜지므로 기본값 true
    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    // ── 서비스 연결 ───────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as ExperimentCallService.ExperimentCallServiceBinder).getService()
            service = bound
            isServiceBound = true
            _isBound.value = true

            viewModelScope.launch {
                bound.callState.collect { state ->
                    _callState.value = state
                    when (state) {
                        ExperimentCallService.CallState.CONNECTED -> startDurationTimer()
                        ExperimentCallService.CallState.IDLE,
                        ExperimentCallService.CallState.DISCONNECTED -> {
                            stopDurationTimer()
                            resetUiStates()
                        }
                        else -> {}
                    }
                }
            }
            viewModelScope.launch {
                bound.serviceMessage.collect { _userMessage.emit(it) }
            }
            viewModelScope.launch {
                bound.audioPipeline.transcriptFlow.collect { (speaker, text) ->
                    updateTranscript(speaker, text, isFinal = true)
                }
            }
            viewModelScope.launch {
                bound.audioPipeline.partialTranscriptFlow.collect { (speaker, text) ->
                    updateTranscript(speaker, text, isFinal = false)
                }
            }
            viewModelScope.launch {
                bound.scenarioController.armedScenario.collect { _armedScenario.value = it }
            }
            viewModelScope.launch {
                bound.scenarioController.fired.collect { _scenarioFired.value = it }
            }
            viewModelScope.launch {
                bound.noticeBanner.collect { _noticeBanner.value = it }
            }
            viewModelScope.launch {
                bound.textSurveyController.isActive.collect { _textSurveyActive.value = it }
            }
            viewModelScope.launch {
                bound.textSurveyController.currentQuestionIndex.collect { _textSurveyQuestionIndex.value = it }
            }
            viewModelScope.launch {
                bound.voiceSurveyController.isActive.collect { _voiceSurveyActive.value = it }
            }
            viewModelScope.launch {
                bound.voiceSurveyController.currentQuestionIndex.collect { _voiceSurveyQuestionIndex.value = it }
            }
            viewModelScope.launch {
                bound.surveyAnswerEvent.collect { answer ->
                    _surveyAnswers.value = _surveyAnswers.value + answer
                }
            }
            viewModelScope.launch {
                bound.localSpeechRecognizer.isModelReady.collect { _isSttReady.value = it }
            }
            viewModelScope.launch {
                bound.scenarioMicBlocked.collect { _isScenarioMicBlocked.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isServiceBound = false
            _isBound.value = false
        }
    }

    fun bindService(context: Context) {
        val intent = Intent(context, ExperimentCallService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (isServiceBound) {
            context.unbindService(serviceConnection)
            isServiceBound = false
            _isBound.value = false
        }
    }

    // ── 통화 제어 ─────────────────────────────────────────────────

    fun joinRoom(context: Context, serverIp: String, roomId: String) {
        val intent = Intent(context, ExperimentCallService::class.java)
        context.startService(intent)
        _callState.value = ExperimentCallService.CallState.CONNECTING
        viewModelScope.launch {
            if (!isServiceBound) bindService(context)
            val svc = awaitService()
            if (svc == null) {
                _callState.value = ExperimentCallService.CallState.IDLE
                _userMessage.emit("통화 서비스 연결에 실패했습니다. 다시 시도해 주세요.")
                return@launch
            }
            svc.joinRoom(serverIp, roomId)
        }
    }

    fun startLoopbackCall(context: Context, monitorServerUrl: String? = null) {
        val intent = Intent(context, ExperimentCallService::class.java)
        context.startService(intent)
        viewModelScope.launch {
            if (!isServiceBound) bindService(context)
            val svc = awaitService()
            if (svc == null) {
                _callState.value = ExperimentCallService.CallState.IDLE
                _userMessage.emit("통화 서비스 연결에 실패했습니다. 다시 시도해 주세요.")
                return@launch
            }
            svc.startLoopbackCall(monitorServerUrl)
        }
    }

    private suspend fun awaitService(): ExperimentCallService? {
        var count = 0
        while (service == null && count < 30) {
            delay(100)
            count++
        }
        return service
    }

    fun endCall() {
        service?.logUserAction("전화 끊기")
        service?.hangUpCall()
        resetUiStates()
    }

    // ── 연구자 패널 명령 (온디바이스 = 원격 콘솔과 1:1 대응, §5.2) ──

    val scenarioCatalog: List<ScenarioConfig> get() = ScenarioCatalog.scenarios

    fun armScenario(scenarioId: String) {
        service?.let {
            it.armScenario(scenarioId)
            it.logManualOverride("arm_scenario", mapOf("scenarioId" to scenarioId))
        }
    }

    fun triggerNow() {
        service?.logManualOverride("trigger_now")
        service?.triggerNow()
    }

    fun resetSession() {
        service?.logManualOverride("reset_session")
        service?.resetSession()
        _surveyAnswers.value = emptyList()
        // 서비스에서 participantMuted를 해제했으므로 UI 음소거 표시도 초기화
        _isLocalMuted.value = false
    }

    fun injectRemoteSpeech(phrase: String) {
        service?.logManualOverride("inject_remote", mapOf("phrase" to phrase))
        service?.simulateRemoteSpeech(phrase)
    }

    fun injectLocalSpeech(phrase: String) {
        service?.logManualOverride("inject_local", mapOf("phrase" to phrase))
        service?.simulateLocalSpeech(phrase)
    }

    /** 공격자 요구 → (지연) → 피해자 숫자 발화 콤보 주입 */
    fun injectSensitiveCombo(requestPhrase: String, digitPhrase: String) {
        viewModelScope.launch {
            injectRemoteSpeech(requestPhrase)
            delay(1500)
            injectLocalSpeech(digitPhrase)
        }
    }

    fun forceSurveyAnswer(index: Int, value: Boolean) {
        service?.logManualOverride("force_answer", mapOf("index" to index, "value" to value))
        service?.forceSurveyAnswer(index, value)
    }

    fun addObservationNote(text: String) {
        if (text.isBlank()) return
        service?.logObservationNote(text.trim())
    }

    // ── 참가자 UI 콜백 ────────────────────────────────────────────

    fun dismissNoticeBanner() = service?.dismissTextBanner()

    fun answerTextSurvey(value: Boolean) = service?.answerTextSurvey(value)

    fun toggleLocalMute() {
        val next = !_isLocalMuted.value
        _isLocalMuted.value = next
        service?.muteLocalMic(next)
    }

    fun toggleSpeakerphone() {
        val on = service?.toggleSpeakerphone() ?: return
        _isSpeakerOn.value = on
    }

    fun logUserAction(action: String) = service?.logUserAction(action)

    // ── 내부 헬퍼 ────────────────────────────────────────────────

    private fun updateTranscript(speaker: String, text: String, isFinal: Boolean) {
        val list = _transcripts.value.toMutableList()
        val lastIdx = list.indexOfLast { it.speaker == speaker }
        if (lastIdx >= 0 && !list[lastIdx].isFinal) {
            list[lastIdx] = TranscriptItem(speaker, text, isFinal)
        } else {
            list.add(TranscriptItem(speaker, text, isFinal))
        }
        _transcripts.value = if (list.size > MAX_TRANSCRIPTS) list.takeLast(MAX_TRANSCRIPTS) else list
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        _callDuration.value = "00:00"
        durationJob = viewModelScope.launch {
            var seconds = 0
            while (true) {
                delay(1000)
                seconds++
                _callDuration.value = "%02d:%02d".format(seconds / 60, seconds % 60)
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
        _callDuration.value = "00:00"
    }

    private fun resetUiStates() {
        _transcripts.value = emptyList()
        _noticeBanner.value = null
        _surveyAnswers.value = emptyList()
        _isLocalMuted.value = false
        _isScenarioMicBlocked.value = false
        _isSpeakerOn.value = true
    }
}
