package com.example.callguard.presentation.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.callguard.domain.interfaces.InterventionEvent
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.interfaces.RiskScore
import com.example.callguard.domain.service.CallService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TranscriptItem(
    val speaker: String,
    val text: String,
    val isFinal: Boolean
)

/**
 * 마이크 차단 후 상황 판단 설문 응답.
 * null = 아직 응답 안 함
 */
data class LeakSurveyAnswers(
    val q1KnownPerson: Boolean? = null,  // 전화를 건 사람이 직접 아는 분인가요?
    val q2VerifiedReal: Boolean? = null    // 가족·지인과의 실제 상황인가요?
) {
    val allAnswered: Boolean
        get() = q1KnownPerson != null && q2VerifiedReal != null

    /**
     * 차단 유지 판단: 아는 사람이 아니고(q1=false) 실제 상황이 아님(q2=false)일 때만 종료.
     * 사용자가 실제 상황이라고 확인(q2=true)하면 항상 재개.
     */
    val shouldKeepBlocked: Boolean
        get() = q1KnownPerson == false && q2VerifiedReal != true
}

class CallViewModel : ViewModel() {
    private val TAG = "CallViewModel"

    companion object {
        private const val MAX_TRANSCRIPTS = 100
    }

    private var callService: CallService? = null
    private var isServiceBound = false

    // ── 서비스 바인딩 ─────────────────────────────────────────────
    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    // ── 사용자 안내 메시지 (Toast 등) ────────────────────────────
    private val _userMessage = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: kotlinx.coroutines.flow.SharedFlow<String> = _userMessage

    // ── 통화 상태 ─────────────────────────────────────────────────
    private val _callState = MutableStateFlow(CallService.CallState.IDLE)
    val callState: StateFlow<CallService.CallState> = _callState.asStateFlow()

    // ── 실시간 녹취록 ─────────────────────────────────────────────
    private val _transcripts = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<TranscriptItem>> = _transcripts.asStateFlow()

    // ── 원격 피싱 위험도 ──────────────────────────────────────────
    private val _riskScore = MutableStateFlow(RiskScore(0f, RiskLevel.SAFE, emptyList()))
    val riskScore: StateFlow<RiskScore> = _riskScore.asStateFlow()

    // ── 개입 팝업 상태 ────────────────────────────────────────────
    /** 로컬 누출 차단 경고 팝업 + 감지된 패턴 */
    private val _localLeakEvent = MutableStateFlow<InterventionEvent.LocalLeakBlocked?>(null)
    val localLeakEvent: StateFlow<InterventionEvent.LocalLeakBlocked?> = _localLeakEvent.asStateFlow()

    /** 상대방 발화 SCAM 확정 → 양쪽 음성 차단 + 설문 시작 팝업 */
    private val _remotePhishingBlockedEvent = MutableStateFlow<InterventionEvent.RemotePhishingBlocked?>(null)
    val remotePhishingBlockedEvent: StateFlow<InterventionEvent.RemotePhishingBlocked?> = _remotePhishingBlockedEvent.asStateFlow()

    /**
     * 상대방 발화 SUSPICIOUS(의심 단계) → 차단 없이 확인 배너만 표시.
     * 실험에서 "경고 팝업 확인 여부"를 측정할 수 있도록 사용자가 직접 확인(dismiss)해야 사라진다.
     */
    private val _suspiciousWarningEvent = MutableStateFlow<InterventionEvent.RemotePhishingDetected?>(null)
    val suspiciousWarningEvent: StateFlow<InterventionEvent.RemotePhishingDetected?> = _suspiciousWarningEvent.asStateFlow()

    fun dismissSuspiciousWarning() {
        callService?.logUserAction("경고 배너 확인 (SUSPICIOUS)")
        _suspiciousWarningEvent.value = null
    }

    /** 마이크 차단 후 설문 응답 상태 */
    private val _surveyAnswers = MutableStateFlow(LeakSurveyAnswers())
    val surveyAnswers: StateFlow<LeakSurveyAnswers> = _surveyAnswers.asStateFlow()

    // ── 음성 설문 상태 (차단 후 TTS로 묻고 STT로 답을 받는다) ────────
    private val _voiceSurveyQuestionIndex = MutableStateFlow(-1)
    val voiceSurveyQuestionIndex: StateFlow<Int> = _voiceSurveyQuestionIndex.asStateFlow()

    private val _voiceSurveyListening = MutableStateFlow(false)
    val voiceSurveyListening: StateFlow<Boolean> = _voiceSurveyListening.asStateFlow()

    // ── STT 모델 로딩 상태 ────────────────────────────────────────
    private val _isSttReady = MutableStateFlow(false)
    val isSttReady: StateFlow<Boolean> = _isSttReady.asStateFlow()

    // ── 통화 시간 타이머 (VictimCallScreen 표시용) ───────────────
    private val _callDuration = MutableStateFlow("00:00")
    val callDuration: StateFlow<String> = _callDuration.asStateFlow()
    private var durationJob: Job? = null

    // ── 오디오 상태 ───────────────────────────────────────────────
    private val _isLocalMuted = MutableStateFlow(false)
    val isLocalMuted: StateFlow<Boolean> = _isLocalMuted.asStateFlow()

    private val _isRemoteMuted = MutableStateFlow(false)
    val isRemoteMuted: StateFlow<Boolean> = _isRemoteMuted.asStateFlow()

    // ── 서비스 바인딩 연결 ────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as CallService.CallServiceBinder).getService()
            callService = bound
            isServiceBound = true
            _isBound.value = true

            viewModelScope.launch {
                bound.callState.collect { state ->
                    _callState.value = state
                    when (state) {
                        CallService.CallState.CONNECTED -> startDurationTimer()
                        CallService.CallState.IDLE,
                        CallService.CallState.DISCONNECTED -> {
                            stopDurationTimer()
                            resetUiStates()
                        }
                        else -> {}
                    }
                }
            }
            viewModelScope.launch {
                bound.serviceMessage.collect { message ->
                    _userMessage.emit(message)
                }
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
                bound.audioPipeline.riskScoreFlow.collect { risk ->
                    _riskScore.value = risk
                }
            }
            viewModelScope.launch {
                bound.interventionEvent.collect { event ->
                    handleIntervention(event)
                }
            }

            // 음성 설문: 진행 중인 질문 번호 / 듣고 있는지 여부
            viewModelScope.launch {
                bound.voiceSurveyController.currentQuestionIndex.collect { idx ->
                    _voiceSurveyQuestionIndex.value = idx
                }
            }
            viewModelScope.launch {
                bound.voiceSurveyController.isListening.collect { listening ->
                    _voiceSurveyListening.value = listening
                }
            }
            // 음성 설문: 질문별 답변이 들어오면 기존 설문 상태에 그대로 반영
            viewModelScope.launch {
                bound.voiceSurveyAnswerEvent.collect { (questionIndex, answer) ->
                    when (questionIndex) {
                        0 -> answerSurvey(q1 = answer)
                        1 -> answerSurvey(q2 = answer)
                    }
                }
            }
            // 음성 설문: 모든 질문에 응답 완료 시 결과 판정/마이크 처리.
            // 반복 무응답으로 강제 종료된 경우(timedOut=true)는 실제 상황을 확인할 수 없으므로
            // 통화를 재개하지 않고 안전하게 끊는다.
            viewModelScope.launch {
                bound.voiceSurveyCompleted.collect { timedOut ->
                    if (timedOut) {
                        callService?.logUserAction("설문 무응답 타임아웃 → 통화 종료")
                        _localLeakEvent.value = null
                        _remotePhishingBlockedEvent.value = null
                        _suspiciousWarningEvent.value = null
                        _surveyAnswers.value = LeakSurveyAnswers()
                        endCall()
                    } else {
                        submitSurveyAndDecide()
                    }
                }
            }

            // STT 모델 로딩 완료 감지.
            // isModelReady가 StateFlow라 collect 시점에 현재 값이 즉시 방출되어 별도 폴백이 불필요.
            viewModelScope.launch {
                bound.localSpeechRecognizer.isModelReady.collect { ready ->
                    _isSttReady.value = ready
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            callService = null
            isServiceBound = false
            _isBound.value = false
        }
    }

    private fun handleIntervention(event: InterventionEvent) {
        when (event) {
            is InterventionEvent.RemotePhishingDetected -> {
                // SUSPICIOUS 단계 — 차단 없이 확인 배너만 띄운다 (사용자가 직접 확인해야 사라짐)
                _suspiciousWarningEvent.value = event
            }
            is InterventionEvent.RemotePhishingBlocked -> {
                // 마이크(+ SCAM 확정 시 상대 음성)는 서비스에서 이미 차단됨 — UI만 업데이트
                _remotePhishingBlockedEvent.value = event
                _isLocalMuted.value = true
                _isRemoteMuted.value = event.remoteAlsoMuted
            }
            is InterventionEvent.LocalLeakBlocked -> {
                // 마이크는 서비스에서 이미 뮤트됨 — UI만 업데이트
                _localLeakEvent.value = event
                _isLocalMuted.value = true
            }
        }
    }

    fun bindCallService(context: Context) {
        val intent = Intent(context, CallService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindCallService(context: Context) {
        if (isServiceBound) {
            context.unbindService(serviceConnection)
            isServiceBound = false
            _isBound.value = false
        }
    }

    // ── 통화 시작 (실제 P2P) ─────────────────────────────────────

    fun joinRoom(context: Context, serverIp: String, roomId: String) {
        val intent = Intent(context, CallService::class.java)
        context.startService(intent)
        _callState.value = CallService.CallState.CONNECTING
        viewModelScope.launch {
            if (!isServiceBound) bindCallService(context)
            val svc = awaitService()
            if (svc == null) {
                // 바인딩 타임아웃 — 사용자에게 알리고 IDLE로 복귀 (무음 실패 방지)
                _callState.value = CallService.CallState.IDLE
                _userMessage.emit("통화 서비스 연결에 실패했습니다. 다시 시도해 주세요.")
                return@launch
            }
            svc.joinRoom(serverIp, roomId)
        }
    }

    // ── 루프백 테스트 통화 ────────────────────────────────────────

    fun startLoopbackCall(context: Context, monitorServerUrl: String? = null) {
        val intent = Intent(context, CallService::class.java)
        context.startService(intent)
        viewModelScope.launch {
            if (!isServiceBound) bindCallService(context)
            val svc = awaitService()
            if (svc == null) {
                _callState.value = CallService.CallState.IDLE
                _userMessage.emit("통화 서비스 연결에 실패했습니다. 다시 시도해 주세요.")
                return@launch
            }
            svc.startLoopbackCall(monitorServerUrl)
        }
    }

    /** 서비스 바인딩이 완료될 때까지 최대 3초 대기. 실패 시 null. */
    private suspend fun awaitService(): CallService? {
        var count = 0
        while (callService == null && count < 30) {
            delay(100)
            count++
        }
        return callService
    }

    fun endCall() {
        callService?.logUserAction("전화 끊기")
        callService?.hangUpCall()
        resetUiStates()
    }

    fun logUserAction(action: String) {
        callService?.logUserAction(action)
    }

    fun toggleLocalMute() {
        val next = !_isLocalMuted.value
        _isLocalMuted.value = next
        callService?.muteLocalMic(next)
    }

    fun toggleRemoteMute() {
        val next = !_isRemoteMuted.value
        _isRemoteMuted.value = next
        callService?.muteRemoteAudio(next)
    }

    fun answerSurvey(
        q1: Boolean? = null,
        q2: Boolean? = null
    ) {
        _surveyAnswers.value = _surveyAnswers.value.copy(
            q1KnownPerson  = q1 ?: _surveyAnswers.value.q1KnownPerson,
            q2VerifiedReal  = q2 ?: _surveyAnswers.value.q2VerifiedReal
        )
        q1?.let { callService?.logSurveyAnswer("전화를 건 사람이 직접 아는 분인가요?", it) }
        q2?.let { callService?.logSurveyAnswer("가족·지인과의 실제 상황인가요?", it) }
    }

    /**
     * 설문 완료 후 결과에 따라:
     *  - 보이스피싱 확정 → 통화 종료 (activeBlockReason은 hangUpCall()이 종료 사유 로그에 남긴 뒤 정리한다)
     *  - 보이스피싱 아님 → resumeAfterFalseAlarm()이 디텍터 리셋→언뮤트→차단 해제를 원자적으로 처리
     */
    fun submitSurveyAndDecide() {
        callService?.stopVoiceSurvey()
        val answers = _surveyAnswers.value

        if (answers.shouldKeepBlocked) {
            callService?.logUserAction("판정 확정 (통화 종료 - 보이스피싱 확정)")
            _localLeakEvent.value = null
            _remotePhishingBlockedEvent.value = null
            _surveyAnswers.value = LeakSurveyAnswers()
            endCall()
        } else {
            callService?.logUserAction("마이크 해제하고 통화 계속 (오탐 확인)")
            _localLeakEvent.value = null
            _remotePhishingBlockedEvent.value = null
            _surveyAnswers.value = LeakSurveyAnswers()
            _isLocalMuted.value = false
            _isRemoteMuted.value = false
            callService?.resumeAfterFalseAlarm()
        }
    }

    // ── 시뮬레이션 ───────────────────────────────────────────────

    fun simulateRemoteSpeech(phrase: String) {
        callService?.simulateRemoteSpeech(phrase)
    }

    fun simulateLocalSpeech(phrase: String) {
        callService?.simulateLocalSpeech(phrase)
    }

    /**
     * 테스트용: 공격자의 민감정보 요구 발화 → (짧은 지연) → 피해자의 숫자 발화 순서로 주입해
     * SensitiveDisclosureDetector의 "요구 직후 숫자 발화" 즉시 차단 경로를 검증한다.
     */
    fun simulateSensitiveDisclosureCombo(requestPhrase: String, digitPhrase: String) {
        viewModelScope.launch {
            callService?.simulateRemoteSpeech(requestPhrase)
            delay(1500)
            callService?.simulateLocalSpeech(digitPhrase)
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────

    private fun updateTranscript(speaker: String, text: String, isFinal: Boolean) {
        val list = _transcripts.value.toMutableList()
        val lastIdx = list.indexOfLast { it.speaker == speaker }
        if (lastIdx >= 0 && !list[lastIdx].isFinal) {
            list[lastIdx] = TranscriptItem(speaker, text, isFinal)
        } else {
            list.add(TranscriptItem(speaker, text, isFinal))
        }
        // 장시간 통화 시 무제한 증가로 인한 메모리·렌더 부담 방지: 최근 MAX_TRANSCRIPTS개만 유지
        if (list.size > MAX_TRANSCRIPTS) {
            _transcripts.value = list.takeLast(MAX_TRANSCRIPTS)
        } else {
            _transcripts.value = list
        }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        _callDuration.value = "00:00"
        durationJob = viewModelScope.launch {
            var seconds = 0
            while (true) {
                delay(1000)
                seconds++
                val m = seconds / 60
                val s = seconds % 60
                _callDuration.value = "%02d:%02d".format(m, s)
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
        _riskScore.value = RiskScore(0f, RiskLevel.SAFE, emptyList())
        _localLeakEvent.value = null
        _remotePhishingBlockedEvent.value = null
        _suspiciousWarningEvent.value = null
        _surveyAnswers.value = LeakSurveyAnswers()
        _isLocalMuted.value = false
        _isRemoteMuted.value = false
        _voiceSurveyQuestionIndex.value = -1
        _voiceSurveyListening.value = false
    }
}
