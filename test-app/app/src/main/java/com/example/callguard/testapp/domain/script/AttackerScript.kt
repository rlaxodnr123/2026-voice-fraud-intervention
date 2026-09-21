package com.example.callguard.testapp.domain.script

/** 이 통화가 실제로 사기인가, 아니면 정상일 수도 있는 애매한 통화인가 */
enum class ScamLevel(val label: String, val short: String) {
    REAL_SCAM("진짜 사기", "사기"),
    AMBIGUOUS("애매한 상황", "애매")
}

/** 발신자가 참가자와 어떤 관계로 보이는가 */
enum class CallerRelationship(val label: String, val short: String) {
    ACQUAINTANCE("지인", "지인"),
    STRANGER("모르는 사람", "모름")
}

/**
 * 공격자 대본 한 줄.
 *
 * 이번 실험의 대본은 **일방적 통보**다 — 상대가 쭉 말하고, 참가자가 끼어들 틈 없이
 * 개입 지점에 도달한다. 참가자 대사는 대본에 넣지 않는다.
 * 참가자가 무엇을 말하는가가 종속변수이므로 자극이 미리 규정해선 안 된다.
 */
data class ScriptLine(
    val text: String,
    /**
     * 이 줄이 **개입 지점**인가. 이 줄 재생이 끝나는 순간 개입이 자동 발동한다.
     * 대본당 정확히 하나, 그리고 마지막 줄이어야 한다([AttackerScriptCatalog.validate]가 검사).
     *
     * "요구 지점"이 아니라 "개입 지점"인 이유: 애매한 시나리오(2·4)에는 애초에
     * 정보 요구가 없고, 그런데도 앱이 개입하는 상황 자체가 측정 대상이기 때문이다.
     */
    val isInterventionPoint: Boolean = false,
    /** 이 줄을 말한 뒤 다음 줄까지의 간격(ms) — 자연스러운 호흡 */
    val pauseAfterMs: Long = 600,
    /**
     * 개입 이후 구간. 자동 재생 대상이 아니며, 참가자가 팝업에서 [통화 계속하기]를
     * 골라 대화가 이어질 때만 연구자가 패널에서 한 줄씩 재생한다.
     * (강제 종료 조건에서는 통화가 끊기므로 쓰이지 않는다)
     */
    val afterIntervention: Boolean = false
)

data class AttackerScript(
    val id: String,
    val label: String,
    val scamLevel: ScamLevel,
    val relationship: CallerRelationship,
    val situation: String,
    /**
     * 지인 조건에서 통화 화면에 뜨는 이름. 모르는 사람 조건에서는 비어 있다.
     * 참가자의 실제 나이·가족 구성에 맞춰 연구자가 설정 화면에서 바꿀 수 있다 —
     * "아들"이 뜨는데 참가자가 20대면 몰입이 깨져 관계 조건 자체가 무력해진다.
     */
    val defaultCallerName: String,
    val callerNumber: String,
    /** 상대가 요구한 것. 애매한 시나리오는 "없음"일 수 있다 */
    val requestedInfo: String,
    /**
     * 개입 TTS가 "~~~ 상황이 보이스피싱으로 의심되어…"의 앞자리에 넣어 읽는 **짧은 구절**.
     *
     * 긴 설명을 읽으면 안내만 십수 초가 걸려, 참가자의 반응시간이 대부분 재생 시간으로 차 버린다.
     * 무슨 일이 있었는지 알아들을 만큼만 짧게 쓴다 (관형형으로 끝나 "상황"에 이어진다).
     *
     * 판단을 단정하지 않고 **사실만** 적는다. 애매한 시나리오(S2·S4)에서 앱이
     * "사기입니다"라고 단정하면, 측정 대상이 개입 방식이 아니라 앱의 오판 자체가 된다.
     */
    val riskPhrase: String,
    /** 연구자 패널에 띄우는 핵심 위험 요소 / 핵심 특징 */
    val keyFactors: List<String>,
    val lines: List<ScriptLine>
) {
    /** 개입 전까지 자동 재생되는 줄 */
    val mainLines: List<ScriptLine> get() = lines.filter { !it.afterIntervention }

    /** 개입 후 연구자가 수동으로 쓰는 줄 */
    val followUpLines: List<ScriptLine> get() = lines.filter { it.afterIntervention }

    /** [mainLines] 안에서 개입 지점의 인덱스 */
    val interventionPointIndex: Int get() = mainLines.indexOfFirst { it.isInterventionPoint }

    /** 설정·로그에 쓰는 짧은 조건 표기 (예: "사기·지인") */
    val cellLabel: String get() = scamLevel.short + "·" + relationship.short

    /**
     * 녹음본 파일명에 쓰는 1-based 순번.
     * assets/attacker/<id>/<순번>.m4a 와 1:1로 대응한다.
     */
    fun audioFileIndex(lineIndexInMainLines: Int): Int = lineIndexInMainLines + 1
}
