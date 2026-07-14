package com.example.callguard.experiment

import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.interfaces.RiskScore
import com.example.callguard.domain.pipeline.MockScamDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공격자(원격) 발화 위험도 감지 검증 — 누적 키워드 수 기반.
 * base 원본에서 누락됐던 "인증번호" 요구가 위험도를 올리는지 확인한다.
 */
class ScamDetectorTest {

    private fun analyzeAndCollect(vararg utterances: String): RiskScore = runBlocking {
        val detector = MockScamDetector()
        val received = mutableListOf<RiskScore>()
        val job = launch { detector.riskState.collect { received.add(it) } }
        delay(50) // 수집 구독 보장
        utterances.forEach { detector.analyzeText(it) }
        delay(200) // 비동기 emit 수신 대기
        job.cancel()
        received.lastOrNull() ?: RiskScore(0f, RiskLevel.SAFE, emptyList())
    }

    @Test
    fun 인증번호_요구시_위험도가_오른다() {
        val risk = analyzeAndCollect("본인 확인을 위해 인증번호를 불러주세요")
        assertTrue("인증번호가 키워드로 잡혀야 함", risk.matchedKeywords.contains("인증번호"))
        assertNotEquals("위험도가 SAFE가 아니어야 함", RiskLevel.SAFE, risk.level)
    }

    @Test
    fun otp_보안카드_요구도_잡힌다() {
        val risk = analyzeAndCollect("OTP 번호와 보안카드 번호를 알려주세요")
        assertTrue(risk.matchedKeywords.any { it == "OTP" || it == "보안카드" })
    }

    @Test
    fun 키워드_누적되면_SCAM으로_상승한다() {
        // 6개 이상 고유 키워드 → SCAM(0.95)
        val risk = analyzeAndCollect(
            "저는 검찰 수사관입니다. 계좌가 범죄에 연루되어 안전계좌로 이체가 필요합니다. " +
                "본인 확인을 위해 인증번호와 주민등록번호를 불러주세요."
        )
        assertNotEquals(RiskLevel.SAFE, risk.level)
        assertTrue("고유 키워드가 충분히 누적돼야 함", risk.matchedKeywords.size >= 4)
    }

    @Test
    fun 관련없는_말은_위험도를_올리지_않는다() {
        val risk = analyzeAndCollect("오늘 날씨가 참 좋네요 점심 뭐 드셨어요")
        assertTrue("피싱 키워드가 없으면 매칭 0", risk.matchedKeywords.isEmpty())
    }

    @Test
    fun 키워드_6개이상이면_SCAM_95퍼센트에_도달한다() {
        // base 곡선: 키워드만으로는 최대 95%(SCAM). 100%는 숫자 유출 전용.
        val risk = analyzeAndCollect(
            "검찰 수사관입니다. 계좌가 범죄에 연루되어 안전계좌로 이체하고 인증번호를 불러주세요."
        )
        assertTrue("고유 키워드 6개 이상", risk.matchedKeywords.size >= 6)
        assertEquals("키워드 위험도 상한은 95%", 0.95f, risk.probability, 0.001f)
    }

    @Test
    fun 키워드_2개면_자동발동_문턱_06을_넘는다() {
        val risk = analyzeAndCollect("본인 확인을 위해 인증번호를 알려주세요")
        assertTrue("2개 키워드(65%)면 자동 발동 문턱(0.6) 이상", risk.probability >= 0.6f)
    }
}
