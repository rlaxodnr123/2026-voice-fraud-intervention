package com.example.callguard.experiment.domain.scenario

/**
 * 개입 시나리오 사전 정의 — 회의록 9종(§6 매핑 표) + 2026-07-08 멘토링 자료의
 * 개입 Level 7(통화 강제 종료)을 반영한 S10.
 *
 * 시나리오 11, 12... 확장은 이 리스트에 항목을 추가하면 끝난다 — 엔진 수정 불필요.
 * 파일럿 결과에 따라 noticeTiming/noticeChannels를 연구자 패널에서 오버라이드할 수 있다.
 * (원격 콘솔의 시나리오 버튼 목록은 admin-dashboard.html의 SCENARIOS 배열과 1:1 동기화 유지)
 */
object ScenarioCatalog {

    val scenarios: List<ScenarioConfig> = listOf(

        // S1: 대화 안 끊기고 AI 음성 개입 (QUEUE_ADD — 진행 중 음성을 끊지 않음)
        ScenarioConfig(
            id = "S1",
            label = "S1. 비차단 · AI 음성 개입",
            blockLocalMic = false,
            blockRemoteAudio = false,
            noticeChannels = setOf(NoticeChannel.VOICE_TTS),
            noticeTiming = NoticeTiming.DURING,
            noticeMessage = "주의하세요! 보이스피싱이 의심되는 통화입니다. " +
                "계좌번호, 비밀번호 등 개인정보는 알려주지 마세요.",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = false
        ),

        // S2: 대화 안 끊기고 진동 개입
        ScenarioConfig(
            id = "S2",
            label = "S2. 비차단 · 진동 개입",
            blockLocalMic = false,
            blockRemoteAudio = false,
            noticeChannels = setOf(NoticeChannel.VIBRATION),
            noticeTiming = NoticeTiming.DURING,
            noticeMessage = "",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = false
        ),

        // S3: 상대 음성 차단 + AI 음성 개입
        ScenarioConfig(
            id = "S3",
            label = "S3. 상대 음성 차단 · AI 음성 개입",
            blockLocalMic = false,
            blockRemoteAudio = true,
            noticeChannels = setOf(NoticeChannel.VOICE_TTS),
            noticeTiming = NoticeTiming.AFTER_BLOCK,
            noticeMessage = "보이스피싱 위험이 감지되어 상대방의 음성을 차단했습니다. " +
                "개인정보를 알려주지 마시고 통화를 종료해 주세요.",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = true
        ),

        // S4: 양쪽 음성 차단 + AI 음성 개입
        ScenarioConfig(
            id = "S4",
            label = "S4. 양방향 차단 · AI 음성 개입",
            blockLocalMic = true,
            blockRemoteAudio = true,
            noticeChannels = setOf(NoticeChannel.VOICE_TTS),
            noticeTiming = NoticeTiming.AFTER_BLOCK,
            noticeMessage = "주의! 보이스피싱이 의심되어 마이크와 상대방 음성을 모두 차단했습니다. " +
                "화면을 확인해 주세요.",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = true
        ),

        // S5: 안내 없이 사용자 음성만 차단 (UX 부정 영향 측정용 — 디브리핑 필수, §8)
        ScenarioConfig(
            id = "S5",
            label = "S5. 무통보 · 내 마이크만 차단",
            blockLocalMic = true,
            blockRemoteAudio = false,
            noticeChannels = emptySet(),
            noticeTiming = NoticeTiming.NONE,
            noticeMessage = "",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = false
        ),

        // S6: 안내 후 사용자 음성만 차단 (안내 채널 기본값 음성 — §10 미해결 항목, 텍스트로 오버라이드 가능)
        ScenarioConfig(
            id = "S6",
            label = "S6. 음성 안내 후 내 마이크 차단",
            blockLocalMic = true,
            blockRemoteAudio = false,
            noticeChannels = setOf(NoticeChannel.VOICE_TTS),
            noticeTiming = NoticeTiming.BEFORE_BLOCK,
            noticeMessage = "개인정보 유출 위험이 감지되었습니다. 잠시 후 마이크가 차단됩니다.",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = true
        ),

        // S7: 상대 음성 들리는 채로 마이크 차단 + 텍스트 안내
        ScenarioConfig(
            id = "S7",
            label = "S7. 내 마이크 차단 · 텍스트 안내",
            blockLocalMic = true,
            blockRemoteAudio = false,
            noticeChannels = setOf(NoticeChannel.TEXT_BANNER),
            noticeTiming = NoticeTiming.DURING,
            noticeMessage = "개인정보 유출 위험이 감지되어 마이크가 차단되었습니다.\n" +
                "상대방에게 내 목소리가 전달되지 않습니다.",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = false
        ),

        // S8: 양쪽 차단 + 텍스트 설문 (사전 텍스트 안내 포함)
        ScenarioConfig(
            id = "S8",
            label = "S8. 양방향 차단 · 텍스트 설문",
            blockLocalMic = true,
            blockRemoteAudio = true,
            noticeChannels = setOf(NoticeChannel.TEXT_BANNER),
            noticeTiming = NoticeTiming.BEFORE_BLOCK,
            noticeMessage = "보이스피싱 위험이 감지되어 통화가 일시 차단되었습니다.\n" +
                "아래 질문에 답해 주세요.",
            surveyType = SurveyType.TEXT,
            interruptOngoingAudio = true
        ),

        // S9: 양쪽 차단(확정, §10) 후 음성 설문 — VOICE_TTS 리드인 뒤 설문이 이어진다
        ScenarioConfig(
            id = "S9",
            label = "S9. 양방향 차단 · 음성 설문",
            blockLocalMic = true,
            blockRemoteAudio = true,
            noticeChannels = setOf(NoticeChannel.VOICE_TTS),
            noticeTiming = NoticeTiming.AFTER_BLOCK,
            noticeMessage = "주의! 보이스피싱이 의심되어 마이크와 상대방 음성이 차단되었습니다. " +
                "지금부터 몇 가지 질문을 드리겠습니다.",
            surveyType = SurveyType.VOICE,
            interruptOngoingAudio = true
        ),

        // S10: 통화 강제 종료 (멘토링 자료 개입 Level 7 "최종 개입") —
        // 양측 차단으로 추가 유출을 막고, 종료 사유를 음성으로 안내한 뒤 통화를 끊는다.
        ScenarioConfig(
            id = "S10",
            label = "S10. 통화 강제 종료 · AI 음성 안내",
            blockLocalMic = true,
            blockRemoteAudio = true,
            noticeChannels = setOf(NoticeChannel.VOICE_TTS),
            noticeTiming = NoticeTiming.AFTER_BLOCK,
            noticeMessage = "보이스피싱으로 판단되어 통화를 강제로 종료합니다. " +
                "개인정보를 알려주셨다면 즉시 금융기관에 문의하세요.",
            surveyType = SurveyType.NONE,
            interruptOngoingAudio = true,
            terminateCall = true
        )
    )

    fun findById(id: String): ScenarioConfig? = scenarios.firstOrNull { it.id == id }
}
