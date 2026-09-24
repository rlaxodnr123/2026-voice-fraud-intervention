package com.example.callguard.testapp

import com.example.callguard.testapp.domain.intervention.InterventionCatalog
import com.example.callguard.testapp.domain.intervention.InterventionController
import com.example.callguard.testapp.domain.intervention.InterventionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개입 실행기 검증.
 *
 * 실험 결과의 타당성이 두 가지에 걸려 있으므로 그것을 테스트로 고정한다:
 *  ① 두 조건이 선택권 유무만 빼고 똑같이 동작한다
 *  ② 개입은 **처음부터 끝까지 한 화면**이다 (안내 중·후에 화면이 바뀌지 않는다)
 */
class InterventionControllerTest {

    private class Recorder {
        val remoteBlocked = mutableListOf<Boolean>()
        val micBlocked = mutableListOf<Boolean>()
        var vibrateCount = 0
        var toneCount = 0
        val spoken = mutableListOf<String>()

        /** 개입 화면 표시 이력: (문구, 선택 제공 여부) */
        val screens = mutableListOf<Pair<String, Boolean>>()
        var choiceEnabledCount = 0
        var hideCount = 0

        var endCallCount = 0
        val events = mutableListOf<String>()
        val delays = mutableListOf<Long>()

        /** TTS 완료 콜백을 즉시 부를지 — false면 안내가 아직 끝나지 않은 상태를 흉내 낸다 */
        var autoCompleteTts = true
        var pendingTtsDone: (() -> Unit)? = null

        fun controller(now: () -> Long = { 1_000L }) = InterventionController(
            setRemoteAudioBlocked = { remoteBlocked.add(it) },
            setMicBlocked = { micBlocked.add(it) },
            vibrate = { vibrateCount++ },
            playWarningTone = { toneCount++ },
            speak = { text, onDone ->
                spoken.add(text)
                if (autoCompleteTts) onDone?.invoke() else pendingTtsDone = onDone
            },
            showInterventionScreen = { msg, offer -> screens.add(msg to offer) },
            enableChoice = { choiceEnabledCount++ },
            hideInterventionScreen = { hideCount++ },
            endCall = { endCallCount++ },
            onLog = { event, _ -> events.add(event) },
            postDelayed = { delayMs, action -> delays.add(delayMs); action() },
            now = now
        )
    }

    private fun armed(id: InterventionId, configure: Recorder.() -> Unit = {}):
        Pair<Recorder, InterventionController> {
        val r = Recorder().apply(configure)
        val c = r.controller()
        c.arm(InterventionCatalog.findById(id))
        return r to c
    }

    private fun fired(id: InterventionId, configure: Recorder.() -> Unit = {}):
        Pair<Recorder, InterventionController> {
        val (r, c) = armed(id, configure)
        c.fireAuto("point")
        return r to c
    }

    // ── 한 화면 유지 ─────────────────────────────────────────

    @Test
    fun `개입 화면은 안내보다 먼저 뜨고 안내가 끝나도 그대로다`() {
        // 안내 중과 안내 후에 다른 화면을 띄우면 참가자가 화면 전환 자체에 반응하게 된다
        val (r, c) = armed(InterventionId.POPUP_TTS) { autoCompleteTts = false }
        c.fireAuto("point")

        assertEquals("화면이 즉시 떠야 한다", 1, r.screens.size)
        assertEquals(1, r.spoken.size)

        r.pendingTtsDone?.invoke()

        // 화면을 새로 띄우지도, 걷어내지도 않는다
        assertEquals(1, r.screens.size)
        assertEquals(0, r.hideCount)
    }

    @Test
    fun `화면에 띄운 문장과 읽어 주는 문장이 같다`() {
        // 귀로 들은 것과 눈으로 본 것이 다르면 무엇을 근거로 판단했는지 알 수 없다
        listOf(InterventionId.POPUP_TTS, InterventionId.FORCE_TERMINATE).forEach { id ->
            val (r, _) = fired(id)
            assertEquals(id.name, r.screens.first().first, r.spoken.first())
        }
    }

    @Test
    fun `안내 문구는 확정된 두 가지뿐이다`() {
        val (a, _) = fired(InterventionId.POPUP_TTS)
        val (b, _) = fired(InterventionId.FORCE_TERMINATE)

        assertEquals(
            "보이스피싱으로 의심되어 통화가 잠시 중단되었습니다. " +
                "상대방과 나의 음성이 서로 차단되어 전달되지 않습니다. 통화를 이어가시겠습니까?",
            a.spoken.first()
        )
        assertEquals("보이스피싱으로 의심되어 통화가 종료되었습니다.", b.spoken.first())
    }

    // ── 개입 1 ───────────────────────────────────────────────

    @Test
    fun `개입 1은 스피커 마이크 차단 진동 경고음과 함께 선택 화면을 띄우고 통화를 끊지 않는다`() {
        val (r, c) = fired(InterventionId.POPUP_TTS)

        assertEquals(listOf(true), r.remoteBlocked)
        assertEquals(listOf(true), r.micBlocked)
        assertEquals(1, r.vibrateCount)
        assertEquals(1, r.toneCount)
        assertEquals(1, r.screens.size)
        assertTrue("선택지를 제공해야 한다", r.screens.first().second)
        assertEquals(0, r.endCallCount)
        assertTrue(c.fired)
        assertEquals(1_000L, c.firedAt)
    }

    @Test
    fun `선택 버튼은 안내가 끝나야 활성화된다`() {
        // 안내를 다 듣기 전에 고를 수 있으면 "듣고 판단한다"는 조건이 성립하지 않는다
        val (r, c) = armed(InterventionId.POPUP_TTS) { autoCompleteTts = false }
        c.fireAuto("point")

        assertEquals(0, r.choiceEnabledCount)
        r.pendingTtsDone?.invoke()
        assertEquals(1, r.choiceEnabledCount)
        assertTrue(r.events.contains("intervention_choice_enabled"))
    }

    // ── 개입 2 ───────────────────────────────────────────────

    @Test
    fun `개입 2는 안내가 끝난 뒤 통화를 끊고 화면은 남긴다`() {
        val (r, c) = armed(InterventionId.FORCE_TERMINATE) { autoCompleteTts = false }
        c.fireAuto("point")

        // 안내 중에는 아직 끊기지 않는다 — 바로 끊으면 종료 사유를 듣지 못한다
        assertEquals(0, r.endCallCount)
        assertEquals("화면은 이미 떠 있어야 한다", 1, r.screens.size)
        assertFalse("선택지를 주면 안 된다", r.screens.first().second)

        r.pendingTtsDone?.invoke()

        assertEquals(1, r.endCallCount)
        assertEquals(0, r.choiceEnabledCount)
        // 통화가 끊겨도 개입 화면은 남는다 — 화면이 바뀌면 한 화면 설계가 깨진다
        assertEquals(0, r.hideCount)
    }

    // ── 두 조건의 공통성 ─────────────────────────────────────

    @Test
    fun `두 개입 조건의 공통 동작은 완전히 동일하다`() {
        val (a, _) = fired(InterventionId.POPUP_TTS)
        val (b, _) = fired(InterventionId.FORCE_TERMINATE)

        assertEquals(a.remoteBlocked, b.remoteBlocked)
        assertEquals(a.micBlocked, b.micBlocked)
        assertEquals(a.vibrateCount, b.vibrateCount)
        assertEquals(a.toneCount, b.toneCount)
        assertEquals(a.spoken.size, b.spoken.size)
        assertEquals(a.delays, b.delays)
        assertEquals(a.screens.size, b.screens.size)
        assertTrue(a.spoken.first().startsWith("보이스피싱으로 의심되어"))
        assertTrue(b.spoken.first().startsWith("보이스피싱으로 의심되어"))
        // 다른 것은 선택권 유무와 강제 종료 여부뿐이어야 한다
        assertTrue(a.screens.first().second)
        assertFalse(b.screens.first().second)
        assertEquals(0, a.endCallCount)
        assertEquals(1, b.endCallCount)
    }

    @Test
    fun `경고음과 안내 음성은 겹치지 않게 간격을 둔다`() {
        // 동시에 나가면 경고음이 안내의 첫 어절을 덮어 참가자가 이유를 듣지 못한다
        val (r, _) = fired(InterventionId.POPUP_TTS)
        assertEquals(listOf(900L), r.delays)
    }

    // ── 통화 이어가기 ────────────────────────────────────────

    @Test
    fun `통화 이어가기를 고르면 스피커와 마이크 차단이 모두 풀리고 화면이 걷힌다`() {
        // 차단이 남으면 이어가기를 고른 참가자가 듣지도 말하지도 못해
        // 선택지가 허울이 되고 개입 1이 사실상 개입 2와 같아진다
        val (r, c) = fired(InterventionId.POPUP_TTS)

        c.resumeCall()

        assertEquals(listOf(true, false), r.remoteBlocked)
        assertEquals(listOf(true, false), r.micBlocked)
        assertEquals(1, r.hideCount)
        assertTrue(c.resumed)
        assertTrue(r.events.contains("participant_chose_continue"))
    }

    @Test
    fun `발동 전에는 통화 이어가기가 아무 일도 하지 않는다`() {
        val (r, c) = armed(InterventionId.POPUP_TTS)

        c.resumeCall()

        assertTrue(r.remoteBlocked.isEmpty())
        assertTrue(r.micBlocked.isEmpty())
        assertEquals(0, r.hideCount)
        assertFalse(c.resumed)
    }

    // ── 통제·중복·초기화 ─────────────────────────────────────

    @Test
    fun `무개입 통제는 화면도 띄우지 않고 시각만 남긴다`() {
        val (r, c) = fired(InterventionId.NONE)

        assertTrue(r.remoteBlocked.isEmpty())
        assertTrue(r.micBlocked.isEmpty())
        assertEquals(0, r.vibrateCount)
        assertEquals(0, r.toneCount)
        assertTrue(r.spoken.isEmpty())
        assertTrue("통제는 개입 화면도 띄우지 않는다", r.screens.isEmpty())
        assertEquals(0, r.endCallCount)
        // 발동 시각은 기록돼야 개입군과 같은 기준점에서 이후 행동을 비교할 수 있다
        assertEquals(1_000L, c.firedAt)
        assertTrue(r.events.contains("intervention_control_no_action"))
    }

    @Test
    fun `세션당 한 번만 발동한다`() {
        val (r, c) = fired(InterventionId.POPUP_TTS)
        c.fireManual()
        c.fireAuto("point_again")

        assertEquals(1, r.vibrateCount)
        assertEquals(1, r.toneCount)
        assertEquals(1, r.spoken.size)
        assertEquals(1, r.screens.size)
        assertEquals(2, r.events.count { it == "intervention_duplicate_ignored" })
    }

    @Test
    fun `무장하지 않으면 발동하지 않는다`() {
        val r = Recorder()
        val c = r.controller()

        c.fireManual()

        assertFalse(c.fired)
        assertNull(c.firedAt)
        assertTrue(r.screens.isEmpty())
        assertTrue(r.events.contains("intervention_skipped"))
    }

    @Test
    fun `재무장하면 새 조건으로 바뀌고 발동 상태가 초기화된다`() {
        // 연구자가 패널에서 조건을 바꾸는 경로
        val (r, c) = armed(InterventionId.POPUP_TTS)
        c.arm(InterventionCatalog.findById(InterventionId.FORCE_TERMINATE))
        c.fireAuto("point")

        assertEquals(1, r.endCallCount)
        assertFalse(r.screens.first().second)
    }

    @Test
    fun `초기화는 스피커 마이크 차단과 개입 화면을 반드시 걷어낸다`() {
        val (r, c) = fired(InterventionId.POPUP_TTS)

        c.reset()

        // 차단이 남은 채로 다음 세션이 시작되면 그 참가자는 통화 시작부터 차단 상태가 된다
        assertEquals(listOf(true, false), r.remoteBlocked)
        assertEquals(listOf(true, false), r.micBlocked)
        assertTrue(r.hideCount >= 1)
        assertFalse(c.fired)
        assertNull(c.firedAt)
        assertFalse(c.resumed)
    }
}
