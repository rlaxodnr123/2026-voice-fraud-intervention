package com.example.callguard.domain.interfaces

import kotlinx.coroutines.flow.SharedFlow

enum class RiskLevel {
    SAFE,
    SUSPICIOUS,
    SCAM
}

data class RiskScore(
    val probability: Float,
    val level: RiskLevel,
    val matchedKeywords: List<String>
)

/**
 * 개입 이벤트 — 경고 팝업에 표시할 정보를 담는다.
 *
 * REMOTE_PHISHING  : 상대방 발화에서 피싱 패턴 감지 (경고 준비)
 * LOCAL_LEAK_BLOCKED: 내가 개인정보를 말하려는 순간 마이크 즉시 차단
 */
sealed class InterventionEvent {
    /** 상대방이 피싱 뉘앙스 발화 */
    data class RemotePhishingDetected(val riskScore: RiskScore) : InterventionEvent()

    /** 사용자 발화에서 개인정보 누출 감지 → 마이크 즉시 차단 */
    data class LocalLeakBlocked(
        val triggerPhrase: String,   // 감지된 민감 패턴 ("비밀번호", "인증번호" 등)
        val partialText: String      // 실제 감지된 발화 원문
    ) : InterventionEvent()
}

/**
 * SpeechRecognizer defines the interface to convert raw audio streams into text.
 */
interface SpeechRecognizer {
    val transcript: SharedFlow<String>
    val partialTranscript: SharedFlow<String>
    fun feedAudio(pcmData: ByteArray, sampleRate: Int, channels: Int)
    fun reset()
}

/**
 * ScamDetector analyzes text transcripts to assess risk.
 */
interface ScamDetector {
    val riskState: SharedFlow<RiskScore>
    fun analyzeText(text: String)
    fun reset()
}

/**
 * InterventionEngine coordinates UI overlays, mutes, or call terminations based on risk.
 */
interface InterventionEngine {
    fun executeIntervention(level: RiskLevel)
    fun reset()
}
