package com.example.callguard.experiment

import com.example.callguard.experiment.domain.scenario.NoticeChannel
import com.example.callguard.experiment.domain.scenario.NoticeTiming
import com.example.callguard.experiment.domain.scenario.ScenarioCatalog
import com.example.callguard.experiment.domain.scenario.ScenarioConfig
import com.example.callguard.experiment.domain.scenario.ScenarioController
import com.example.callguard.experiment.domain.scenario.SurveyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시나리오 엔진 단위 테스트 — 9개 시나리오 각각의 발동 결과(뮤트/안내채널/설문/순서)를
 * 자동 검증한다. ScenarioController는 원자 동작을 전부 함수로 주입받으므로
 * Android 프레임워크 없이 순수 JVM에서 실행된다.
 */
class ScenarioControllerTest {

    /** 주입된 원자 동작 호출을 순서대로 기록하는 테스트 스파이 */
    private class Recorder {
        var localMuted = false
        var remoteMuted = false
        val calls = mutableListOf<String>()          // 시간순 호출 로그
        val spoken = mutableListOf<String>()
        var voiceSurveyStarted = false
        var textSurveyStarted = false
        var surveysStopped = 0
        var endCallCount = 0
        var bannerHiddenCount = 0
        var pendingVoiceOnDone: (() -> Unit)? = null // 음성 완료 콜백 보관 (BEFORE_BLOCK·S10)
        val loggedEvents = mutableListOf<Pair<String, Map<String, Any?>>>()

        fun build(): ScenarioController = ScenarioController(
            setLocalMuted = { m -> localMuted = m; calls.add("localMuted=$m") },
            setRemoteMuted = { m -> remoteMuted = m; calls.add("remoteMuted=$m") },
            speak = { text, flush, onDone ->
                spoken.add(text); calls.add("speak(flush=$flush)")
                pendingVoiceOnDone = onDone
            },
            vibrateSOS = { calls.add("vibrate") },
            showTextBanner = { msg, ack -> calls.add("banner(ack=$ack)") },
            startVoiceSurvey = { voiceSurveyStarted = true; calls.add("voiceSurvey") },
            startTextSurvey = { textSurveyStarted = true; calls.add("textSurvey") },
            stopSurveys = { surveysStopped++; calls.add("stopSurveys") },
            onLog = { e, d -> loggedEvents.add(e to d) },
            endCall = { endCallCount++; calls.add("endCall") },
            hideTextBanner = { bannerHiddenCount++; calls.add("hideBanner") }
        )
    }

    private fun scenario(id: String) = ScenarioCatalog.findById(id)!!

    // ── 카탈로그 sanity ──────────────────────────────────────────

    @Test
    fun catalog_has_ten_scenarios_with_unique_ids() {
        val ids = ScenarioCatalog.scenarios.map { it.id }
        assertEquals(10, ids.size)
        assertEquals(ids.size, ids.toSet().size) // 중복 없음
        assertEquals(listOf("S1","S2","S3","S4","S5","S6","S7","S8","S9","S10"), ids)
    }

    // ── 시나리오별 발동 결과 (§6 매핑 표) ────────────────────────

    @Test
    fun s1_nonblocking_voice_only() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S1")); c.onManualTrigger()
        assertFalse(r.localMuted); assertFalse(r.remoteMuted)
        assertEquals(1, r.spoken.size)
        assertFalse(r.voiceSurveyStarted); assertFalse(r.textSurveyStarted)
    }

    @Test
    fun s2_nonblocking_vibration_only() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S2")); c.onManualTrigger()
        assertFalse(r.localMuted); assertFalse(r.remoteMuted)
        assertTrue(r.calls.contains("vibrate"))
        assertTrue(r.spoken.isEmpty())
    }

    @Test
    fun s3_blocks_remote_then_voice() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S3")); c.onManualTrigger()
        assertFalse(r.localMuted); assertTrue(r.remoteMuted)
        // AFTER_BLOCK: 차단이 안내보다 먼저
        assertTrue(r.calls.indexOf("remoteMuted=true") < r.calls.indexOf("speak(flush=true)"))
    }

    @Test
    fun s4_blocks_both_then_voice() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S4")); c.onManualTrigger()
        assertTrue(r.localMuted); assertTrue(r.remoteMuted)
        assertEquals(1, r.spoken.size)
        assertFalse(r.voiceSurveyStarted)
    }

    @Test
    fun s5_silent_block_local_only_no_notice() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S5")); c.onManualTrigger()
        assertTrue(r.localMuted); assertFalse(r.remoteMuted)
        assertTrue(r.spoken.isEmpty())
        assertFalse(r.calls.contains("vibrate"))
        assertFalse(r.calls.any { it.startsWith("banner") })
    }

    @Test
    fun s6_voice_notice_BEFORE_block_order() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S6")); c.onManualTrigger()
        // 음성 안내가 끝나기 전에는 아직 차단되지 않아야 한다
        assertEquals(1, r.spoken.size)
        assertFalse("음성 완료 전 차단되면 안 됨", r.localMuted)
        // 음성 완료 콜백 실행 → 그제서야 차단
        r.pendingVoiceOnDone?.invoke()
        assertTrue(r.localMuted)
        assertFalse(r.remoteMuted)
    }

    @Test
    fun s7_block_local_with_text_banner() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S7")); c.onManualTrigger()
        assertTrue(r.localMuted); assertFalse(r.remoteMuted)
        assertTrue(r.calls.any { it.startsWith("banner") })
        assertTrue(r.spoken.isEmpty())
    }

    @Test
    fun s8_text_banner_BEFORE_block_then_text_survey() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S8")); c.onManualTrigger()
        // 비음성 사전 안내(텍스트)는 즉시 표시 후 차단
        assertTrue(r.calls.any { it.startsWith("banner") })
        assertTrue(r.localMuted); assertTrue(r.remoteMuted)
        assertTrue(r.textSurveyStarted)
        assertFalse(r.voiceSurveyStarted)
        // 배너가 차단보다 먼저
        assertTrue(r.calls.indexOfFirst { it.startsWith("banner") } < r.calls.indexOf("localMuted=true"))
    }

    @Test
    fun s9_blocks_both_then_voice_survey() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S9")); c.onManualTrigger()
        assertTrue(r.localMuted); assertTrue(r.remoteMuted)
        assertTrue(r.voiceSurveyStarted)
        // AFTER_BLOCK 리드인 음성이 재생됨
        assertEquals(1, r.spoken.size)
    }

    @Test
    fun s10_blocks_both_then_voice_then_ends_call_after_tts() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S10")); c.onManualTrigger()
        // 차단 → 안내 재생, 안내가 끝나기 전에는 통화가 끊기면 안 된다
        assertTrue(r.localMuted); assertTrue(r.remoteMuted)
        assertEquals(1, r.spoken.size)
        assertEquals("안내 완료 전 통화 종료되면 안 됨", 0, r.endCallCount)
        // 음성 완료 콜백 → 그제서야 통화 종료
        r.pendingVoiceOnDone?.invoke()
        assertEquals(1, r.endCallCount)
    }

    @Test
    fun s10_does_not_end_call_if_disarmed_before_tts_done() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S10")); c.onManualTrigger()
        c.disarm() // 안내 재생 중 연구자가 세션 리셋
        r.pendingVoiceOnDone?.invoke()
        assertEquals("리셋 후 뒤늦은 콜백이 통화를 끊으면 안 됨", 0, r.endCallCount)
    }

    // ── fire 공유 / 가드 로직 (§4.3) ─────────────────────────────

    @Test
    fun auto_and_manual_trigger_produce_identical_result() {
        val auto = Recorder(); auto.build().also { it.arm(scenario("S4")); it.onAutoDetectionTriggered("kw") }
        val manual = Recorder(); manual.build().also { it.arm(scenario("S4")); it.onManualTrigger() }
        assertEquals(auto.localMuted, manual.localMuted)
        assertEquals(auto.remoteMuted, manual.remoteMuted)
        assertEquals(auto.spoken, manual.spoken)
    }

    @Test
    fun fires_only_once_per_session() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S4"))
        c.onManualTrigger()
        c.onManualTrigger() // 두 번째는 무시돼야 함
        c.onAutoDetectionTriggered("kw")
        assertEquals(1, r.spoken.size)
        assertEquals(1, r.loggedEvents.count { it.first == "scenario_fired" })
    }

    @Test
    fun trigger_without_arm_is_ignored() {
        val r = Recorder(); val c = r.build()
        c.onManualTrigger()
        assertFalse(r.localMuted); assertFalse(r.remoteMuted)
        assertTrue(r.spoken.isEmpty())
        assertTrue(r.loggedEvents.none { it.first == "scenario_fired" })
    }

    @Test
    fun logs_source_auto_vs_manual() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S1")); c.onAutoDetectionTriggered("kw")
        val fired = r.loggedEvents.first { it.first == "scenario_fired" }.second
        assertEquals("auto", fired["source"])

        val r2 = Recorder(); val c2 = r2.build()
        c2.arm(scenario("S1")); c2.onManualTrigger()
        val fired2 = r2.loggedEvents.first { it.first == "scenario_fired" }.second
        assertEquals("manual", fired2["source"])
    }

    // ── disarm / 재무장 상태 정리 (§9) ───────────────────────────

    @Test
    fun disarm_unmutes_and_stops_surveys() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S9")); c.onManualTrigger()
        assertTrue(r.localMuted && r.remoteMuted)
        c.disarm()
        assertFalse(r.localMuted); assertFalse(r.remoteMuted)
        assertTrue(r.surveysStopped >= 1)
        assertNull(c.armedScenario.value)
        assertFalse(c.fired.value)
    }

    @Test
    fun s6_late_tts_callback_after_disarm_does_not_reapply_block() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S6")); c.onManualTrigger()
        assertFalse(r.localMuted) // 안내 재생 중 — 아직 차단 전
        c.disarm()                // TTS가 끝나기 전에 연구자가 세션 리셋
        r.pendingVoiceOnDone?.invoke() // 뒤늦게 도착한 음성 완료 콜백
        assertFalse("리셋 후 뒤늦은 콜백이 차단을 되살리면 안 됨", r.localMuted)
    }

    @Test
    fun s6_late_tts_callback_after_rearm_does_not_apply_previous_block() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S6")); c.onManualTrigger()
        c.arm(scenario("S1")) // TTS 완료 전 재무장 (비차단 시나리오)
        r.pendingVoiceOnDone?.invoke()
        assertFalse("재무장 후 이전 시나리오의 차단이 적용되면 안 됨", r.localMuted)
    }

    @Test
    fun rearming_clears_previous_text_banner() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S7")); c.onManualTrigger() // 배너 표시 + 마이크 차단
        c.arm(scenario("S1"))                       // 리셋 없이 곧바로 재무장
        assertTrue("재무장 시 이전 배너가 정리돼야 함", r.bannerHiddenCount >= 1)
    }

    @Test
    fun rearming_after_fire_clears_previous_mute() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S4")); c.onManualTrigger()  // 양쪽 차단됨
        assertTrue(r.localMuted && r.remoteMuted)
        // 리셋 없이 곧바로 비차단 시나리오 무장 → 이전 뮤트가 남지 않아야 함
        c.arm(scenario("S1"))
        assertFalse("재무장 시 이전 뮤트 잔존하면 안 됨", r.localMuted)
        assertFalse(r.remoteMuted)
        assertFalse(c.fired.value)
    }

    @Test
    fun rearming_allows_second_scenario_to_fire() {
        val r = Recorder(); val c = r.build()
        c.arm(scenario("S1")); c.onManualTrigger()
        c.arm(scenario("S4")); c.onManualTrigger()
        // 두 시나리오 모두 발동 로그가 남아야 함
        assertEquals(2, r.loggedEvents.count { it.first == "scenario_fired" })
        assertTrue(r.localMuted) // S4 결과
    }

    // ── 런타임 오버라이드 대비: config 자체 검증 ─────────────────

    @Test
    fun every_scenario_config_is_internally_consistent() {
        ScenarioCatalog.scenarios.forEach { s ->
            // 설문이 있으면 최소한 차단이 동반돼야 실험 논리상 자연스럽다 (S8/S9)
            if (s.surveyType != SurveyType.NONE) {
                assertTrue("${s.id}: 설문 시나리오는 차단을 동반해야 함",
                    s.blockLocalMic || s.blockRemoteAudio)
            }
            // VOICE_TTS/TEXT_BANNER 채널이면 안내 메시지가 비어있지 않아야 함
            if (NoticeChannel.VOICE_TTS in s.noticeChannels ||
                NoticeChannel.TEXT_BANNER in s.noticeChannels) {
                assertTrue("${s.id}: 안내 채널이 있으면 메시지 필요", s.noticeMessage.isNotBlank())
            }
            // NoticeTiming.NONE이면 안내 채널이 없어야 함 (S5)
            if (s.noticeTiming == NoticeTiming.NONE) {
                assertTrue("${s.id}: NONE 타이밍인데 안내 채널 존재", s.noticeChannels.isEmpty())
            }
            // 통화 강제 종료 시나리오는 설문을 가질 수 없다 (종료되면 설문 진행 불가, S10)
            if (s.terminateCall) {
                assertEquals("${s.id}: 통화 종료 시나리오는 설문 불가", SurveyType.NONE, s.surveyType)
            }
        }
    }

    /**
     * 10개 시나리오 전수 검증 — 각 시나리오의 카탈로그 설정(차단/안내채널/설문/종료)과
     * 실제 발동 결과가 정확히 일치하는지 데이터 기반으로 확인한다.
     * (BEFORE_BLOCK 음성·통화종료 시나리오는 음성 완료 콜백까지 실행해 최종 상태로 만든다.)
     */
    @Test
    fun all_ten_scenarios_execute_exactly_per_config() {
        ScenarioCatalog.scenarios.forEach { cfg ->
            val r = Recorder(); val c = r.build()
            c.arm(cfg)
            c.onManualTrigger()
            // BEFORE_BLOCK 음성(S6)·통화종료 음성(S10)은 안내 완료 후에 차단/종료가 일어난다
            r.pendingVoiceOnDone?.invoke()

            assertEquals("${cfg.id} 로컬 차단", cfg.blockLocalMic, r.localMuted)
            assertEquals("${cfg.id} 원격 차단", cfg.blockRemoteAudio, r.remoteMuted)
            assertEquals("${cfg.id} 진동",
                NoticeChannel.VIBRATION in cfg.noticeChannels, r.calls.contains("vibrate"))
            assertEquals("${cfg.id} 텍스트 배너",
                NoticeChannel.TEXT_BANNER in cfg.noticeChannels, r.calls.any { it.startsWith("banner") })
            assertEquals("${cfg.id} 음성 안내",
                NoticeChannel.VOICE_TTS in cfg.noticeChannels, r.spoken.isNotEmpty())
            assertEquals("${cfg.id} 음성 설문",
                cfg.surveyType == SurveyType.VOICE, r.voiceSurveyStarted)
            assertEquals("${cfg.id} 텍스트 설문",
                cfg.surveyType == SurveyType.TEXT, r.textSurveyStarted)
            assertEquals("${cfg.id} 통화 강제 종료",
                cfg.terminateCall, r.endCallCount > 0)
        }
    }

    @Test
    fun scenario_snapshot_map_roundtrips_key_fields() {
        val s = scenario("S8")
        val map = s.toLogMap()
        assertEquals("S8", map["id"])
        assertEquals(true, map["blockLocalMic"])
        assertEquals(true, map["blockRemoteAudio"])
        assertEquals("TEXT", map["surveyType"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((map["noticeChannels"] as List<String>).contains("TEXT_BANNER"))
    }
}
