package com.example.callguard.domain.pipeline

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    // onDone: TTS 발화가 실제로 끝난 시점에 호출됨 → 그때 리스닝을 시작해야 TTS 음성이
    // 마이크로 되먹임되어 "아니오" 등으로 오인식되는 것을 막을 수 있다.
    private val speak: (text: String, onDone: (() -> Unit)?) -> Unit,
    private val onAnswer: (questionIndex: Int, answer: Boolean) -> Unit,
    // timedOut=true면 반복 무응답으로 강제 종료된 것 — 상위 레이어는 통화를 재개하지 않고
    // 안전하게 끊어야 한다. false면 질문에 정상적으로 모두 응답해 완료된 경우.
    private val onCompleted: (timedOut: Boolean) -> Unit
) {
    private val TAG = "VoiceSurveyController"

    data class Question(val prompt: String)

    private val questions = listOf(
        Question("첫번째 질문입니다. 전화를 건 사람이 본인이 직접 아는 분인가요? 예 또는 아니오로 답해주세요."),
        Question("두번째 질문입니다. 가족이나 지인과의 실제 상황인가요? 예 또는 아니오로 답해주세요.")
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

    private val scope = CoroutineScope(Dispatchers.Main)
    private var timeoutJob: Job? = null
    // 각 질문에서 무응답 재촉 대기 시간 / 최대 재시도 횟수
    private val silenceTimeoutMs = 15_000L
    private var silenceRetryCount = 0
    private val maxSilenceRetries = 2

    fun start() {
        _isActive.value = true
        silenceRetryCount = 0
        _currentQuestionIndex.value = 0
        askCurrent()
    }

    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        _isActive.value = false
        _isListening.value = false
        _currentQuestionIndex.value = -1
    }

    private fun askCurrent() {
        val idx = _currentQuestionIndex.value
        if (idx !in questions.indices) return
        _isListening.value = false
        // TTS 발화가 끝난 뒤에 리스닝을 시작한다. 재생 중 마이크를 열면 TTS 음성이
        // STT로 되먹임되어 "예/아니오"를 오인식할 수 있으므로 onDone 콜백에서 켠다.
        speak(questions[idx].prompt) {
            // stop() 호출 후 뒤늦게 도착한 콜백이면 리스닝을 되살리지 않는다
            // (통화가 이미 끊긴 뒤 TTS 재생이 이어지는 것을 방지).
            if (_isActive.value) {
                _isListening.value = true
                startSilenceTimeout()
            }
        }
    }

    /** 리스닝 시작 후 무응답 시 재촉/강제완료를 처리하는 타임아웃을 건다. */
    private fun startSilenceTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(silenceTimeoutMs)
            if (!_isActive.value || !_isListening.value) return@launch
            if (silenceRetryCount < maxSilenceRetries) {
                silenceRetryCount++
                Log.d(TAG, "무응답 ${silenceTimeoutMs}ms 경과 → 재촉 (${silenceRetryCount}/$maxSilenceRetries)")
                _isListening.value = false
                speak("응답이 없으시네요. 예 또는 아니오로 답해주세요.") {
                    if (_isActive.value) {
                        _isListening.value = true
                        startSilenceTimeout()
                    }
                }
            } else {
                // 반복 무응답 → 실제 상황인지 확인할 수 없는 상태이므로, 통화를 재개하는 대신
                // 안전하게 종료한다(무응답을 "오탐"으로 간주해 재개하면 실제 피싱 상황에서
                // 위험할 수 있다). TTS 안내는 onDone 콜백을 기다리지 않고 즉시 종료를 진행해
                // TTS가 재생되지 않는 기기에서도 통화가 끊기지 않고 방치되는 일이 없게 한다.
                Log.w(TAG, "반복 무응답 → 안전을 위해 통화 종료")
                _isListening.value = false
                _currentQuestionIndex.value = -1
                _isActive.value = false
                speak("응답이 없어 안전을 위해 통화를 종료합니다.", null)
                onCompleted(true)
            }
        }
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
            // 재질문 중에는 리스닝을 잠시 끄고, 재질문 발화가 끝나면 다시 켠다.
            timeoutJob?.cancel()
            _isListening.value = false
            speak("잘 못 들었어요. 예 또는 아니오로 다시 답해주세요.") {
                if (_isActive.value) {
                    _isListening.value = true
                    startSilenceTimeout()
                }
            }
            return
        }

        Log.d(TAG, "질문 ${idx + 1} 응답: $answer ('$text')")
        timeoutJob?.cancel()
        _isListening.value = false
        onAnswer(idx, answer)

        val next = idx + 1
        if (next in questions.indices) {
            silenceRetryCount = 0  // 다음 질문은 무응답 카운트를 새로 시작
            _currentQuestionIndex.value = next
            askCurrent()
        } else {
            _currentQuestionIndex.value = -1
            _isActive.value = false
            onCompleted(false)
        }
    }
}
