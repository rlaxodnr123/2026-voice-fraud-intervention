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
 * 실험 결과의 타당성이 "두 조건이 선택권 유무만 빼고 똑같이 동작한다"에 걸려 있으므로,
 * 그 불변식을 테스트로 고정한다.
 */
class InterventionControllerTest {

    /** 시나리오가 주는 짧은 상황 구절 — 개입 TTS가 문장 앞자리에 넣어 읽는다 */
    private val PHRASE = "다른 사람 계좌로 송금을 요구하는"

    private class Recorder {
        val remoteBlocked = mutableListOf<Boolean>()
        val micBlocked = mutableListOf<Boolean>()
        var vibrateCount = 0
        var toneCount = 0
        val spoken = mutableListOf<String>()
        val popups = mutableListOf<String?>()
        val endedWith = mutableListOf<String>()
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
            setDecisionPopup = { popups.add(it) },
            endCall = { msg -> endedWith.add(msg) },
            onLog = { event, _ -> events.add(event) },
            postDelayed = { delayMs, action -> delays.add(delayMs); action() },
            now = now
        )
    }

    private fun armed(id: InterventionId, configure: Recorder.() -> Unit = {}):
        Pair<Recorder, InterventionController> {
        val r = Recorder().apply(configure)
        val c = r.controller()
        c.arm(InterventionCatalog.findById(id), PHRASE)
        return r to c
    }

    private fun fired(id: InterventionId, configure: Recorder.() -> Unit = {}):
        Pair<Recorder, InterventionController> {
        val (r, c) = armed(id, configure)
        c.fireAuto("point")
        return r to c
    }

    // ── 개입 1 ───────────────────────────────────────────────

    @Test
    fun `개입 1은 스피커 마이크 차단 진동 경고음 안내 뒤 선택 팝업을 띄우고 통화를 끊지 않는다`() {
        val (r, c) = fired(InterventionId.POPUP_TTS)

        assertEquals(listOf(true), r.remoteBlocked)
        assertEquals(listOf(true), r.micBlocked)
        assertEquals(1, r.vibrateCount)
        assertEquals(1, r.toneCount)
        assertEquals(1, r.spoken.size)
        assertEquals(1, r.popups.size)
        assertTrue(r.popups.first()!!.contains("보이스피싱"))
        assertTrue(r.endedWith.isEmpty())
        assertTrue(c.fired)
        assertEquals(1_000L, c.firedAt)
    }

    @Test
    fun `안내 음성과 팝업은 시나리오별 상황 구절을 담는다`() {
        // 고정 문구만 읽어 주면 참가자가 무엇을 근거로 계속·종료를 판단할지 알 수 없다
        val (r, _) = fired(InterventionId.POPUP_TTS)
        assertTrue(r.spoken.first().contains(PHRASE))
        assertTrue(r.popups.first()!!.contains(PHRASE))
        // 안내가 길면 반응시간이 대부분 재생 시간으로 차 버린다 — 한 문장 길이를 넘기지 않는다
        assertTrue("안내가 너무 깁니다: " + r.spoken.first(), r.spoken.first().length <= 70)
    }

    @Test
    fun `상대방 음성을 안내보다 먼저 끊는다`() {
        // 상대가 계속 말하는 위에 경고음과 안내가 겹치면 참가자가 셋 다 못 알아듣는다
        val (r, _) = armed(InterventionId.POPUP_TTS) { autoCompleteTts = false }
            .also { it.second.fireAuto("point") }

        assertEquals(listOf(true), r.remoteBlocked)
        assertEquals(1, r.toneCount)
        assertEquals(1, r.spoken.size)
    }

    @Test
    fun `선택 팝업은 안내가 끝난 뒤에 뜬다`() {
        // 설명을 다 듣기 전에 선택지가 뜨면 "요약을 듣고 판단한다"는 조건이 성립하지 않는다
        val (r, c) = armed(InterventionId.POPUP_TTS) { autoCompleteTts = false }
        c.fireAuto("point")

        assertTrue(r.popups.isEmpty())
        r.pendingTtsDone?.invoke()
        assertEquals(1, r.popups.size)
    }

    // ── 개입 2 ───────────────────────────────────────────────

    @Test
    fun `개입 2는 안내가 끝난 뒤에만 통화를 종료하고 팝업을 띄우지 않는다`() {
        val (r, c) = armed(InterventionId.FORCE_TERMINATE) { autoCompleteTts = false }
        c.fireAuto("point")

        // 안내가 진행 중인 동안에는 아직 끊기지 않아야 한다.
        // 바로 끊으면 참가자가 종료 사유를 듣지 못해 조건의 의미가 사라진다.
        assertTrue(r.endedWith.isEmpty())
        assertTrue(r.popups.isEmpty())

        r.pendingTtsDone?.invoke()

        assertEquals(1, r.endedWith.size)
        assertTrue(r.endedWith.first().contains("보이스피싱"))
        assertTrue(r.popups.isEmpty())
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
        // 두 조건의 안내는 같은 상황 구절과 같은 의심 문구를 담고 마무리만 다르다
        assertTrue(a.spoken.first().contains(PHRASE))
        assertTrue(b.spoken.first().contains(PHRASE))
        // 다른 것은 선택권(팝업) 유무와 강제 종료 여부뿐이어야 한다
        assertTrue(a.endedWith.isEmpty())
        assertEquals(1, b.endedWith.size)
        assertEquals(1, a.popups.size)
        assertTrue(b.popups.isEmpty())
    }

    @Test
    fun `경고음과 안내 음성은 겹치지 않게 간격을 둔다`() {
        // 동시에 나가면 경고음이 안내의 첫 어절을 덮어 참가자가 이유를 듣지 못한다
        val (r, _) = fired(InterventionId.POPUP_TTS)
        assertEquals(listOf(900L), r.delays)
    }

    // ── 통화 계속하기 ────────────────────────────────────────

    @Test
    fun `통화 계속하기를 고르면 스피커와 마이크 차단이 모두 풀린다`() {
        // 차단이 남으면 "계속하기"를 고른 참가자가 듣지도 말하지도 못해
        // 선택지가 허울이 되고 개입 1이 사실상 개입 2와 같아진다
        val (r, c) = fired(InterventionId.POPUP_TTS)

        c.resumeCall()

        assertEquals(listOf(true, false), r.remoteBlocked)
        assertEquals(listOf(true, false), r.micBlocked)
        assertNull(r.popups.last())
        assertTrue(c.resumed)
        assertTrue(r.events.contains("participant_chose_continue"))
    }

    @Test
    fun `발동 전에는 통화 계속하기가 아무 일도 하지 않는다`() {
        val (r, c) = armed(InterventionId.POPUP_TTS)

        c.resumeCall()

        assertTrue(r.remoteBlocked.isEmpty())
        assertTrue(r.micBlocked.isEmpty())
        assertFalse(c.resumed)
    }

    // ── 통제·중복·초기화 ─────────────────────────────────────

    @Test
    fun `무개입 통제는 아무 동작도 하지 않고 시각만 남긴다`() {
        val (r, c) = fired(InterventionId.NONE)

        assertTrue(r.remoteBlocked.isEmpty())
        assertTrue(r.micBlocked.isEmpty())
        assertEquals(0, r.vibrateCount)
        assertEquals(0, r.toneCount)
        assertTrue(r.spoken.isEmpty())
        assertTrue(r.popups.isEmpty())
        assertTrue(r.endedWith.isEmpty())
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
        assertEquals(2, r.events.count { it == "intervention_duplicate_ignored" })
    }

    @Test
    fun `무장하지 않으면 발동하지 않는다`() {
        val r = Recorder()
        val c = r.controller()

        c.fireManual()

        assertFalse(c.fired)
        assertNull(c.firedAt)
        assertTrue(r.events.contains("intervention_skipped"))
    }

    @Test
    fun `재무장하면 새 조건으로 바뀌고 발동 상태가 초기화된다`() {
        // 연구자가 패널에서 조건을 바꾸는 경로
        val (r, c) = armed(InterventionId.POPUP_TTS)
        c.arm(InterventionCatalog.findById(InterventionId.FORCE_TERMINATE), PHRASE)
        c.fireAuto("point")

        assertEquals(1, r.endedWith.size)
        assertTrue(r.popups.isEmpty())
    }

    @Test
    fun `초기화는 스피커 마이크 차단과 팝업을 반드시 걷어낸다`() {
        val (r, c) = fired(InterventionId.POPUP_TTS)

        c.reset()

        // 차단이 남은 채로 다음 세션이 시작되면 그 참가자는 통화 시작부터 차단 상태가 된다
        assertEquals(listOf(true, false), r.remoteBlocked)
        assertEquals(listOf(true, false), r.micBlocked)
        assertNull(r.popups.last())
        assertFalse(c.fired)
        assertNull(c.firedAt)
        assertFalse(c.resumed)
    }
}
