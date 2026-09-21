package com.example.callguard.testapp.domain.script

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 공격자 대사를 참가자에게 들려주는 방식 */
enum class PlaybackMode(val label: String, val description: String) {
    AUTO_TTS(
        "자동 재생 (TTS)",
        "앱이 대본을 합성 음성으로 순서대로 읽는다. 모든 참가자가 글자·타이밍까지 동일한 자극을 받는다"
    ),
    RECORDING(
        "녹음본 재생",
        "assets/attacker/<대본ID>/ 에 넣어 둔 실제 연기 녹음을 순서대로 재생한다. 파일이 없는 줄은 TTS로 대체된다"
    ),
    LIVE(
        "라이브 발화 (연구자)",
        "앱은 소리를 내지 않는다. 연구자가 패널의 대본을 보며 직접 말하고, 줄마다 [다음 대사]를 눌러 진행한다"
    )
}

/**
 * 대본 재생기.
 *
 * 세 재생 모드가 **같은 대본 데이터와 같은 개입 지점**을 지나가도록 한 곳에 모았다.
 * 모드가 바뀌어도 개입이 발동하는 지점(개입 지점 대사 종료 직후)은 변하지 않는다.
 * 다만 라이브 모드는 연구자가 [다음 대사]를 눌러야 도달하므로 사람 지연이 섞인다 —
 * 그래서 도달 이벤트에 `liveManualAdvance`를 남겨 분석에서 구분할 수 있게 한다.
 *
 * 스피커로 나간 공격자 음성은 마이크로 되들어와 참가자 발화로 잘못 인식된다.
 * 그래서 재생 중에는 [onSpeakingChanged]로 분석 게이트를 닫는다 —
 * 이 게이트가 없으면 공격자가 부른 숫자가 참가자의 "유출"로 기록된다.
 */
class AttackerScriptPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** TTS 재생 — 완료 콜백이 반드시 호출돼야 다음 줄로 넘어간다 */
    private val speak: (text: String, onDone: () -> Unit) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onLineChanged: (lineIndex: Int, line: ScriptLine?) -> Unit,
    /** 개입 지점 대사가 끝난 직후 — 개입 자동 발동 */
    private val onInterventionPointReached: () -> Unit,
    /** 대본의 마지막 줄까지 재생이 끝남 (개입이 없는 통제 조건에서 의미 있음) */
    private val onScriptFinished: () -> Unit,
    private val onLog: (event: String, data: Map<String, Any?>) -> Unit
) {
    private val TAG = "ScriptPlayer"

    private var job: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    @Volatile var script: AttackerScript? = null; private set
    @Volatile var mode: PlaybackMode = PlaybackMode.AUTO_TTS; private set

    /** 라이브 모드에서 연구자가 지금 읽어야 할 줄 (0-based). 자동 모드에서는 재생 중인 줄. */
    @Volatile var cursor: Int = -1; private set

    @Volatile private var interventionPointFired = false

    fun start(script: AttackerScript, mode: PlaybackMode) {
        stop()
        this.script = script
        this.mode = mode
        this.cursor = -1
        this.interventionPointFired = false

        onLog(
            "script_started",
            mapOf(
                "scriptId" to script.id,
                "label" to script.label,
                "mode" to mode.name,
                "lineCount" to script.mainLines.size,
                "interventionPointIndex" to script.interventionPointIndex,
                "scamLevel" to script.scamLevel.name,
                "relationship" to script.relationship.name,
                "requestedInfo" to script.requestedInfo
            )
        )

        if (mode == PlaybackMode.LIVE) {
            // 라이브는 연구자가 advance()를 누를 때마다 한 줄씩 진행한다.
            cursor = 0
            val firstLine = script.mainLines.getOrNull(0)
            onLineChanged(0, firstLine)
            if (firstLine != null) {
                onLog(
                    "attacker_line_start",
                    mapOf(
                        "index" to 0,
                        "isInterventionPoint" to firstLine.isInterventionPoint,
                        "live" to true,
                        "text" to firstLine.text
                    )
                )
            }
            return
        }

        job = scope.launch {
            script.mainLines.forEachIndexed { index, line ->
                if (!isActive) return@launch
                cursor = index
                onLineChanged(index, line)
                onLog(
                    "attacker_line_start",
                    mapOf("index" to index, "isInterventionPoint" to line.isInterventionPoint, "text" to line.text)
                )

                playLine(script, index, line)

                onLog("attacker_line_end", mapOf("index" to index, "isInterventionPoint" to line.isInterventionPoint))

                if (line.isInterventionPoint) {
                    // 이 대사가 끝나는 순간이 개입 기준점이다.
                    fireInterventionPointOnce()
                }
                if (!isActive) return@launch
                delay(line.pauseAfterMs)
            }
            onScriptFinished()
        }
    }

    /** 라이브 모드 전용 — 연구자가 방금 읽은 줄을 완료 처리하고 다음 줄로 넘긴다. */
    fun advance() {
        val s = script ?: return
        if (mode != PlaybackMode.LIVE) return
        val current = cursor
        val line = s.mainLines.getOrNull(current) ?: return

        onLog(
            "attacker_line_end",
            mapOf("index" to current, "isInterventionPoint" to line.isInterventionPoint, "live" to true)
        )
        if (line.isInterventionPoint) fireInterventionPointOnce()

        val next = current + 1
        cursor = next
        if (next >= s.mainLines.size) {
            onLineChanged(next, null)
            onScriptFinished()
        } else {
            onLineChanged(next, s.mainLines[next])
            onLog(
                "attacker_line_start",
                mapOf(
                    "index" to next,
                    "isInterventionPoint" to s.mainLines[next].isInterventionPoint,
                    "live" to true,
                    "text" to s.mainLines[next].text
                )
            )
        }
    }

    /** 개입 후 참가자가 계속 대답하려 할 때, 연구자가 후속 대사를 한 줄 재생한다. */
    fun playFollowUp(followUpIndex: Int) {
        val s = script ?: return
        val line = s.followUpLines.getOrNull(followUpIndex) ?: return
        onLog("attacker_followup", mapOf("index" to followUpIndex, "text" to line.text, "mode" to mode.name))
        if (mode == PlaybackMode.LIVE) return  // 라이브는 연구자가 직접 읽는다
        scope.launch {
            onSpeakingChanged(true)
            try {
                speakAwait(line.text)
            } finally {
                onSpeakingChanged(false)
            }
        }
    }

    private fun fireInterventionPointOnce() {
        if (interventionPointFired) return
        interventionPointFired = true
        onLog(
            "intervention_point_reached",
            mapOf(
                "scriptId" to script?.id,
                "requestedInfo" to script?.requestedInfo,
                // 라이브 모드는 연구자가 버튼을 눌러야 여기 도달한다 — 사람 지연이 섞여 있다
                "liveManualAdvance" to (mode == PlaybackMode.LIVE)
            )
        )
        onInterventionPointReached()
    }

    private suspend fun playLine(script: AttackerScript, index: Int, line: ScriptLine) {
        onSpeakingChanged(true)
        try {
            if (mode == PlaybackMode.RECORDING) {
                val asset = findAudioAsset(script.id, script.audioFileIndex(index))
                if (asset != null) {
                    playAsset(asset)
                    return
                }
                // 녹음 파일이 없으면 무음으로 건너뛰지 않고 TTS로 대체한다.
                // 무음으로 건너뛰면 참가자가 요구를 듣지 못한 채 개입만 발동해 세션이 무효가 된다.
                onLog(
                    "attacker_line_fallback_tts",
                    mapOf(
                        "index" to index,
                        "expected" to "attacker/" + script.id + "/" + script.audioFileIndex(index) + ".*"
                    )
                )
            }
            speakAwait(line.text)
        } finally {
            onSpeakingChanged(false)
        }
    }

    private val audioExtensions = listOf("m4a", "mp3", "wav", "ogg")

    private fun findAudioAsset(scriptId: String, fileIndex: Int): String? {
        val dir = "attacker/" + scriptId
        val names = try {
            context.assets.list(dir)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "녹음본 폴더 조회 실패: " + dir, e)
            emptySet<String>()
        }
        for (ext in audioExtensions) {
            val name = "" + fileIndex + "." + ext
            if (name in names) return dir + "/" + name
        }
        return null
    }

    private suspend fun playAsset(assetPath: String) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            val afd = context.assets.openFd(assetPath)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    // 실제 통화처럼 수화부(귀에 대는 스피커)로 나가게 한다.
                    // 스피커로 크게 울리면 마이크 되들어옴이 심해 STT가 오염된다.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mp.setOnCompletionListener { player ->
                afd.runCatching { close() }
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { player, what, extra ->
                onLog("attacker_audio_error", mapOf("asset" to assetPath, "what" to what, "extra" to extra))
                afd.runCatching { close() }
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                if (cont.isActive) cont.resume(Unit)
                true
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            cont.invokeOnCancellation {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                if (mediaPlayer === mp) mediaPlayer = null
            }
        } catch (e: Exception) {
            onLog("attacker_audio_error", mapOf("asset" to assetPath, "error" to (e.message ?: "unknown")))
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private suspend fun speakAwait(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        var resumed = false
        speak(text) {
            if (!resumed && cont.isActive) {
                resumed = true
                cont.resume(Unit)
            }
        }
    }

    /**
     * 지금 나가고 있는 상대방 음성만 끊는다 (스피커 차단).
     * 대본·모드는 그대로 두므로 참가자가 통화를 계속하기로 하면 후속 대사를 이어 쓸 수 있다.
     *
     * 재생 중이던 코루틴의 finally가 출력 카운터를 스스로 내리므로
     * 여기서 [onSpeakingChanged]를 부르지 않는다 — 두 번 내리면 경고 음성이 나가는 동안
     * 분석 게이트가 열려 버린다.
     */
    fun stopPlayback() {
        job?.cancel()
        job = null
        mediaPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mediaPlayer = null
    }

    fun stop() {
        stopPlayback()
        cursor = -1
    }
}
