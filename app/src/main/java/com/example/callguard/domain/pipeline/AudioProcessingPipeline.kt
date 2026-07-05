package com.example.callguard.domain.pipeline

import com.example.callguard.domain.interfaces.InterventionEvent
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.interfaces.RiskScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * AudioProcessingPipeline — 두 가지 감지 경로를 연결한다.
 *
 * [경로 A] 원격 STT → MockScamDetector → 피싱 의도 경고
 *   → InterventionEvent.RemotePhishingDetected
 *
 * [경로 B] 로컬 STT (Partial 포함) → LocalLeakDetector → 즉시 마이크 차단
 *   → InterventionEvent.LocalLeakBlocked
 */
class AudioProcessingPipeline(
    val localSpeechRecognizer: MockSpeechRecognizer,
    val remoteSpeechRecognizer: MockSpeechRecognizer,
    val scamDetector: MockScamDetector,
    val interventionEngine: MockInterventionEngine,
    val localLeakDetector: LocalLeakDetector,
    val sensitiveDisclosureDetector: SensitiveDisclosureDetector
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    // 화자 → 텍스트 (final)
    private val _transcriptFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val transcriptFlow: SharedFlow<Pair<String, String>> = _transcriptFlow

    // 화자 → 텍스트 (partial, 말하는 도중)
    private val _partialTranscriptFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val partialTranscriptFlow: SharedFlow<Pair<String, String>> = _partialTranscriptFlow

    // 원격 피싱 위험도
    val riskScoreFlow: SharedFlow<RiskScore> = scamDetector.riskState

    // 개입 이벤트 (UI/서비스로 전달)
    private val _interventionEvent = MutableSharedFlow<InterventionEvent>(extraBufferCapacity = 16)
    val interventionEvent: SharedFlow<InterventionEvent> = _interventionEvent

    init {
        // ── 경로 A: 원격 발화 분석 ─────────────────────────────────

        // 원격 Final STT → 피싱 감지 + 민감정보 요구 패턴 감지
        scope.launch {
            remoteSpeechRecognizer.transcript.collect { text ->
                _transcriptFlow.emit("REMOTE" to text)
                scamDetector.analyzeText(text)
                sensitiveDisclosureDetector.analyzeRemoteText(text)
            }
        }

        // 원격 Partial STT → 피싱 감지 + 민감정보 요구 패턴 감지 (실시간)
        scope.launch {
            remoteSpeechRecognizer.partialTranscript.collect { text ->
                _partialTranscriptFlow.emit("REMOTE" to text)
                scamDetector.analyzeText(text)
                sensitiveDisclosureDetector.analyzeRemoteText(text)
            }
        }

        // 피싱 위험도 → 개입 엔진
        scope.launch {
            scamDetector.riskState.collect { risk ->
                if (risk.level != RiskLevel.SAFE) {
                    interventionEngine.executeIntervention(risk.level)
                    _interventionEvent.emit(InterventionEvent.RemotePhishingDetected(risk))
                }
            }
        }

        // ── 경로 B: 로컬 발화 누출 감지 ──────────────────────────

        // 로컬 Final STT → 녹취록 + 누출 감지 + 피싱 감지
        // (루프백에서는 내 목소리 = 상대방 목소리, P2P에서도 대화 맥락 분석)
        scope.launch {
            localSpeechRecognizer.transcript.collect { text ->
                _transcriptFlow.emit("LOCAL" to text)
                localLeakDetector.analyze(text)   // 개인정보 누출 차단
                scamDetector.analyzeText(text)    // 피싱 키워드 위험도 계산
                sensitiveDisclosureDetector.analyzeLocalText(text)  // 요구 직후 숫자 발화 감지
            }
        }

        // 로컬 Partial STT → 누출 감지 + 피싱 감지 (말하는 도중 실시간)
        scope.launch {
            localSpeechRecognizer.partialTranscript.collect { text ->
                _partialTranscriptFlow.emit("LOCAL" to text)
                localLeakDetector.analyze(text)   // 개인정보 즉시 차단
                scamDetector.analyzeText(text)    // 피싱 맥락 실시간 분석
                sensitiveDisclosureDetector.analyzeLocalText(text)  // 요구 직후 숫자 발화 감지
            }
        }
    }

    // LocalLeakDetector 콜백에서 호출 → InterventionEvent 발행
    fun emitLocalLeakEvent(triggerPhrase: String, partialText: String) {
        scope.launch {
            _interventionEvent.emit(InterventionEvent.LocalLeakBlocked(triggerPhrase, partialText))
        }
    }

    fun onLocalAudioFrame(pcmData: ByteArray, sampleRate: Int, channels: Int) {
        localSpeechRecognizer.feedAudio(pcmData, sampleRate, channels)
    }

    fun onRemoteAudioFrame(pcmData: ByteArray, sampleRate: Int, channels: Int) {
        remoteSpeechRecognizer.feedAudio(pcmData, sampleRate, channels)
    }

    fun reset() {
        localSpeechRecognizer.reset()
        remoteSpeechRecognizer.reset()
        scamDetector.reset()
        localLeakDetector.reset()
        sensitiveDisclosureDetector.reset()
        interventionEngine.reset()
    }

    /** 서비스 완전 종료(onDestroy) 시 호출 — 내부 코루틴과 STT 자원을 해제한다. */
    fun release() {
        scope.cancel()
        localSpeechRecognizer.release()
        remoteSpeechRecognizer.release()
    }
}
