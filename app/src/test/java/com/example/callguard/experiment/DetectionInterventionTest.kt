package com.example.callguard.experiment

import com.example.callguard.domain.pipeline.LocalLeakDetector
import com.example.callguard.domain.pipeline.SensitiveDisclosureDetector
import com.example.callguard.experiment.domain.scenario.ScenarioCatalog
import com.example.callguard.experiment.domain.scenario.ScenarioController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 탐지기 → 시나리오 엔진 → 개입(차단/음성/설문) 전체 체인 통합 테스트.
 *
 * ExperimentCallService가 실제로 배선하는 방식(LocalLeakDetector/SensitiveDisclosureDetector의
 * 콜백에서 scenarioController.onAutoDetectionTriggered 호출)을 그대로 재현한다.
 * 이 두 탐지기는 Android 프레임워크에 의존하지 않으므로(동기 콜백) 순수 JVM에서 검증 가능하다.
 *
 * 사용자가 보고한 "주민등록번호를 말했는데(주입했는데) 방어가 안 된다" 케이스를 자동 검증한다.
 */
class DetectionInterventionTest {

    private class Recorder {
        var localMuted = false
        var remoteMuted = false
        val spoken = mutableListOf<String>()
        var textSurveyStarted = false
        var voiceSurveyStarted = false
        val pending = mutableListOf<(() -> Unit)>()

        fun build(): ScenarioController = ScenarioController(
            setLocalMuted = { localMuted = it },
            setRemoteMuted = { remoteMuted = it },
            speak = { text, _, onDone -> spoken.add(text); if (onDone != null) pending.add(onDone) },
            vibrateSOS = {},
            showTextBanner = { _, _ -> },
            startVoiceSurvey = { voiceSurveyStarted = true },
            startTextSurvey = { textSurveyStarted = true },
            stopSurveys = {},
            onLog = { _, _ -> },
            endCall = {},
            hideTextBanner = {}
        )
    }

    /** ExperimentCallService.initComponents 와 동일한 배선을 만든다. */
    private fun wire(controller: ScenarioController): Pair<LocalLeakDetector, SensitiveDisclosureDetector> {
        val leak = LocalLeakDetector { phrase, _ ->
            controller.onAutoDetectionTriggered("local_leak:$phrase")
        }
        val disclosure = SensitiveDisclosureDetector { label, _ ->
            controller.onAutoDetectionTriggered("disclosure:$label")
        }
        return leak to disclosure
    }

    private fun scenario(id: String) = ScenarioCatalog.findById(id)!!

    // ── 사용자 보고 케이스: 주민등록번호 발화 → 차단 시나리오 발동 ──

    @Test
    fun 주민등록번호_발화시_마이크차단_시나리오_S5가_방어한다() {
        val r = Recorder(); val c = r.build(); val (leak, _) = wire(c)
        c.arm(scenario("S5"))                       // 내 마이크 차단 시나리오 무장
        leak.analyze("주민등록번호는 901231입니다")     // 참가자(주입) 발화
        assertTrue("주민등록번호 발화 시 마이크가 차단돼야 함", r.localMuted)
    }

    @Test
    fun 양방향차단_설문_S8이_개인정보_발화에_발동한다() {
        val r = Recorder(); val c = r.build(); val (leak, _) = wire(c)
        c.arm(scenario("S8"))
        leak.analyze("카드 비밀번호는 0000이에요")
        assertTrue(r.localMuted); assertTrue(r.remoteMuted)
        assertTrue("S8은 텍스트 설문을 시작해야 함", r.textSurveyStarted)
    }

    @Test
    fun 요구직후_숫자발화_콤보가_발동한다() {
        val r = Recorder(); val c = r.build(); val (_, disclosure) = wire(c)
        c.arm(scenario("S5"))
        disclosure.analyzeRemoteText("주민등록번호 뒤 7자리까지 말씀해 주세요")  // 공격자 요구
        disclosure.analyzeLocalText("901231 1234567")                    // 피해자 숫자 발화
        assertTrue("요구 직후 숫자 발화 시 차단돼야 함", r.localMuted)
    }

    // ── 비차단 시나리오는 '방어(차단)'가 아니라 음성만 (설계상 정상) ──

    @Test
    fun S1은_차단하지_않고_음성만_낸다() {
        val r = Recorder(); val c = r.build(); val (leak, _) = wire(c)
        c.arm(scenario("S1"))                       // 비차단 · AI 음성
        leak.analyze("주민등록번호는 901231입니다")
        assertFalse("S1은 차단하지 않음(설계상 정상)", r.localMuted)
        assertEquals("S1은 AI 음성을 내야 함", 1, r.spoken.size)
    }

    // ── 무장 안 했으면 아무 일도 없음 (사용자 혼동 지점) ──

    @Test
    fun 시나리오_무장_안하면_발화해도_아무일도_없다() {
        val r = Recorder(); val c = r.build(); val (leak, _) = wire(c)
        // arm() 호출 안 함
        leak.analyze("주민등록번호는 901231입니다")
        assertFalse(r.localMuted); assertFalse(r.remoteMuted)
        assertTrue(r.spoken.isEmpty())
    }

    // ── 같은 통화에서 재발동하려면 리셋(재무장) 필요 ──

    @Test
    fun 리셋없이_같은_유출을_재주입하면_재발동_안된다() {
        val r = Recorder(); val c = r.build(); val (leak, _) = wire(c)
        c.arm(scenario("S5"))
        leak.analyze("주민등록번호는 901231입니다")
        assertTrue(r.localMuted)
        // 세션 리셋 없이 다시 무장 → cleanup으로 뮤트 해제됨
        c.disarm()
        assertFalse(r.localMuted)
        // 같은 라벨은 detector가 alreadyTriggered로 억제 → 재발동 위해선 detector.reset() 필요
        leak.analyze("주민등록번호는 901231입니다")
        assertFalse("detector 리셋 전에는 같은 라벨 재발동 안 됨", r.localMuted)
        leak.reset()
        c.arm(scenario("S5"))
        leak.analyze("주민등록번호는 901231입니다")
        assertTrue("detector 리셋 + 재무장 후에는 재발동됨", r.localMuted)
    }
}
