package com.example.callguard.experiment

import com.example.callguard.experiment.domain.pipeline.TextSurveyController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 텍스트 설문(S8) 로직 단위 테스트. StateFlow + 콜백 기반이라 순수 JVM에서 검증된다.
 */
class TextSurveyControllerTest {

    private class Harness {
        val answers = mutableListOf<Pair<Int, Boolean>>()
        var completed = false
        val controller = TextSurveyController(
            onAnswer = { idx, v -> answers.add(idx to v) },
            onCompleted = { completed = true }
        )
    }

    @Test
    fun start_activates_first_question() {
        val h = Harness()
        h.controller.start()
        assertTrue(h.controller.isActive.value)
        assertEquals(0, h.controller.currentQuestionIndex.value)
    }

    @Test
    fun answering_all_questions_completes() {
        val h = Harness()
        h.controller.start()
        h.controller.answer(true)   // Q1
        assertEquals(1, h.controller.currentQuestionIndex.value)
        assertFalse(h.completed)
        h.controller.answer(false)  // Q2 (마지막)
        assertTrue(h.completed)
        assertFalse(h.controller.isActive.value)
        assertEquals(-1, h.controller.currentQuestionIndex.value)
        assertEquals(listOf(0 to true, 1 to false), h.answers)
    }

    @Test
    fun answer_before_start_is_ignored() {
        val h = Harness()
        h.controller.answer(true)
        assertTrue(h.answers.isEmpty())
        assertFalse(h.completed)
    }

    @Test
    fun stop_deactivates() {
        val h = Harness()
        h.controller.start()
        h.controller.stop()
        assertFalse(h.controller.isActive.value)
        assertEquals(-1, h.controller.currentQuestionIndex.value)
    }

    @Test
    fun forceAnswer_matches_current_index_only() {
        val h = Harness()
        h.controller.start()
        // 현재 질문(0)과 다른 인덱스는 무시
        h.controller.forceAnswer(1, true)
        assertTrue(h.answers.isEmpty())
        assertEquals(0, h.controller.currentQuestionIndex.value)
        // 일치하면 진행
        h.controller.forceAnswer(0, true)
        assertEquals(listOf(0 to true), h.answers)
        assertEquals(1, h.controller.currentQuestionIndex.value)
    }

    @Test
    fun forceAnswer_before_start_is_ignored() {
        val h = Harness()
        h.controller.forceAnswer(0, true)
        assertTrue(h.answers.isEmpty())
    }
}
