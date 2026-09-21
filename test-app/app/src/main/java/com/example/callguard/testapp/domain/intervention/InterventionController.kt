package com.example.callguard.testapp.domain.intervention

/**
 * 개입 실행기.
 *
 * 자동 발동(대본의 개입 지점 도달)과 수동 발동(연구자 버튼)이 **같은 [fire]** 를 지나간다.
 * 연구자가 버튼을 누른 것은 "개입 지점이 마침 그때 도달한 것"과 결과가 완전히 동일해야
 * 두 경로 차이로 측정이 오염되지 않는다.
 *
 * 원자 동작(차단/진동/경고음/TTS/팝업/종료)을 전부 함수로 주입받으므로 이 클래스는
 * Android 프레임워크에 의존하지 않는다 → JVM 단위 테스트로 조건 조합을 검증할 수 있다.
 *
 * 실행 순서는 고정이다:
 *   ① 스피커 차단(상대방 음성 끊김) + 진동 + 경고음 + 마이크 차단 — 즉시, 동시
 *   ② [toneGapMs] 뒤 상황 안내 TTS
 *   ③ 조건 1 → 계속/종료 선택 팝업 (안내가 끝난 뒤)
 *      조건 2 → 안내가 끝난 뒤 통화 종료
 *
 * ①에서 스피커를 가장 먼저 끊는 이유: 상대방이 계속 말하는 위에 경고음과 안내가 겹치면
 * 참가자가 셋 다 못 알아듣는다.
 * ②에서 간격을 두는 이유: 경고음(약 0.6초)과 안내 음성이 겹치면 안내의 첫 어절이 묻힌다.
 * ③에서 TTS 완료를 기다리는 이유: 설명을 다 듣기 전에 선택지가 뜨거나 통화가 끊기면
 * "요약을 듣고 판단한다"는 조건 자체가 성립하지 않는다.
 */
class InterventionController(
    /** 상대방 음성을 즉시 끊는다 */
    private val setRemoteAudioBlocked: (Boolean) -> Unit,
    private val setMicBlocked: (Boolean) -> Unit,
    private val vibrate: () -> Unit,
    private val playWarningTone: () -> Unit,
    private val speak: (text: String, onDone: (() -> Unit)?) -> Unit,
    /** 통화 계속/종료 선택 팝업 표시·해제 */
    private val setDecisionPopup: (message: String?) -> Unit,
    private val endCall: (terminationScreenMessage: String) -> Unit,
    private val onLog: (event: String, data: Map<String, Any?>) -> Unit,
    /** 지연 실행 주입 — 테스트에서는 즉시 실행으로 바꿔 끼운다 */
    private val postDelayed: (delayMs: Long, action: () -> Unit) -> Unit = { _, action -> action() },
    /** 현재 시각 주입 — 테스트에서 고정 시각을 쓰기 위함 */
    private val now: () -> Long = System::currentTimeMillis,
    /** 경고음과 안내 음성 사이 간격 */
    private val toneGapMs: Long = 900
) {
    private val lock = Any()

    @Volatile
    private var config: InterventionConfig? = null

    /** 이번 세션에서 읽어 줄 상황 요약 (시나리오마다 다르다) */
    @Volatile
    private var riskPhrase: String = ""

    @Volatile
    var fired: Boolean = false
        private set

    /** 개입이 실제로 발동된 시각 (ms). 반응시간 계산의 기준점. */
    @Volatile
    var firedAt: Long? = null
        private set

    /** 참가자가 [통화 계속하기]를 골랐는가 */
    @Volatile
    var resumed: Boolean = false
        private set

    val armedConfig: InterventionConfig? get() = config

    /** 세션 시작 시 조건과 상황 요약을 무장한다. */
    fun arm(cfg: InterventionConfig, riskPhrase: String = "") = synchronized(lock) {
        config = cfg
        this.riskPhrase = riskPhrase
        fired = false
        firedAt = null
        resumed = false
        onLog("intervention_armed", mapOf("config" to cfg.toLogMap(riskPhrase)))
    }

    /** 대본의 개입 지점 도달로 자동 발동 */
    fun fireAuto(reason: String) = fire(source = "auto", reason = reason)

    /** 연구자 패널의 "지금 개입" 버튼 */
    fun fireManual() = fire(source = "manual", reason = "researcher_button")

    private fun fire(source: String, reason: String) {
        val cfg: InterventionConfig
        val summary: String
        synchronized(lock) {
            cfg = config ?: run {
                onLog("intervention_skipped", mapOf("why" to "not_armed", "source" to source))
                return
            }
            // 세션당 1회만 발동한다. 자동 발동 직후 연구자가 버튼을 또 눌러도
            // 개입이 두 번 실행되면 반응시간과 유출 판정이 모두 오염된다.
            if (fired) {
                onLog("intervention_duplicate_ignored", mapOf("source" to source))
                return
            }
            fired = true
            firedAt = now()
            summary = riskPhrase
        }

        onLog(
            "intervention_fired",
            mapOf("source" to source, "reason" to reason, "config" to cfg.toLogMap(summary))
        )

        if (!cfg.active) {
            // 무개입 통제군: 발동 시점만 기록하고 아무 동작도 하지 않는다.
            // 이 타임스탬프가 있어야 개입군과 동일한 기준점에서 이후 행동을 비교할 수 있다.
            onLog("intervention_control_no_action", emptyMap())
            return
        }

        // ① 공통 개입 — 즉시, 동시에. 상대방 음성을 가장 먼저 끊는다.
        if (cfg.blockRemoteAudio) setRemoteAudioBlocked(true)
        if (cfg.warningTone) playWarningTone()
        if (cfg.vibrate) vibrate()
        if (cfg.blockMic) setMicBlocked(true)
        onLog(
            "intervention_common_applied",
            mapOf(
                "remoteAudioBlocked" to cfg.blockRemoteAudio,
                "tone" to cfg.warningTone,
                "vibrate" to cfg.vibrate,
                "micBlocked" to cfg.blockMic
            )
        )

        val message = cfg.buildTtsMessage(summary)
        if (message.isBlank()) {
            afterAnnouncement(cfg, summary)
            return
        }
        // ② 경고음이 끝난 뒤 안내 → ③ 안내가 끝난 뒤 팝업 또는 종료
        val gap = if (cfg.warningTone) toneGapMs else 0L
        postDelayed(gap) {
            speak(message) {
                onLog("intervention_tts_done", emptyMap())
                afterAnnouncement(cfg, summary)
            }
        }
    }

    private fun afterAnnouncement(cfg: InterventionConfig, summary: String) {
        if (cfg.showDecisionPopup) {
            setDecisionPopup(cfg.buildPopupMessage(summary))
            // 발동 시각만으로는 참가자의 판단 시간을 알 수 없다 —
            // 안내 음성이 십수 초라 반응시간의 대부분이 재생 시간이기 때문이다.
            // 선택지가 실제로 제시된 시각을 따로 남겨야 "고민한 시간"을 뽑을 수 있다.
            onLog("intervention_popup_shown", emptyMap())
        }
        if (cfg.terminateCall) endCall(cfg.terminationScreenMessage)
    }

    /**
     * 참가자가 [통화 계속하기]를 골랐을 때 (개입 1 전용).
     *
     * 스피커와 마이크 차단을 **반드시** 푼다. 차단이 남으면 "계속하기"를 고른 참가자가
     * 상대 말을 듣지도 말하지도 못해 선택지가 허울이 되고, 조건 1이 조건 2와 같아진다.
     * 시나리오는 발동 상태로 남겨 둔다 — 재발동으로 개입이 두 번 실행되면 측정이 오염된다.
     */
    fun resumeCall() = synchronized(lock) {
        if (!fired) return
        resumed = true
        setDecisionPopup(null)
        setRemoteAudioBlocked(false)
        setMicBlocked(false)
        onLog(
            "participant_chose_continue",
            mapOf("sinceInterventionMs" to firedAt?.let { now() - it })
        )
    }

    /** 참가자가 [통화 종료]를 골랐을 때 — 실제 종료는 호출자가 수행한다 */
    fun dismissPopup() = synchronized(lock) {
        setDecisionPopup(null)
    }

    /** 다음 세션을 위한 초기화 — 차단과 팝업을 반드시 걷어내고 시작한다. */
    fun reset() = synchronized(lock) {
        config = null
        riskPhrase = ""
        fired = false
        firedAt = null
        resumed = false
        setDecisionPopup(null)
        setRemoteAudioBlocked(false)
        setMicBlocked(false)
        onLog("intervention_reset", emptyMap())
    }
}
