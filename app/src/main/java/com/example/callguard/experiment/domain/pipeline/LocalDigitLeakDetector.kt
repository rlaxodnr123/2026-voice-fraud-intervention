package com.example.callguard.experiment.domain.pipeline

/**
 * 피해자(로컬) 발화의 "맥락 없는 연속 숫자"를 개인정보 유출로 감지한다.
 *
 * 배경: 실제 피해자는 "제 주민등록번호는…" 같은 트리거 문구를 붙이지 않고 숫자만 바로
 * 불러주는 경우가 대부분이다. 기존 탐지기는
 *  - [LocalLeakDetector]        → "주민등록번호는" 같은 명시적 문구가 있어야 하고,
 *  - [SensitiveDisclosureDetector] → 공격자가 먼저 요구(활성 라벨)해야만
 * 숫자를 유출로 본다. 그래서 아무 맥락 없이 숫자만 나열하면 놓친다.
 *
 * 이 탐지기는 그 공백을 메운다: 로컬 발화에 **3자리 이상 연속된 숫자**가 나오면
 * 즉시 유출로 간주하고 콜백한다(공백은 무시 — 연구자가 "9 0 1 2 3 1"처럼 띄어 입력하거나
 * STT가 숫자 사이에 공백을 넣어도 하나의 숫자열로 본다).
 *
 * 세션당 1회만 발동(triggered)해 partial STT의 반복 프레임으로 콜백이 폭주하지 않게 한다.
 * 다음 시나리오/세션을 위해 [reset]으로 재무장한다.
 */
class LocalDigitLeakDetector(
    private val minDigits: Int = 3,
    private val onLeak: (matchedDigits: String) -> Unit
) {
    private val whitespace = Regex("\\s+")
    private val digitRun = Regex("[0-9]{$minDigits,}")

    @Volatile private var triggered = false

    fun analyze(text: String) {
        if (triggered) return
        val compact = text.replace(whitespace, "")
        val match = digitRun.find(compact) ?: return
        triggered = true
        onLeak(match.value)
    }

    fun reset() {
        triggered = false
    }
}
