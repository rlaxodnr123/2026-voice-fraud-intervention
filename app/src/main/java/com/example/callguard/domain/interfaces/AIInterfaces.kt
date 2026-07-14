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
    /** 내 발화에서 개인정보 누출 패턴 감지, 또는 공격자 요구 직후 숫자 발화 감지 → 양쪽 음성 차단 */
    LOCAL_LEAK,
    /** 상대 발화에서 피싱 위험 SCAM 판정 → 내 마이크 + 상대 음성 모두 차단 */
    REMOTE_PHISHING,
    /** (레거시) 과거 SUSPICIOUS 단계 차단 사유. 현재는 SUSPICIOUS를 경고만 하도록 완화해 더 이상 발생하지 않지만,
     *  기존 로그·admin-dashboard 표시 호환을 위해 값만 유지한다. */
    SUSPECTED_REMOTE
}

/**
 * 개입 이벤트 — 경고 팝업에 표시할 정보를 담는다.
 *
 * REMOTE_PHISHING_DETECTED: 상대방 발화에서 피싱 뉘앙스(SUSPICIOUS) 감지, 차단 없이 경고만 표시
 * REMOTE_PHISHING_BLOCKED : 상대방 발화 SCAM 확정 → 내 마이크+상대 음성 차단 후 설문 시작
 * LOCAL_LEAK_BLOCKED      : 내가 개인정보를 말하려는 순간, 또는 공격자 요구 직후 숫자를 발화하는 순간
 *                           마이크 즉시 차단 후 설문 시작
 */
sealed class InterventionEvent {
    /** 상대방이 피싱 뉘앙스 발화 (SUSPICIOUS) → 차단 없이 경고만 */
    data class RemotePhishingDetected(val riskScore: RiskScore) : InterventionEvent()

    /** 상대방 발화가 SCAM으로 확정 → 마이크 + 상대 음성 차단 후 설문 시작. */
    data class RemotePhishingBlocked(
        val riskScore: RiskScore,
        val remoteAlsoMuted: Boolean = true
    ) : InterventionEvent()

    /** 사용자 발화에서 개인정보 누출 감지(직접 발화 또는 요구 직후 숫자 발화) → 마이크 즉시 차단 */
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
    /** 내부 코루틴 스코프·네이티브 자원을 해제한다. 통화 종료 시 호출. */
    fun release()
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
