package com.example.callguard.testapp

import com.example.callguard.testapp.domain.script.AttackerVoice
import com.example.callguard.testapp.domain.script.AttackerVoiceAssets
import com.example.callguard.testapp.domain.script.VoiceAssetStatus
import com.example.callguard.testapp.domain.script.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목소리 6종이 설계대로 갖춰졌는지 본다.
 *
 * 여기서 막고 싶은 사고는 하나다 — **연구자가 고른 것과 다른 목소리가 재생되는 것.**
 * 폴더 이름이 겹치거나 조합이 빠지면 그 사고가 조용히 난다.
 */
class AttackerVoiceTest {

    @Test
    fun `목소리는 연령대 3종 성별 2종의 6가지 조합이다`() {
        val voices = AttackerVoice.values().toList()
        assertEquals(6, voices.size)

        val expected = listOf(20, 30, 40).flatMap { band ->
            listOf(VoiceGender.MALE, VoiceGender.FEMALE).map { band to it }
        }.toSet()
        assertEquals(expected, voices.map { it.ageBand to it.gender }.toSet())
    }

    @Test
    fun `폴더 이름은 서로 달라 다른 목소리가 섞일 수 없다`() {
        val ids = AttackerVoice.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        // 시나리오가 달라도 경로가 겹치면 S1 녹음이 S2에서 재생된다
        val dirs = AttackerVoice.values().flatMap { v -> listOf("S1", "S2").map { v.assetDir(it) } }
        assertEquals(dirs.size, dirs.toSet().size)
    }

    @Test
    fun `경로는 시나리오와 목소리를 모두 담는다`() {
        assertEquals("attacker/S1/m40", AttackerVoice.M40.assetDir("S1"))
        assertEquals("attacker/S2/f20", AttackerVoice.F20.assetDir("S2"))
    }

    @Test
    fun `표기는 연구자가 바로 읽을 수 있는 형태다`() {
        assertEquals("20대 남성", AttackerVoice.M20.label)
        assertEquals("40대 여성", AttackerVoice.F40.label)
        assertEquals(listOf(20, 30, 40), AttackerVoice.ageBands)
    }

    @Test
    fun `아이디로 목소리를 되찾을 수 있다`() {
        // 로그에 남은 m30 같은 값을 분석 스크립트가 되돌려 읽는 경로다
        AttackerVoice.values().forEach { v ->
            assertEquals(v, AttackerVoice.findById(v.id))
        }
        assertEquals(null, AttackerVoice.findById("m50"))
    }

    @Test
    fun `파일명 규칙은 개입 전후를 구분한다`() {
        // 개입 전 대사와 후속 대사가 같은 이름을 쓰면 서로를 덮어쓴다
        assertEquals("1", AttackerVoiceAssets.mainStem(1))
        assertEquals("f1", AttackerVoiceAssets.followUpStem(1))
        assertTrue(AttackerVoiceAssets.extensions.contains("mp3"))
        // 탐색 순서가 바뀌면 예전 m4a가 새 mp3를 가리는 사고의 원인이 된다
        assertEquals("m4a", AttackerVoiceAssets.extensions.first())
    }

    @Test
    fun `녹음본이 한 줄이라도 비면 완비가 아니다`() {
        // 앱은 빠진 줄만 시스템 TTS로 대체한다 — 통화 중에 목소리가 바뀐다는 뜻이고,
        // 그 세션은 목소리 조건으로 쓸 수 없다. 그래서 "거의 다 있음"은 완비가 아니다.
        val partial = VoiceAssetStatus(AttackerVoice.M30, mainFound = 3, mainTotal = 4, followUpFound = 0, followUpTotal = 3)
        assertFalse(partial.isComplete)
        assertFalse(partial.isEmpty)

        val none = VoiceAssetStatus(AttackerVoice.M30, mainFound = 0, mainTotal = 4, followUpFound = 0, followUpTotal = 3)
        assertTrue(none.isEmpty)
        assertFalse(none.isComplete)

        val full = VoiceAssetStatus(AttackerVoice.M30, mainFound = 4, mainTotal = 4, followUpFound = 3, followUpTotal = 3)
        assertTrue(full.isComplete)
        assertEquals("녹음본 완비", full.summary)
    }
}
