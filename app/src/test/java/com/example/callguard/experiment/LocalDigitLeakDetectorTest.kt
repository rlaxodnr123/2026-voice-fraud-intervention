package com.example.callguard.experiment

import com.example.callguard.experiment.domain.pipeline.LocalDigitLeakDetector
import com.example.callguard.experiment.domain.scenario.ScenarioCatalog
import com.example.callguard.experiment.domain.scenario.ScenarioController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 맥락 없는 연속 숫자 유출 탐지기 + 시나리오 발동 통합 검증.
 * 사용자 요청: 피해자가 트리거 문구 없이 숫자만 나열해도 즉시 개인정보 유출로 감지.
 */
class LocalDigitLeakDetectorTest {

    @Test
    fun 연속_3자리_이상이면_감지한다() {
        val hits = mutableListOf<String>()
        val d = LocalDigitLeakDetector { hits.add(it) }
        d.analyze("901231")
        assertEquals(listOf("901231"), hits)
    }

    @Test
    fun 공백으로_띄어_입력해도_하나의_숫자열로_본다() {
        val hits = mutableListOf<String>()
        val d = LocalDigitLeakDetector { hits.add(it) }
        d.analyze("9 0 1 2 3 1")   // 연구자가 띄어 입력 / STT가 공백 삽입
        assertEquals(listOf("901231"), hits)
    }

    @Test
    fun 하이픈_포함_계좌_전화번호도_감지한다() {
        val hits = mutableListOf<String>()
        val d = LocalDigitLeakDetector { hits.add(it) }
        d.analyze("110-452-123456")
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun 두자리_이하는_감지하지_않는다() {
        val hits = mutableListOf<String>()
        val d = LocalDigitLeakDetector { hits.add(it) }
        d.analyze("3시 30분에 봐요")   // 연속 2자리까지
        assertTrue(hits.isEmpty())
    }

    @Test
    fun 숫자없는_일반발화는_감지하지_않는다() {
        val hits = mutableListOf<String>()
        val d = LocalDigitLeakDetector { hits.add(it) }
        d.analyze("네 알겠습니다 그렇게 할게요")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun 세션당_1회만_발동하고_리셋후_재발동한다() {
        val hits = mutableListOf<String>()
        val d = LocalDigitLeakDetector { hits.add(it) }
        d.analyze("901231")
        d.analyze("456789")   // 리셋 전 두 번째는 무시(partial 반복 폭주 방지)
        assertEquals(1, hits.size)
        d.reset()
        d.analyze("456789")
        assertEquals(2, hits.size)
    }

    // ── 탐지기 → 시나리오 엔진 통합: 숫자만 나열해도 차단 시나리오 즉시 발동 ──

    @Test
    fun 숫자만_나열해도_차단시나리오_S5가_즉시_방어한다() {
        var localMuted = false
        val controller = ScenarioController(
            setLocalMuted = { localMuted = it },
            setRemoteMuted = {},
            speak = { _, _, _ -> },
            vibrateSOS = {},
            showTextBanner = { _, _ -> },
            startVoiceSurvey = {},
            startTextSurvey = {},
            stopSurveys = {},
            onLog = { _, _ -> },
            endCall = {},
            hideTextBanner = {}
        )
        val detector = LocalDigitLeakDetector { controller.onAutoDetectionTriggered("local_digits:$it") }
        controller.arm(ScenarioCatalog.findById("S5")!!)   // 내 마이크 차단 시나리오
        detector.analyze("901231 1234567")                 // 트리거 문구 없이 숫자만
        assertTrue("숫자만 나열해도 즉시 마이크 차단돼야 함", localMuted)
    }

    @Test
    fun 무장_안하면_숫자나열해도_아무일도_없다() {
        var fired: String? = null
        val controller = ScenarioController(
            setLocalMuted = {}, setRemoteMuted = {}, speak = { _, _, _ -> },
            vibrateSOS = {}, showTextBanner = { _, _ -> }, startVoiceSurvey = {},
            startTextSurvey = {}, stopSurveys = {},
            onLog = { e, _ -> if (e == "scenario_fired") fired = e }, endCall = {}, hideTextBanner = {}
        )
        val detector = LocalDigitLeakDetector { controller.onAutoDetectionTriggered("local_digits:$it") }
        detector.analyze("901231")
        assertNull("무장 안 하면 발동 안 함", fired)
    }
}
