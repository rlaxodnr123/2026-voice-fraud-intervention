package com.example.callguard.testapp.domain.log

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 세션 한 건의 조건 묶음 — 파일명과 로그 머리말에 그대로 들어간다 */
data class SessionMeta(
    val participantId: String,
    /** 이 참가자가 겪는 몇 번째 통화인가 (1~). 첫 노출 vs 반복 노출 비교의 핵심 변수 */
    val trialOrder: Int,
    val interventionId: String,
    val interventionLabel: String,
    val scriptId: String,
    val scriptLabel: String,
    /** REAL_SCAM / AMBIGUOUS — 2×2 설계의 한 축 */
    val scamLevel: String,
    /** ACQUAINTANCE / STRANGER — 2×2 설계의 다른 축 */
    val relationship: String,
    /** 통화 화면에 실제로 표시한 발신자 (지인 조건에서 연구자가 바꿀 수 있다) */
    val callerDisplayed: String,
    val playbackMode: String,
    /** 상대방 목소리 (m20·f20·m30·f30·m40·f40) — 같은 대사라도 목소리가 자극을 바꾼다 */
    val attackerVoice: String,
    val attackerVoiceLabel: String,
    /**
     * 고른 목소리가 실제로 그대로 나갔는가.
     *
     * "녹음본 재생"이 아닌 모드에서는 시스템 TTS가 읽으므로 목소리 선택이 소리에
     * 반영되지 않는다. 이 값이 false인 세션을 목소리 조건으로 묶어 분석하면 안 된다.
     */
    val attackerVoiceApplied: Boolean,
    val startedAt: Long
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "participantId" to participantId,
        "trialOrder" to trialOrder,
        "interventionId" to interventionId,
        "interventionLabel" to interventionLabel,
        "scriptId" to scriptId,
        "scriptLabel" to scriptLabel,
        "scamLevel" to scamLevel,
        "relationship" to relationship,
        "callerDisplayed" to callerDisplayed,
        "playbackMode" to playbackMode,
        "attackerVoice" to attackerVoice,
        "attackerVoiceLabel" to attackerVoiceLabel,
        "attackerVoiceApplied" to attackerVoiceApplied,
        "startedAt" to startedAt,
        "startedAtText" to ExperimentLogger.formatTime(startedAt)
    )
}

/**
 * 세션 로그 기록기.
 *
 * 한 세션 = 폴더 하나(`sessions/<세션ID>/`)이고 그 안에 `session.json`과 `recording.wav`가 들어간다.
 * 폴더로 묶는 이유: 나중에 분석할 때 "이 로그의 녹음은 어느 파일인가"를 파일명 규칙으로
 * 추측하지 않아도 되고, 폴더째 옮기면 쌍이 절대 깨지지 않기 때문이다.
 *
 * 파일 쓰기는 **전용 단일 스레드**에서만 일어난다. [log]는 마이크 워커 스레드·TTS 콜백
 * 스레드·메인 스레드에서 동시에 불리는데, 여기서 곧바로 디스크에 쓰면 오디오 읽기 루프가
 * 막혀 AudioRecord 버퍼가 넘치고 녹음이 끊긴다.
 */
class ExperimentLogger(private val rootDir: File) {

    companion object {
        private const val TAG = "ExperimentLogger"

        // SimpleDateFormat은 스레드 안전하지 않다. 여러 스레드에서 동시에 format()을 부르면
        // 시각 문자열이 뒤섞이거나 예외가 난다 — 타임스탬프가 주 지표인 실험에서는 치명적이다.
        private val fileStamp = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
        }
        private val readable = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA)
        }

        fun formatTime(ms: Long): String = readable.get().format(Date(ms))
        fun formatStamp(ms: Long): String = fileStamp.get().format(Date(ms))

        /** 개입 이후 "참가자가 반응했다"로 보는 이벤트들 */
        private val REACTION_TYPES = arrayOf(
            "participant_speech_onset",     // 발화 개시 (중간 인식 기준 — 발화 끝이 아니다)
            "participant_chose_continue",   // 팝업에서 계속하기 선택
            "user_action",                  // 음소거·스피커 등 화면 조작
            "call_ended_by_participant"     // 직접 통화 종료
        )
    }

    private val events = mutableListOf<JSONObject>()

    /** 쓰기 작업이 이미 큐에 있는지 — 이벤트마다 쌓지 않고 하나로 합친다 */
    private val writePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ExperimentLogger").apply { isDaemon = true }
    }

    @Volatile var meta: SessionMeta? = null; private set
    @Volatile var sessionDir: File? = null; private set

    val isActive: Boolean get() = meta != null

    fun start(meta: SessionMeta): File {
        synchronized(events) { events.clear() }
        this.meta = meta
        val id = buildString {
            append(meta.participantId.ifBlank { "unknown" })
            append("_T").append(meta.trialOrder)
            append("_").append(meta.scriptId)
            // 목소리를 폴더명에 넣는다 — 세션 폴더를 열어 보지 않고도 어떤 자극이었는지 보인다
            append("_").append(meta.attackerVoice)
            append("_").append(meta.interventionId)
            append("_").append(formatStamp(meta.startedAt))
        }.replace(Regex("[^A-Za-z0-9_\\-]"), "")

        val dir = File(rootDir, id)
        dir.mkdirs()
        sessionDir = dir
        log("session_started", meta.toMap())
        return dir
    }

    fun log(type: String, data: Map<String, Any?> = emptyMap()) {
        if (meta == null) return
        val now = System.currentTimeMillis()
        val obj = JSONObject()
        obj.put("t", now)
        obj.put("time", formatTime(now))
        obj.put("type", type)
        obj.put("data", JSONObject(data.mapValues { it.value ?: JSONObject.NULL }))
        synchronized(events) { events.add(obj) }
        scheduleFlush()
    }

    /** 세션 종료 — 파일을 확정하고 폴더를 돌려준다. @return 세션 폴더 */
    fun finish(reason: String, extra: Map<String, Any?> = emptyMap()): File? {
        val m = meta ?: return null
        val dir = sessionDir
        log("session_finished", extra + mapOf("reason" to reason))
        val snapshot = synchronized(events) { events.toList() }

        // 먼저 세션을 닫아 둔다 — 큐에 남아 있던 쓰기 작업들이 여기서 전부 스스로 빠진다.
        // 그러지 않으면 아래 동기 쓰기가 끝난 뒤 뒤늦게 실행된 작업이 옛 스냅샷으로 덮어써,
        // session_finished 가 사라진 파일이 최종 산출물로 남는다.
        meta = null
        sessionDir = null

        // 마지막 쓰기도 **같은 단일 스레드 큐**에 실어 보내고 끝날 때까지 기다린다.
        // 이 스레드에서 직접 쓰면, 마침 실행 중이던 예약 작업이 그 뒤에 자기 스냅샷으로
        // 파일을 덮어써 session_finished 는 물론 대부분의 이벤트가 사라진 파일이 남는다 —
        // 위에서 세션을 닫아 빠지는 것은 "아직 시작하지 않은" 작업뿐이다.
        // 기다리는 이유: 연구자가 돌려받은 폴더를 곧바로 내보낼 수 있어야 한다.
        if (dir != null) {
            runCatching { writer.submit { writeSessionFile(dir, m, snapshot) } }
                .onSuccess { task ->
                    runCatching { task.get(5, TimeUnit.SECONDS) }
                        .onFailure { Log.e(TAG, "세션 로그 확정 대기 실패", it) }
                }
                .onFailure {
                    // 큐가 이미 닫혔다면 기록을 잃는 것보다 이 스레드에서 쓰는 편이 낫다
                    Log.e(TAG, "세션 로그 확정 예약 실패 — 이 스레드에서 직접 쓴다", it)
                    writeSessionFile(dir, m, snapshot)
                }
        }
        return dir
    }

    fun release() {
        runCatching { writer.shutdown() }
    }

    /**
     * 파일 쓰기를 예약한다.
     *
     * 이벤트마다 작업을 큐에 넣으면 각 작업이 **자기 시점의 스냅샷으로 파일 전체를 덮어써서**
     * 마지막에 실행된 작업이 이기는 경쟁이 된다. 그래서 예약은 한 번만 하고(coalescing),
     * 실제로 실행될 때 그 순간의 최신 스냅샷을 쓴다.
     */
    private fun scheduleFlush() {
        if (!writePending.compareAndSet(false, true)) return
        runCatching {
            writer.execute {
                writePending.set(false)
                val dir = sessionDir ?: return@execute   // 이미 종료된 세션이면 건드리지 않는다
                val m = meta ?: return@execute
                val snapshot = synchronized(events) { events.toList() }
                writeSessionFile(dir, m, snapshot)
            }
        }.onFailure {
            writePending.set(false)
            // 큐가 거부하면 그 이벤트부터 파일에 안 남는다 — 조용히 넘기면 원인을 못 찾는다
            Log.e(TAG, "세션 로그 플러시 예약 실패", it)
        }
    }

    private fun writeSessionFile(dir: File, m: SessionMeta, snapshot: List<JSONObject>) {
        try {
            val root = JSONObject()
            root.put("meta", JSONObject(m.toMap().mapValues { it.value ?: JSONObject.NULL }))
            root.put(
                "summary",
                JSONObject(summarize(m, snapshot).mapValues { it.value ?: JSONObject.NULL })
            )
            root.put("events", JSONArray(snapshot))
            File(dir, "session.json").writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "세션 로그 기록 실패", e)
        }
    }

    /**
     * 분석에 바로 쓰는 파생 지표를 이벤트 목록에서 계산한다.
     *
     * 계산을 기록 시점이 아니라 여기서 하는 이유: 기록 도중에 누적하면 중간에 정의가 바뀔 때
     * 이미 저장된 세션은 옛 정의로 남는다. 원본 이벤트만 사실로 두고 요약은 언제든 다시
     * 계산할 수 있게 해야 재분석이 가능하다.
     */
    internal fun summarize(meta: SessionMeta, list: List<JSONObject>): Map<String, Any?> {
        fun first(vararg types: String): JSONObject? =
            list.firstOrNull { it.optString("type") in types }

        fun firstTime(vararg types: String): Long? = first(*types)?.optLong("t")

        fun firstAfter(after: Long?, vararg types: String): Long? {
            if (after == null) return null
            return list.firstOrNull { it.optString("type") in types && it.optLong("t") >= after }
                ?.optLong("t")
        }

        val pointAt = firstTime("intervention_point_reached")
        val firedEvent = first("intervention_fired")
        val firedAt = firedEvent?.optLong("t")
        val endedAt = firstTime("session_finished")

        // 개입 지점 대사가 화면/스피커에 나오기 시작한 시각.
        // 라이브 모드에서는 연구자가 그 줄을 읽고 [다음 대사]를 누를 때까지의 사람 지연이
        // 통째로 들어가므로, 이 값과의 차이를 봐야 트리거 지연을 볼 수 있다.
        val pointLineShownAt = list.firstOrNull {
            it.optString("type") == "attacker_line_start" &&
                it.optJSONObject("data")?.optBoolean("isInterventionPoint") == true
        }?.optLong("t")

        val firstReactionEvent = firedAt?.let { f ->
            list.firstOrNull { it.optString("type") in REACTION_TYPES && it.optLong("t") >= f }
        }

        val leakEvent = list.firstOrNull { it.optString("type") == "leak_detected" }
        val leakAt = leakEvent?.optLong("t")
        val refusalAt = firstTime("refusal_detected")

        val endedByParticipantAt = firstTime("call_ended_by_participant")
        val endedByAppAt = firstTime("call_ended_by_app")
        val callEndedAt = endedByParticipantAt ?: endedByAppAt
        val callEndedBy = when {
            endedByParticipantAt != null -> "participant"
            endedByAppAt != null -> "app"
            else -> "none"
        }

        val continuedAt = firstTime("participant_chose_continue")
        val ttsDoneAt = firstTime("intervention_tts_done")
        val choiceEnabledAt = firstTime("intervention_choice_enabled")
        // 참가자가 실제로 선택지를 보고 고민한 시간. 통화를 끊은 경우도 포함한다.
        val decisionAt = continuedAt ?: firstTime("call_ended_by_participant")

        return mapOf(
            // ── 조건 ────────────────────────────────────────────
            "participantId" to meta.participantId,
            "trialOrder" to meta.trialOrder,
            "interventionId" to meta.interventionId,
            "scriptId" to meta.scriptId,
            "scamLevel" to meta.scamLevel,
            "relationship" to meta.relationship,
            "playbackMode" to meta.playbackMode,

            // ── 개입 타이밍 ─────────────────────────────────────
            "interventionPointAt" to pointAt,
            "interventionFiredAt" to firedAt,
            "interventionSource" to firedEvent?.optJSONObject("data")?.optString("source"),
            // 개입 지점 도달 → 발동 지연. 설계상 0에 가까워야 하며,
            // 크게 벌어진 세션은 재생·트리거에 문제가 있었다는 뜻이다.
            "pointToInterventionMs" to diff(pointAt, firedAt),
            // 라이브 모드에서만 의미 있는 값 — 연구자가 대사를 읽고 버튼을 누르기까지
            "lineShownToInterventionMs" to diff(pointLineShownAt, firedAt),
            // 라이브 모드는 사람이 버튼을 눌러야 발동하므로 트리거 지연이 대본에 고정되지 않는다.
            // 자동 모드와 RT를 직접 비교하면 안 된다는 표시.
            "triggerLatencyControlled" to (meta.playbackMode != "LIVE"),

            // ── 반응시간 (RQ2 주 지표) ──────────────────────────
            "firstReactionAt" to firstReactionEvent?.optLong("t"),
            "firstReactionType" to firstReactionEvent?.optString("type"),
            "reactionTimeMs" to diff(firedAt, firstReactionEvent?.optLong("t")),

            // ── 유출 (RQ1 주 지표) ──────────────────────────────
            "leaked" to (leakAt != null),
            "leakConfidence" to leakEvent?.optJSONObject("data")?.optString("confidence"),
            "leakSource" to leakEvent?.optJSONObject("data")?.optString("source"),
            "leakAfterIntervention" to (leakAt != null && firedAt != null && leakAt >= firedAt),
            "pointToLeakMs" to diff(pointAt, leakAt),

            "refusalDetected" to (refusalAt != null),
            "refusalAt" to refusalAt,

            // ── 개입에 대한 선택 ────────────────────────────────
            // 애매한 시나리오(S2·S4)에서는 "계속하기"가 올바른 선택이고,
            // 사기 시나리오에서는 개입을 무시한 것이다. scamLevel과 함께 읽어야 한다.
            // 안내 음성 길이 — 반응시간에서 이 몫을 빼야 참가자의 판단 시간이 남는다
            "announcementDoneAt" to ttsDoneAt,
            "announcementMs" to diff(firedAt, ttsDoneAt),
            // 안내가 끝나 선택 버튼이 눌리게 된 시각 (조건 1 전용).
            // 개입 화면 자체는 발동과 동시에 떠 있으므로, "고민 시작"은 이 시점이다.
            "choiceEnabledAt" to choiceEnabledAt,
            "decisionTimeMs" to diff(choiceEnabledAt, decisionAt),

            "choseContinue" to (continuedAt != null),
            "choseContinueAt" to continuedAt,
            "continueDecisionMs" to diff(firedAt, continuedAt),

            // ── 통화 종료 ───────────────────────────────────────
            "callEndedBy" to callEndedBy,
            "callEndedAt" to callEndedAt,
            "callDurationMs" to diff(meta.startedAt, callEndedAt),
            "interventionToCallEndMs" to diff(firedAt, callEndedAt),

            // ── 기타 ────────────────────────────────────────────
            "sessionEndReason" to list.lastOrNull { it.optString("type") == "session_finished" }
                ?.optJSONObject("data")?.optString("reason"),
            "sessionDurationMs" to diff(meta.startedAt, endedAt),
            "observationNotes" to list.filter { it.optString("type") == "observation" }
                .map { it.optJSONObject("data")?.optString("note").orEmpty() },
            "gatedSpeechCount" to list.count { it.optString("type") == "participant_speech_gated" },
            "micErrorCount" to list.count { it.optString("type") == "mic_error" },
            "ttsFallbackCount" to list.count { it.optString("type") == "attacker_line_fallback_tts" },
            "eventCount" to list.size
        )
    }

    private fun diff(from: Long?, to: Long?): Long? =
        if (from != null && to != null) to - from else null
}
