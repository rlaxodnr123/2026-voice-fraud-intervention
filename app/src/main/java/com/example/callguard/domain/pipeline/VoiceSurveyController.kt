package com.example.callguard.domain.pipeline

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 마이크 차단 후 상황 확인 설문을 음성으로 진행한다.
 *
 * 흐름: TTS로 질문 낭독 → 로컬 STT(Final) 수신 → 예/아니오 키워드 매칭
 *       → 다음 질문으로 진행, 4문항 종료 시 onCompleted 콜백.
 *
 * 마이크가 차단된 상태에서도 [WebRtcManager]는 로컬 STT 피드를 계속 보내주므로
 * (상대방에게 보내는 트랙만 끊김) 이 컨트롤러가 정상 동작한다.
 */
class VoiceSurveyController(
    private val speak: (text: String) -> Unit,
    private val onAnswer: (questionIndex: Int, answer: Boolean) -> Unit,
    private val onCompleted: () -> Unit
) {
    private val TAG = "VoiceSurveyController"

    data class Question(val prompt: String)

    private val questions = listOf(
        Question("첫번째 질문입니다. 모르는 사람에게 전화가 왔나요? 예 또는 아니오로 답해주세요."),
        Question("두번째 질문입니다. 저장되지 않은 모르는 번호인가요?"),
        Question("세번째 질문입니다. 보이스피싱이 의심되시나요?"),
        Question("네번째 질문입니다. 가족이나 지인과의 실제 긴급 상황인가요?")
    )

    private val yesWords = listOf("예", "네", "맞아요", "맞습니다", "그래요", "응", "yes")
    // STT가 "아니요"를 "아니 요"처럼 띄어 인식하거나 "아니"만 잡는 경우까지 포괄하도록
    // "아니" 계열 어근과 짧은 변형을 폭넓게 등록한다 (긴 단어를 먼저 검사하지 않아도
    // contains 매칭이라 "아니" 하나만으로도 모든 변형을 커버함).
    private val noWords = listOf("아니오", "아니요", "아니야", "아닙니다", "아니에요", "안돼요", "아니", "틀려요", "no")

    private val _currentQuestionIndex = MutableStateFlow(-1)
    val currentQuestionIndex: StateFlow<Int> = _currentQuestionIndex

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    fun start() {
        _isActive.value = true
        _currentQuestionIndex.value = 0
        askCurrent()
    }

    fun stop() {
        _isActive.value = false
        _isListening.value = false
        _currentQuestionIndex.value = -1
    }

    private fun askCurrent() {
        val idx = _currentQuestionIndex.value
        if (idx !in questions.indices) return
        _isListening.value = false
        speak(questions[idx].prompt)
        // TTS 발화 직후부터 응답을 받기 시작한다 (speak는 동기 큐잉이므로 바로 듣기 시작해도 안전)
        _isListening.value = true
    }

    /** AudioProcessingPipeline의 로컬 Final STT 텍스트를 전달받아 예/아니오를 판정한다. */
    fun onLocalTranscript(text: String) {
        if (!_isActive.value || !_isListening.value) return
        val idx = _currentQuestionIndex.value
        if (idx !in questions.indices) return

        // Vosk가 종종 음절 사이에 공백을 넣어 인식하므로(예: "아니 요") 공백을 제거하고 매칭한다.
        val lower = text.trim().replace(" ", "")
        val answer = when {
            noWords.any { lower.contains(it, ignoreCase = true) } -> false
            yesWords.any { lower.contains(it, ignoreCase = true) } -> true
            else -> null
        } ?: run {
            Log.d(TAG, "응답 인식 불가, 재질문: '$text'")
            speak("잘 못 들었어요. 예 또는 아니오로 다시 답해주세요.")
            return
        }

        Log.d(TAG, "질문 ${idx + 1} 응답: $answer ('$text')")
        _isListening.value = false
        onAnswer(idx, answer)

        val next = idx + 1
        if (next in questions.indices) {
            _currentQuestionIndex.value = next
            askCurrent()
        } else {
            _currentQuestionIndex.value = -1
            _isActive.value = false
            onCompleted()
        }
    }
}
