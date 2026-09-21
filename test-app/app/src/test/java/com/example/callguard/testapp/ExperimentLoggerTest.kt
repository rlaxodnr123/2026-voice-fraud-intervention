package com.example.callguard.testapp

import com.example.callguard.testapp.domain.log.ExperimentLogger
import com.example.callguard.testapp.domain.log.SessionMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ExperimentLoggerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun meta(
        mode: String = "AUTO_TTS",
        intervention: String = "POPUP_TTS"
    ) = SessionMeta(
        participantId = "P07",
        trialOrder = 2,
        interventionId = intervention,
        interventionLabel = "1. 팝업 + TTS",
        scriptId = "S1",
        scriptLabel = "진짜 사기 · 지인",
        scamLevel = "REAL_SCAM",
        relationship = "ACQUAINTANCE",
        callerDisplayed = "김민수",
        playbackMode = mode,
        startedAt = System.currentTimeMillis()
    )

    private fun summaryOf(dir: File): JSONObject =
        JSONObject(File(dir, "session.json").readText()).getJSONObject("summary")

    @Test
    fun `세션 폴더 이름에 조건이 모두 들어간다`() {
        // 폴더 이름만 보고 어떤 참가자의 몇 번째 어떤 조건인지 알 수 있어야
        // 수십 개 폴더를 PC로 옮긴 뒤에도 정리가 가능하다
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())
        logger.finish("test")
        assertTrue(dir.name.contains("P07"))
        assertTrue(dir.name.contains("T2"))
        assertTrue(dir.name.contains("S1"))
        assertTrue(dir.name.contains("POPUP_TTS"))
    }

    @Test
    fun `요약은 조건 두 축을 그대로 싣는다`() {
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())
        logger.finish("test")

        val s = summaryOf(dir)
        assertEquals("REAL_SCAM", s.getString("scamLevel"))
        assertEquals("ACQUAINTANCE", s.getString("relationship"))
    }

    @Test
    fun `반응시간은 발화 개시 시점으로 잰다`() {
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())

        logger.log("intervention_point_reached")
        Thread.sleep(20)
        logger.log("intervention_fired", mapOf("source" to "auto"))
        Thread.sleep(30)
        logger.log("participant_speech_onset", mapOf("delayMs" to 30))
        Thread.sleep(50)
        // 최종 인식은 발화가 끝나고 무음이 이어져야 나온다 — 이것을 반응시각으로 쓰면
        // 반응시간에 발화 길이와 무음 대기가 통째로 더해진다
        logger.log("participant_speech", mapOf("text" to "네?"))
        logger.finish("researcher_finished")

        val s = summaryOf(dir)
        assertEquals("participant_speech_onset", s.getString("firstReactionType"))
        assertTrue(s.getLong("reactionTimeMs") >= 20)
        assertTrue(s.getLong("reactionTimeMs") < 80)
    }

    @Test
    fun `되들어온 음성과 연구자 수동 입력은 반응시간에 들어가지 않는다`() {
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())

        logger.log("intervention_fired", mapOf("source" to "auto"))
        // 경고 음성이 마이크로 되들어온 구간 — 참가자 발화가 아니다
        logger.log("participant_speech_gated", mapOf("text" to "보이스피싱이 의심되는 통화입니다"))
        // 연구자가 타이핑을 끝낸 시각은 참가자가 말한 시각이 아니다
        logger.log("participant_speech_manual", mapOf("text" to "네"))
        logger.finish("researcher_finished")

        val s = summaryOf(dir)
        assertTrue(s.isNull("reactionTimeMs"))
        assertEquals(1, s.getInt("gatedSpeechCount"))
    }

    @Test
    fun `팝업에서 계속하기를 고른 것도 반응으로 잡는다`() {
        // 말없이 버튼만 누른 참가자가 무응답으로 기록되면 안 된다
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())

        logger.log("intervention_fired", mapOf("source" to "auto"))
        Thread.sleep(20)
        logger.log("participant_chose_continue", mapOf("sinceInterventionMs" to 20))
        logger.finish("researcher_finished")

        val s = summaryOf(dir)
        assertEquals("participant_chose_continue", s.getString("firstReactionType"))
        assertTrue(s.getBoolean("choseContinue"))
        assertTrue(s.getLong("continueDecisionMs") >= 20)
    }

    @Test
    fun `유출 이벤트가 요약에 반영된다`() {
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())

        logger.log("intervention_point_reached")
        logger.log("intervention_fired", mapOf("source" to "manual"))
        logger.log("leak_detected", mapOf("source" to "stt", "confidence" to "CONFIRMED", "matched" to "901231"))
        logger.finish("researcher_finished")

        val s = summaryOf(dir)
        assertTrue(s.getBoolean("leaked"))
        assertEquals("CONFIRMED", s.getString("leakConfidence"))
        assertEquals("stt", s.getString("leakSource"))
        // 개입 이후에 유출됐다 = 개입이 막지 못했다. 이 구분이 주 지표의 핵심이다.
        assertTrue(s.getBoolean("leakAfterIntervention"))
    }

    @Test
    fun `누가 통화를 끊었는지 요약에 남는다`() {
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta(intervention = "FORCE_TERMINATE"))

        logger.log("intervention_fired", mapOf("source" to "auto"))
        Thread.sleep(20)
        logger.log("call_ended_by_app", mapOf("sinceInterventionMs" to 20))
        Thread.sleep(30)
        logger.finish("researcher_finished")

        val s = summaryOf(dir)
        assertEquals("app", s.getString("callEndedBy"))
        assertTrue(s.getLong("interventionToCallEndMs") >= 20)
        // 통화 길이와 세션 길이는 다르다 — 세션에는 연구자가 패널을 만진 시간이 포함된다
        assertTrue(s.getLong("sessionDurationMs") >= s.getLong("callDurationMs"))
    }

    @Test
    fun `통화가 끝나지 않은 세션은 none으로 남는다`() {
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())
        logger.log("intervention_fired", mapOf("source" to "auto"))
        logger.finish("researcher_finished")

        val s = summaryOf(dir)
        assertEquals("none", s.getString("callEndedBy"))
        assertTrue(s.isNull("callDurationMs"))
    }

    @Test
    fun `라이브 모드는 트리거 지연이 통제되지 않았다고 표시한다`() {
        val auto = ExperimentLogger(temp.root).let { l ->
            val d = l.start(meta(mode = "AUTO_TTS")); l.finish("t"); summaryOf(d)
        }
        val live = ExperimentLogger(temp.root).let { l ->
            val d = l.start(meta(mode = "LIVE")); l.finish("t"); summaryOf(d)
        }
        assertTrue(auto.getBoolean("triggerLatencyControlled"))
        assertFalse(live.getBoolean("triggerLatencyControlled"))
    }

    @Test
    fun `개입 지점 대사가 뜬 시각과의 차이를 기록한다`() {
        // 라이브 모드에서 연구자가 대사를 읽고 버튼을 누르기까지의 사람 지연을 보려면
        // 대사가 화면에 뜬 시각이 필요하다
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta(mode = "LIVE"))

        logger.log("attacker_line_start", mapOf("index" to 6, "isInterventionPoint" to true, "live" to true))
        Thread.sleep(40)
        logger.log("intervention_point_reached", mapOf("liveManualAdvance" to true))
        logger.log("intervention_fired", mapOf("source" to "auto"))
        logger.finish("researcher_finished")

        val s = summaryOf(dir)
        assertTrue(s.getLong("lineShownToInterventionMs") >= 40)
        assertNotEquals(s.getLong("lineShownToInterventionMs"), s.optLong("pointToInterventionMs"))
    }

    @Test
    fun `관찰 메모가 요약에 모인다`() {
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())
        logger.log("observation", mapOf("note" to "말을 멈춤"))
        logger.log("observation", mapOf("note" to "직접 통화를 끊음"))
        logger.finish("researcher_finished")

        val notes = summaryOf(dir).getJSONArray("observationNotes")
        assertEquals(2, notes.length())
        assertEquals("말을 멈춤", notes.getString(0))
    }

    @Test
    fun `여러 스레드가 동시에 기록해도 깨지지 않는다`() {
        // 실제로는 마이크 워커 스레드·TTS 콜백 스레드·메인 스레드가 동시에 log()를 부른다.
        // 시각 포맷터를 공유하면 여기서 문자열이 뒤섞이거나 예외가 난다.
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)

        repeat(4) { t ->
            thread {
                start.await()
                repeat(50) { i -> logger.log("participant_speech", mapOf("text" to ("t" + t + "-" + i))) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        logger.finish("researcher_finished")

        val root = JSONObject(File(dir, "session.json").readText())
        val events = root.getJSONArray("events")
        // session_started + 200건 + session_finished
        assertEquals(202, events.length())
        for (i in 0 until events.length()) {
            val time = events.getJSONObject(i).getString("time")
            assertTrue("시각 문자열이 깨졌습니다: " + time, Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}$").matches(time))
        }
    }

    @Test
    fun `종료 전에도 파일이 온전한 JSON으로 남는다`() {
        // 앱이 중간에 죽어도 그 시점까지의 기록을 살릴 수 있어야 한다
        val logger = ExperimentLogger(temp.root)
        val dir = logger.start(meta())
        logger.log("intervention_point_reached")
        logger.finish("researcher_finished")

        val root = JSONObject(File(dir, "session.json").readText())
        assertEquals("P07", root.getJSONObject("meta").getString("participantId"))
        assertTrue(root.getJSONArray("events").length() >= 2)
    }
}
