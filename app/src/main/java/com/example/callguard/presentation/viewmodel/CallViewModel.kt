package com.example.callguard.presentation.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.callguard.domain.interfaces.BlockReason
import com.example.callguard.domain.interfaces.InterventionEvent
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.interfaces.RiskScore
import com.example.callguard.domain.service.CallService
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
    val q1UnknownPerson: Boolean? = null,  // 모르는 사람?
    val q2UnknownNumber: Boolean? = null,  // 모르는 번호?
    val q3SuspectScam: Boolean? = null,    // 보이스피싱 의심?
    val q4VerifiedReal: Boolean? = null    // 실제 상황 확인했나요?
) {
    val allAnswered: Boolean
        get() = q1UnknownPerson != null && q2UnknownNumber != null &&
                q3SuspectScam != null && q4VerifiedReal != null

    /** 의심 점수 0~3: 높을수록 보이스피싱 가능성 높음 */
    val suspicionScore: Int
        get() = listOf(q1UnknownPerson, q2UnknownNumber, q3SuspectScam).count { it == true }

    /** 차단을 유지해야 하는 판단: 의심 2개 이상이고 실제 상황 미확인 */
    val shouldKeepBlocked: Boolean
        get() = suspicionScore >= 2 && q4VerifiedReal != true
}

class CallViewModel : ViewModel() {
    private val TAG = "CallViewModel"

    private var callService: CallService? = null
    private var isServiceBound = false

    // ── 서비스 바인딩 ─────────────────────────────────────────────
    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

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
    /** 원격 피싱 감지 경고 팝업 */
    private val _showRemotePhishingWarning = MutableStateFlow(false)
    val showRemotePhishingWarning: StateFlow<Boolean> = _showRemotePhishingWarning.asStateFlow()

    /** 로컬 누출 차단 경고 팝업 + 감지된 패턴 */
    private val _localLeakEvent = MutableStateFlow<InterventionEvent.LocalLeakBlocked?>(null)
    val localLeakEvent: StateFlow<InterventionEvent.LocalLeakBlocked?> = _localLeakEvent.asStateFlow()

    /** 상대방 발화 SCAM 확정 → 양쪽 음성 차단 + 설문 시작 팝업 */
    private val _remotePhishingBlockedEvent = MutableStateFlow<InterventionEvent.RemotePhishingBlocked?>(null)
    val remotePhishingBlockedEvent: StateFlow<InterventionEvent.RemotePhishingBlocked?> = _remotePhishingBlockedEvent.asStateFlow()

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
                    if (state == CallService.CallState.IDLE) resetUiStates()
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
                        2 -> answerSurvey(q3 = answer)
                        3 -> answerSurvey(q4 = answer)
                    }
                }
            }
            // 음성 설문: 4문항 모두 응답 완료 시 자동으로 결과 판정/마이크 처리
            viewModelScope.launch {
                bound.voiceSurveyCompleted.collect {
                    submitSurveyAndDecide()
                }
            }

            // STT 모델 로딩 완료 감지
            viewModelScope.launch {
                bound.localSpeechRecognizer.isModelReady.collect { ready ->
                    _isSttReady.value = ready
                }
            }
            // 이미 로딩 완료된 경우 즉시 반영
            _isSttReady.value = bound.localSpeechRecognizer.modelLoaded
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
                // SCAM 확정은 RemotePhishingBlocked가 전담 (양쪽 음성 차단 + 설문) — 여기서는 SUSPICIOUS 경고만 표시
                if (event.riskScore.level == RiskLevel.SUSPICIOUS) {
                    _showRemotePhishingWarning.value = true
                }
            }
            is InterventionEvent.RemotePhishingBlocked -> {
                // 마이크 + 상대 음성은 서비스에서 이미 차단됨 — UI만 업데이트
                _remotePhishingBlockedEvent.value = event
                _isLocalMuted.value = true
                _isRemoteMuted.value = true
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
        viewModelScope.launch {
            if (!isServiceBound) bindCallService(context)
            var count = 0
            while (callService == null && count < 30) {
                kotlinx.coroutines.delay(100)
                count++
            }
            callService?.joinRoom(serverIp, roomId)
        }
    }

    // ── 루프백 테스트 통화 ────────────────────────────────────────

    fun startLoopbackCall(context: Context) {
        val intent = Intent(context, CallService::class.java)
        context.startService(intent)
        viewModelScope.launch {
            if (!isServiceBound) bindCallService(context)
            var count = 0
            while (callService == null && count < 30) {
                kotlinx.coroutines.delay(100)
                count++
            }
            callService?.startLoopbackCall()
        }
    }

    fun endCall() {
        callService?.hangUpCall()
        resetUiStates()
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

    fun dismissRemotePhishingWarning() {
        _showRemotePhishingWarning.value = false
    }

    fun answerSurvey(
        q1: Boolean? = null,
        q2: Boolean? = null,
        q3: Boolean? = null,
        q4: Boolean? = null
    ) {
        _surveyAnswers.value = _surveyAnswers.value.copy(
            q1UnknownPerson = q1 ?: _surveyAnswers.value.q1UnknownPerson,
            q2UnknownNumber = q2 ?: _surveyAnswers.value.q2UnknownNumber,
            q3SuspectScam   = q3 ?: _surveyAnswers.value.q3SuspectScam,
            q4VerifiedReal  = q4 ?: _surveyAnswers.value.q4VerifiedReal
        )
    }

    /**
     * 설문 완료 후 결과에 따라:
     *  - 보이스피싱 확정 → 통화 종료
     *  - 보이스피싱 아님 → 마이크 자동 재개 (REMOTE_PHISHING 원인이었다면 상대 음성도 재개)
     */
    fun submitSurveyAndDecide() {
        callService?.stopVoiceSurvey()
        val answers = _surveyAnswers.value
        val reason = callService?.consumeBlockReason() ?: BlockReason.LOCAL_LEAK

        if (answers.shouldKeepBlocked) {
            // 보이스피싱 확정 → 통화 종료
            _localLeakEvent.value = null
            _remotePhishingBlockedEvent.value = null
            _surveyAnswers.value = LeakSurveyAnswers()
            endCall()
        } else {
            // 보이스피싱 아님으로 확인 → 마이크(및 필요 시 상대 음성) 자동 재개
            _localLeakEvent.value = null
            _remotePhishingBlockedEvent.value = null
            _surveyAnswers.value = LeakSurveyAnswers()
            _isLocalMuted.value = false
            callService?.muteLocalMic(false)
            if (reason == BlockReason.REMOTE_PHISHING) {
                _isRemoteMuted.value = false
                callService?.muteRemoteAudio(false)
            }
        }
    }

    fun dismissLocalLeakWarning() {
        callService?.stopVoiceSurvey()
        callService?.consumeBlockReason()
        _localLeakEvent.value = null
        _remotePhishingBlockedEvent.value = null
        _surveyAnswers.value = LeakSurveyAnswers()
        _isLocalMuted.value = false
        callService?.muteLocalMic(false)
    }

    // ── 시뮬레이션 ───────────────────────────────────────────────

    fun simulateRemoteSpeech(phrase: String) {
        callService?.simulateRemoteSpeech(phrase)
    }

    fun simulateLocalSpeech(phrase: String) {
        callService?.simulateLocalSpeech(phrase)
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
        _transcripts.value = list
    }

    private fun resetUiStates() {
        _transcripts.value = emptyList()
        _riskScore.value = RiskScore(0f, RiskLevel.SAFE, emptyList())
        _showRemotePhishingWarning.value = false
        _localLeakEvent.value = null
        _remotePhishingBlockedEvent.value = null
        _surveyAnswers.value = LeakSurveyAnswers()
        _isLocalMuted.value = false
        _isRemoteMuted.value = false
        _voiceSurveyQuestionIndex.value = -1
        _voiceSurveyListening.value = false
    }
}
