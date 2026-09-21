package com.example.callguard.testapp.domain.stt

/**
 * 참가자 발화에서 **개인정보 유출**과 **거부 의사**를 잡아낸다.
 *
 * 주 지표(RQ1)는 "요구 이후 참가자가 실제로 정보를 불러줬는가"다. 실제 피해자는
 * "제 주민번호는…" 같은 앞말 없이 숫자만 바로 읽는 경우가 대부분이라,
 * 트리거 문구가 아니라 **연속된 숫자 자체**를 유출로 본다.
 *
 * 한국어 STT는 숫자를 "구공일이삼"처럼 한글 음절로 뱉기도 한다. 그래서 두 등급으로 나눈다:
 *  - [Confidence.CONFIRMED] 아라비아 숫자 연속 → 자동 확정
 *  - [Confidence.SUSPECTED] 숫자 음절 연속 → **연구자 확인 필요**
 *
 * 음절 방식을 자동 확정으로 쓰지 않는 이유: "사실", "이제", "구매" 같은 일상 단어가
 * 숫자 음절과 겹쳐 오탐이 난다. 주 지표를 오탐으로 오염시키느니 연구자에게 넘긴다.
 */
class LeakDetector(
    private val minDigits: Int = 3,
    private val minSpokenDigits: Int = 4
) {
    enum class Confidence { CONFIRMED, SUSPECTED }

    data class Leak(val matched: String, val confidence: Confidence)

    private val whitespace = Regex("\\s+")
    private val digitRun = Regex("[0-9]{" + minDigits + ",}")

    /** STT가 한글 음절로 뱉은 숫자 */
    private val spokenDigits = mapOf(
        '공' to '0', '영' to '0', '일' to '1', '이' to '2', '삼' to '3', '사' to '4',
        '오' to '5', '육' to '6', '륙' to '6', '칠' to '7', '팔' to '8', '구' to '9'
    )
    private val spokenRun = Regex("[" + spokenDigits.keys.joinToString("") + "]{" + minSpokenDigits + ",}")

    /** 참가자가 요구를 명시적으로 거절했음을 나타내는 표현 */
    private val refusalPhrases = listOf(
        "안 알려", "안알려", "못 알려", "못알려", "알려드릴 수 없", "알려줄 수 없",
        "안 불러", "안불러", "싫어요", "싫습니다", "안 할래", "안할래",
        "끊을게", "끊겠습니다", "보이스피싱", "사기", "경찰", "신고"
    )

    @Volatile private var leakReported = false
    @Volatile private var refusalReported = false

    /** 유출이 감지되면 한 번만 반환한다 (partial STT의 반복 프레임으로 중복되지 않게) */
    fun analyzeLeak(text: String): Leak? {
        if (leakReported) return null
        val compact = text.replace(whitespace, "")

        digitRun.find(compact)?.let {
            leakReported = true
            return Leak(it.value, Confidence.CONFIRMED)
        }
        spokenRun.find(compact)?.let { match ->
            leakReported = true
            val converted = match.value.map { spokenDigits[it] ?: it }.joinToString("")
            return Leak(match.value + " (→ " + converted + ")", Confidence.SUSPECTED)
        }
        return null
    }

    /** 거부 표현이 감지되면 한 번만 반환한다 */
    fun analyzeRefusal(text: String): String? {
        if (refusalReported) return null
        val hit = refusalPhrases.firstOrNull { text.contains(it) } ?: return null
        refusalReported = true
        return hit
    }

    fun reset() {
        leakReported = false
        refusalReported = false
    }
}
