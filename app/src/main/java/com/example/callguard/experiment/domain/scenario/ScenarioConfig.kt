package com.example.callguard.experiment.domain.scenario

/** 개입 안내를 어떤 채널로 전달할지 (복수 조합 가능, §4.1) */
enum class NoticeChannel { NONE, VOICE_TTS, VIBRATION, TEXT_BANNER }

/** 안내와 차단의 순서 관계 */
enum class NoticeTiming { NONE, BEFORE_BLOCK, DURING, AFTER_BLOCK }

/** 개입 후 설문 방식 */
enum class SurveyType { NONE, VOICE, TEXT }

/**
 * 회의록의 개입 시나리오 9종을 하나의 데이터 구조로 표현한다 (§4.1).
 *
 * 시나리오를 코드에 하드코딩하지 않고 데이터로 표현해야
 * "독립적으로 진행" 요구와 "시나리오 n 추가" 확장 여지를 동시에 만족한다.
 * 새 시나리오는 [ScenarioCatalog]에 항목을 추가하는 것만으로 만들어진다.
 */
data class ScenarioConfig(
    val id: String,                     // "S1".."S10", 확장 시 "S11"...
    val label: String,                  // UI 표시용 이름
    val blockLocalMic: Boolean,         // 사용자 마이크(상대 전달) 차단 여부
    val blockRemoteAudio: Boolean,      // 상대방 음성 차단 여부
    val noticeChannels: Set<NoticeChannel>, // 복수 채널 동시 가능 (예: 음성+진동)
    val noticeTiming: NoticeTiming,
    val noticeMessage: String,
    val surveyType: SurveyType,
    val interruptOngoingAudio: Boolean, // true=QUEUE_FLUSH(끼어들기), false=QUEUE_ADD(안 끊고 이어짐)
    val terminateCall: Boolean = false  // 개입 Level 7 "통화 강제 종료" — 안내(음성이면 완료 후) 뒤 통화 종료
) {
    /** 발동 시점의 실제 적용 파라미터 스냅샷 — 세션 로그(scenarioParams)에 그대로 기록 (§7) */
    fun toLogMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "label" to label,
        "blockLocalMic" to blockLocalMic,
        "blockRemoteAudio" to blockRemoteAudio,
        "noticeChannels" to noticeChannels.map { it.name },
        "noticeTiming" to noticeTiming.name,
        "noticeMessage" to noticeMessage,
        "surveyType" to surveyType.name,
        "interruptOngoingAudio" to interruptOngoingAudio,
        "terminateCall" to terminateCall
    )
}
