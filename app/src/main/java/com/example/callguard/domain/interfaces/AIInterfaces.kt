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
 * 마이크/원격 음성 차단을 일으킨 원인.
 * 설문 완료 후 어떤 자원을 해제해야 하는지 판단하는 데 쓰인다.
 */
enum class BlockReason {
    /** 내 발화에서 개인정보 누출 패턴 감지 → 내 마이크만 차단 */
    LOCAL_LEAK,
    /** 상대 발화에서 피싱 위험 SCAM 판정 → 내 마이크 + 상대 음성 모두 차단 */
    REMOTE_PHISHING,
    /** 상대 발화에서 피싱 의심(SUSPICIOUS) 감지 → 내 마이크만 차단 (상대 음성은 그대로 들림) */
    SUSPECTED_REMOTE
}

/**
 * 개입 이벤트 — 경고 팝업에 표시할 정보를 담는다.
 *
 * REMOTE_PHISHING_DETECTED: 상대방 발화에서 피싱 뉘앙스(SUSPICIOUS) 감지, 경고만 표시
 * REMOTE_PHISHING_BLOCKED : 상대방 발화 SCAM 확정 → 내 마이크+상대 음성 차단 후 설문 시작
 * LOCAL_LEAK_BLOCKED      : 내가 개인정보를 말하려는 순간 마이크 즉시 차단 후 설문 시작
 */
sealed class InterventionEvent {
    /** 상대방이 피싱 뉘앙스 발화 (SUSPICIOUS, 경고만) */
    data class RemotePhishingDetected(val riskScore: RiskScore) : InterventionEvent()

    /**
     * 상대방 발화에서 피싱 위험(SUSPICIOUS 또는 SCAM) 감지 → 마이크 차단 + 설문 시작.
     * @param remoteAlsoMuted true면 SCAM 확정으로 상대 음성까지 차단됨, false면 SUSPICIOUS 의심 단계라 상대 음성은 그대로 들림
     */
    data class RemotePhishingBlocked(
        val riskScore: RiskScore,
        val remoteAlsoMuted: Boolean = true
    ) : InterventionEvent()

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
