package com.example.callguard.testapp.domain.intervention

/**
 * 실험의 개입 조건 2종 (+ 무개입 통제).
 *
 * 두 조건 모두 아래 순서를 그대로 지난다:
 *
 *   ① 진동 + 스피커 차단(상대방 음성 끊김) + 경고음   ← 공통, 즉시
 *   ② 짧은 상황 안내 TTS                               ← 공통, 경고음이 끝난 뒤
 *   ③ 조건별 분기
 *        조건 1 → 통화를 계속할지 끊을지 버튼 제시
 *        조건 2 → 앱이 통화를 종료
 *
 * 공통 부분을 조건이 아니라 **상수**로 둔 이유: 두 조건의 차이를 "참가자에게 선택권을
 * 주는가" 하나로 귀속시켜야 결과를 해석할 수 있다.
 */
enum class InterventionId { POPUP_TTS, FORCE_TERMINATE, NONE }

data class InterventionConfig(
    val id: InterventionId,
    val label: String,
    /** 연구자 화면 전용 설명 — 참가자에게 노출되지 않는다 */
    val researcherNote: String,
    /** 개입을 실제로 수행하는가 (NONE=무개입 통제) */
    val active: Boolean,

    // ── 공통 개입 ────────────────────────────────────────────
    /** 상대방 음성을 즉시 끊는다. 이 앱에서 실제로 소리가 멈추는 동작이다. */
    val blockRemoteAudio: Boolean,
    /** 참가자 마이크를 차단한다(상대에게 전달 안 됨). 통화 상대가 없으므로 화면 표시·상태상의 처리다. */
    val blockMic: Boolean,
    val vibrate: Boolean,
    val warningTone: Boolean,

    // ── TTS ─────────────────────────────────────────────────
    /**
     * 안내 문장 틀. `%s` 자리에 시나리오의 짧은 상황 구절이 들어간다.
     * 예) "다른 사람 계좌로 송금을 요구하는 상황이 보이스피싱으로 의심되어…"
     */
    val ttsTemplate: String,

    // ── 조건별 ───────────────────────────────────────────────
    /**
     * 통화를 계속할지 끊을지 참가자가 화면에서 고르게 하는가.
     * 고르는 행위 자체가 지표다 — 경고를 무시하고 계속했는가.
     */
    val showDecisionPopup: Boolean,
    /** TTS 안내가 끝난 뒤 통화를 강제 종료하는가 */
    val terminateCall: Boolean,
    /** 강제 종료 후 화면에 남는 안전 안내문 */
    val terminationScreenMessage: String = ""
) {
    /**
     * 실제로 읽어 줄 안내문을 만든다.
     *
     * 시나리오마다 방금 벌어진 일이 다르므로 앞자리 구절도 달라야 한다. 고정 문구만
     * 읽어 주면 참가자는 무엇을 근거로 계속·종료를 판단해야 할지 알 수 없다.
     */
    fun buildTtsMessage(riskPhrase: String): String =
        if (ttsTemplate.isBlank()) "" else ttsTemplate.format(riskPhrase.ifBlank { "지금 통화" })

    /** 팝업에도 같은 내용을 짧게 보여 준다 — 듣고 놓쳤을 때 화면에서 다시 읽을 수 있어야 한다 */
    fun buildPopupMessage(riskPhrase: String): String =
        if (!showDecisionPopup) "" else
            riskPhrase.ifBlank { "지금 통화" } +
                " 상황이\n보이스피싱으로 의심됩니다.\n\n통화를 이어가시겠습니까?"

    /** 발동 시점의 실제 적용값 스냅샷 — 세션 로그에 그대로 기록한다 */
    fun toLogMap(riskPhrase: String = ""): Map<String, Any?> = mapOf(
        "id" to id.name,
        "label" to label,
        "active" to active,
        "blockRemoteAudio" to blockRemoteAudio,
        "blockMic" to blockMic,
        "vibrate" to vibrate,
        "warningTone" to warningTone,
        "ttsMessage" to buildTtsMessage(riskPhrase),
        "showDecisionPopup" to showDecisionPopup,
        "terminateCall" to terminateCall,
        "terminationScreenMessage" to terminationScreenMessage
    )
}

object InterventionCatalog {

    /**
     * 두 조건이 **같은 상황 구절과 같은 의심 문구**를 읽고 마무리만 다르게 말한다.
     * 앞부분이 조건 간에 달라지면 "선택권 유무의 효과"와 "설명의 효과"가 섞인다.
     *
     * 문장을 짧게 유지한다 — 안내가 길면 참가자의 반응시간이 대부분 재생 시간으로 차 버리고,
     * 강제 종료 조건에서는 끊기기까지 하염없이 기다리게 된다.
     *
     * "사기입니다"가 아니라 "의심됩니다"인 이유: 시나리오 절반(S2·S4)은 실제로는
     * 정상일 수 있는 통화다. 앱이 단정하면 애매한 조건에서 앱이 명백히 틀린 말을 하는
     * 셈이 되어, 측정 대상이 개입 방식이 아니라 오판 자체가 된다.
     */
    private const val SUSPECT = "상황이 보이스피싱으로 의심되어"

    val interventions: List<InterventionConfig> = listOf(

        // 개입 1 — 짧은 안내 후 참가자가 계속/종료를 고른다.
        InterventionConfig(
            id = InterventionId.POPUP_TTS,
            label = "1. 안내 + 계속/종료 선택",
            researcherNote = "진동·스피커 차단·경고음 → 짧은 상황 안내 TTS → 참가자가 통화 계속/종료 선택",
            active = true,
            blockRemoteAudio = true,
            blockMic = true,
            vibrate = true,
            warningTone = true,
            ttsTemplate = "%s " + SUSPECT + " 마이크가 차단되었습니다. 통화를 이어가시겠습니까?",
            showDecisionPopup = true,
            terminateCall = false
        ),

        // 개입 2 — 같은 안내를 읽은 뒤 앱이 통화를 끊는다. 참가자에게 선택권이 없다.
        InterventionConfig(
            id = InterventionId.FORCE_TERMINATE,
            label = "2. 안내 + 강제 종료",
            researcherNote = "진동·스피커 차단·경고음 → 짧은 상황 안내 TTS → 앱이 통화를 끊음 (선택권 없음)",
            active = true,
            blockRemoteAudio = true,
            blockMic = true,
            vibrate = true,
            warningTone = true,
            ttsTemplate = "%s " + SUSPECT + " 통화를 종료합니다.",
            showDecisionPopup = false,
            terminateCall = true,
            terminationScreenMessage =
                "보이스피싱이 의심되어 통화가 종료되었습니다.\n\n" +
                "송금 요청이나 개인정보 요구는 전화로 처리하지 마세요.\n" +
                "필요하면 해당 기관의 공식 대표번호로 직접 전화해 확인하세요."
        ),

        // 통제 — 개입 없음. 같은 대본·같은 개입 지점을 지나가지만 앱은 아무것도 하지 않는다.
        InterventionConfig(
            id = InterventionId.NONE,
            label = "3. 무개입 (통제)",
            researcherNote = "개입 없음 — 기준선 측정용. 개입 지점 도달만 로그에 남는다",
            active = false,
            blockRemoteAudio = false,
            blockMic = false,
            vibrate = false,
            warningTone = false,
            ttsTemplate = "",
            showDecisionPopup = false,
            terminateCall = false
        )
    )

    fun findById(id: InterventionId): InterventionConfig =
        interventions.first { it.id == id }
}
