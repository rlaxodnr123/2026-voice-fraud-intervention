package com.example.callguard.experiment.domain.pipeline

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 시나리오 8용 텍스트 설문 컨트롤러 (§4.4).
 *
 * [VoiceSurveyController]와 동일한 상태 모델(질문 인덱스, 활성 여부)을 쓰되,
 * 답변을 STT 대신 화면의 "예/아니오" 버튼 탭으로 받는다.
 * 버튼은 참가자가 누를 때까지 화면에 남아 있으므로 무응답 타임아웃 로직은 없다.
 */
class TextSurveyController(
    private val onAnswer: (questionIndex: Int, answer: Boolean) -> Unit,
    private val onCompleted: () -> Unit
) {
    private val TAG = "TextSurveyController"

    // VoiceSurveyController와 동일 문구 — 텍스트로만 표시된다
    val questions = listOf(
        "전화를 건 사람이 본인이 직접 아는 분인가요?",
        "가족이나 지인과의 실제 상황인가요?"
    )

    private val _currentQuestionIndex = MutableStateFlow(-1)
    val currentQuestionIndex: StateFlow<Int> = _currentQuestionIndex

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    fun start() {
        _currentQuestionIndex.value = 0
        _isActive.value = true
    }

    fun stop() {
        _isActive.value = false
        _currentQuestionIndex.value = -1
    }

    /** UI 버튼 탭 → 다음 질문으로 진행 또는 종료 */
    fun answer(value: Boolean) {
        if (!_isActive.value) return
        val idx = _currentQuestionIndex.value
        if (idx !in questions.indices) return

        Log.d(TAG, "질문 ${idx + 1} 응답(터치): $value")
        onAnswer(idx, value)

        val next = idx + 1
        if (next in questions.indices) {
            _currentQuestionIndex.value = next
        } else {
            _currentQuestionIndex.value = -1
            _isActive.value = false
            onCompleted()
        }
    }

    /**
     * 연구자 수동 응답 (§5.2) — 참가자가 버튼을 누르지 못하는 상황에서
     * 연구자가 온디바이스 패널/원격 콘솔에서 대신 기록한다.
     * 진행 중인 질문과 index가 일치할 때만 유효.
     */
    fun forceAnswer(index: Int, value: Boolean) {
        if (!_isActive.value) return
        if (index != _currentQuestionIndex.value) {
            Log.w(TAG, "forceAnswer 무시 — 현재 질문(${_currentQuestionIndex.value}) != 요청($index)")
            return
        }
        answer(value)
    }
}
