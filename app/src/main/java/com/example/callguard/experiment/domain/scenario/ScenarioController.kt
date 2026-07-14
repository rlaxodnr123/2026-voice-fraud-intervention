package com.example.callguard.experiment.domain.scenario

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 시나리오 실행기 (§4.3).
 *
 * 핵심: [onAutoDetectionTriggered]와 [onManualTrigger]가 동일한 [fire]를 공유한다.
 * 연구자가 수동 버튼을 누르는 것은 "자동 탐지가 마침 그 순간 성공한 것"과 완전히
 * 동일한 결과를 만든다 — 자동/수동 경로 차이로 인한 측정 변수 오염이 없다.
 *
 * 원자 동작(뮤트/음성/진동/배너/설문)을 전부 함수로 주입받으므로 이 클래스 자체는
 * Android 프레임워크에 의존하지 않는다 → JVM 단위 테스트로 9개 시나리오 조합을 검증할 수 있다.
 */
class ScenarioController(
    private val setLocalMuted: (Boolean) -> Unit,
    private val setRemoteMuted: (Boolean) -> Unit,
    private val speak: (text: String, flush: Boolean, onDone: (() -> Unit)?) -> Unit,
    private val vibrateSOS: () -> Unit,
    private val showTextBanner: (message: String, requireAck: Boolean) -> Unit,
    private val startVoiceSurvey: () -> Unit,
    private val startTextSurvey: () -> Unit,
    private val stopSurveys: () -> Unit,
    private val onLog: (event: String, data: Map<String, Any?>) -> Unit,
    /** 개입 Level 7(통화 강제 종료, S10) — 안내 완료 후 통화를 끊는다 */
    private val endCall: () -> Unit = {},
    /** 재무장/disarm 정리 시 이전 시나리오의 텍스트 배너를 제거한다 */
    private val hideTextBanner: () -> Unit = {}
) {
    private val TAG = "ScenarioController"

    private val blockLock = Any()

    private val _armedScenario = MutableStateFlow<ScenarioConfig?>(null)
    val armedScenario: StateFlow<ScenarioConfig?> = _armedScenario

    private val _fired = MutableStateFlow(false)
    /** 현재 무장된 시나리오가 이미 발동됐는지 (세션당 1회만 발동, 중복 방지) */
    val fired: StateFlow<Boolean> = _fired

    /**
     * 통화/세션 시작 시 호출 — 자동 탐지 콜백에 이 시나리오를 연결한다.
     *
     * 이전 시나리오가 이미 발동돼(fired) 뮤트가 걸려 있는 상태에서 연구자가 곧바로
     * 다음 시나리오를 무장하는 실수를 대비해, 재무장 시 항상 뮤트를 해제하고 진행 중인
     * 설문을 중단한다(disarm과 동일한 정리). 이렇게 해야 시나리오 간 상태 잔존이 없다(§9).
     */
    fun arm(scenario: ScenarioConfig) = synchronized(blockLock) {
        if (_fired.value) cleanup()
        _armedScenario.value = scenario
        _fired.value = false
        onLog("scenario_armed", mapOf("scenarioId" to scenario.id, "params" to scenario.toLogMap()))
        Log.d(TAG, "시나리오 무장: ${scenario.id} (${scenario.label})")
    }

    /** 자동 탐지(키워드 매칭 등)가 조건 충족 시 호출한다. */
    fun onAutoDetectionTriggered(triggerLabel: String) = fire(triggerLabel, source = "auto")

    /** 연구자가 온디바이스 패널/원격 콘솔에서 누르는 수동 트리거 — 자동 탐지 상태 무시. */
    fun onManualTrigger() = fire("manual_override", source = "manual")

    private fun fire(triggerLabel: String, source: String): Unit = synchronized(blockLock) {
        val scenario = _armedScenario.value ?: run {
            Log.w(TAG, "발동 요청 무시 — 무장된 시나리오 없음 (trigger=$triggerLabel)")
            return
        }
        if (_fired.value) return
        _fired.value = true

        onLog(
            "scenario_fired",
            mapOf(
                "scenarioId" to scenario.id,
                "trigger" to triggerLabel,
                "source" to source,
                "params" to scenario.toLogMap()  // 발동 시점 실제 적용 스냅샷 (§7)
            )
        )
        Log.w(TAG, "시나리오 발동: ${scenario.id} trigger=$triggerLabel source=$source")

        // 안내 → 차단 순서(S6, S8의 BEFORE_BLOCK)와 차단 → 안내 순서를 데이터로 해석한다.
        val hasVoice = NoticeChannel.VOICE_TTS in scenario.noticeChannels
        if (scenario.noticeTiming == NoticeTiming.BEFORE_BLOCK && hasVoice) {
            // 음성 안내가 실제로 끝난 뒤에 차단한다 — "안내 후 차단" 순서 보장.
            // 비음성 채널(진동/텍스트)은 즉시 함께 표시하고, 차단·설문은 음성 완료 콜백에서.
            playNonVoiceNotices(scenario)
            speak(scenario.noticeMessage, scenario.interruptOngoingAudio) {
                afterVoiceGate(scenario) {
                    applyBlocks(scenario)
                    startSurvey(scenario)
                    finishIfTerminal(scenario)
                }
            }
        } else if (scenario.noticeTiming == NoticeTiming.BEFORE_BLOCK) {
            // 음성 없는 사전 안내(텍스트/진동)는 즉시 표시 → 곧바로 차단
            playAllNotices(scenario)
            applyBlocks(scenario)
            startSurvey(scenario)
            finishIfTerminal(scenario)
        } else if (scenario.terminateCall && hasVoice) {
            // 통화 강제 종료(S10): 차단 → 종료 사유 음성 안내 → 안내가 끝난 뒤 통화 종료.
            // 안내 완료 콜백을 기다리지 않고 바로 끊으면 참가자가 사유를 듣지 못한다.
            applyBlocks(scenario)
            playNonVoiceNotices(scenario)
            speak(scenario.noticeMessage, scenario.interruptOngoingAudio) {
                afterVoiceGate(scenario) { endCall() }
            }
        } else {
            // NONE / DURING / AFTER_BLOCK: 차단(있다면)을 먼저 확실히 걸고 안내한다
            applyBlocks(scenario)
            playAllNotices(scenario)
            startSurvey(scenario)
            finishIfTerminal(scenario)
        }
    }

    /**
     * TTS 완료 콜백은 발동 시점보다 수 초 늦게 도착한다. 그 사이 연구자가 disarm/세션 리셋/
     * 재무장을 했다면 콜백이 이미 정리된 세션에 차단·종료를 되살리면 안 된다(§9 상태 잔존 방지).
     * 발동 당시 시나리오가 여전히 발동 상태일 때만 후속 동작을 실행한다.
     */
    private fun afterVoiceGate(scenario: ScenarioConfig, action: () -> Unit) = synchronized(blockLock) {
        if (_fired.value && _armedScenario.value?.id == scenario.id) {
            action()
        } else {
            Log.w(TAG, "음성 완료 콜백 무시 — 시나리오(${scenario.id})가 이미 해제/재무장됨")
        }
    }

    private fun finishIfTerminal(scenario: ScenarioConfig) {
        if (scenario.terminateCall) endCall()
    }

    private fun applyBlocks(scenario: ScenarioConfig) {
        if (scenario.blockLocalMic) setLocalMuted(true)
        if (scenario.blockRemoteAudio) setRemoteMuted(true)
        if (scenario.blockLocalMic || scenario.blockRemoteAudio) {
            onLog(
                "intervention",
                mapOf(
                    "level" to "SCENARIO_${scenario.id}",
                    "keywords" to listOf(scenario.label),
                    "action" to "BLOCKED"
                )
            )
        }
    }

    private fun playAllNotices(scenario: ScenarioConfig) {
        scenario.noticeChannels.forEach { ch ->
            when (ch) {
                NoticeChannel.VOICE_TTS -> speak(scenario.noticeMessage, scenario.interruptOngoingAudio, null)
                NoticeChannel.VIBRATION -> vibrateSOS()
                NoticeChannel.TEXT_BANNER -> showTextBanner(scenario.noticeMessage, true)
                NoticeChannel.NONE -> {}
            }
        }
    }

    /** VOICE_TTS를 제외한 채널만 재생 (BEFORE_BLOCK에서 음성은 완료 콜백을 따로 걸기 때문) */
    private fun playNonVoiceNotices(scenario: ScenarioConfig) {
        scenario.noticeChannels.forEach { ch ->
            when (ch) {
                NoticeChannel.VIBRATION -> vibrateSOS()
                NoticeChannel.TEXT_BANNER -> showTextBanner(scenario.noticeMessage, true)
                else -> {}
            }
        }
    }

    private fun startSurvey(scenario: ScenarioConfig) {
        when (scenario.surveyType) {
            // TTS 큐(QUEUE_ADD) 특성상 음성 설문 첫 질문은 안내 음성 뒤에 자연히 이어진다
            SurveyType.VOICE -> startVoiceSurvey()
            SurveyType.TEXT -> startTextSurvey()
            SurveyType.NONE -> {}
        }
    }

    /** 설문 중단 + 뮤트 해제 + 배너 제거 (arm 재무장·disarm 공통 정리) */
    private fun cleanup() {
        stopSurveys()
        setLocalMuted(false)
        setRemoteMuted(false)
        hideTextBanner()
    }

    /**
     * 다음 시나리오로 넘어가기 전 상태 초기화.
     * 통화를 유지한 채 세션을 리셋하는 경우를 위해 뮤트도 함께 해제한다.
     */
    fun disarm() = synchronized(blockLock) {
        _armedScenario.value = null
        _fired.value = false
        cleanup()
        onLog("scenario_disarmed", emptyMap())
    }
}
