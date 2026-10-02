package com.example.callguard.testapp.domain.intervention

/**
 * 실험의 개입 조건 2종 (+ 무개입 통제).
 *
 * 두 조건 모두 아래 순서를 그대로 지난다:
 *
 *   ① 스피커 차단(상대방 음성 끊김) + 마이크 차단 + 진동 + 경고음   ← 공통, 즉시
 *   ② 같은 순간 **개입 화면**이 뜨고, 0.9초 뒤 그 화면의 문장을 TTS가 읽는다
 *   ③ 안내가 끝나면
 *        조건 1 → 화면의 [통화 이어가기] / [통화 종료] 버튼이 활성화
 *        조건 2 → 통화 종료 (화면은 그대로 유지)
 *
 * **화면은 개입 시작부터 끝까지 하나다.** 안내 중과 안내 후에 다른 화면을 띄우면
 * 참가자가 "뭔가 또 바뀌었다"에 반응하게 되어, 측정 대상이 개입 방식이 아니라
 * 화면 전환 자체가 된다.
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
    /** 참가자 마이크를 차단한다. 통화 상대가 없으므로 화면 표시·상태상의 처리다. */
    val blockMic: Boolean,
    val vibrate: Boolean,
    val warningTone: Boolean,

    /**
     * 개입 화면에 띄우고 TTS로도 읽는 **하나의 안내문**.
     *
     * 귀로 듣는 말과 눈으로 보는 글이 같아야 한다. 둘이 다르면 참가자가 어느 쪽을
     * 근거로 판단했는지 알 수 없어진다.
     */
    val announcement: String,

    /** 통화를 이어갈지 끊을지 참가자가 고르는가. 고르는 행위 자체가 지표다. */
    val offerChoice: Boolean,
    /** 안내가 끝난 뒤 앱이 통화를 종료하는가 */
    val terminateCall: Boolean
) {
    /** 발동 시점의 실제 적용값 스냅샷 — 세션 로그에 그대로 기록한다 */
    fun toLogMap(): Map<String, Any?> = mapOf(
        "id" to id.name,
        "label" to label,
        "active" to active,
        "blockRemoteAudio" to blockRemoteAudio,
        "blockMic" to blockMic,
        "vibrate" to vibrate,
        "warningTone" to warningTone,
        "announcement" to announcement,
        "offerChoice" to offerChoice,
        "terminateCall" to terminateCall
    )
}

object InterventionCatalog {

    val interventions: List<InterventionConfig> = listOf(

        // 개입 1 — 안내 후 참가자가 통화를 이어갈지 끊을지 고른다.
        InterventionConfig(
            id = InterventionId.POPUP_TTS,
            label = "1. 안내 + 계속/종료 선택",
            researcherNote = "스피커·마이크 차단 + 진동 + 경고음 → 안내 화면·TTS → 참가자가 계속/종료 선택",
            active = true,
            blockRemoteAudio = true,
            blockMic = true,
            vibrate = true,
            warningTone = true,
            announcement = "보이스피싱으로 의심되어 통화가 잠시 중단되었습니다. " +
                "상대방과 나의 음성이 서로 차단되어 전달되지 않습니다. 통화를 이어가려면 화면의 버튼을 눌러주세요.",
            offerChoice = true,
            terminateCall = false
        ),

        // 개입 2 — 같은 형식의 안내를 읽은 뒤 앱이 통화를 끊는다. 선택권이 없다.
        InterventionConfig(
            id = InterventionId.FORCE_TERMINATE,
            label = "2. 안내 + 강제 종료",
            researcherNote = "스피커·마이크 차단 + 진동 + 경고음 → 안내 화면·TTS → 앱이 통화를 끊음 (선택권 없음)",
            active = true,
            blockRemoteAudio = true,
            blockMic = true,
            vibrate = true,
            warningTone = true,
            announcement = "보이스피싱으로 의심되어 통화가 종료되었습니다.",
            offerChoice = false,
            terminateCall = true
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
            announcement = "",
            offerChoice = false,
            terminateCall = false
        )
    )

    fun findById(id: InterventionId): InterventionConfig =
        interventions.first { it.id == id }
}
