package com.example.callguard.testapp.domain.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * 온디바이스 한국어 STT (Vosk). 참가자 발화만 인식한다.
 *
 * 모델은 용량(약 250MB) 때문에 저장소에 넣지 않는다. 없으면 [ready]가 false로 남고
 * 앱은 **녹음 + 연구자 수동 기록**만으로 정상 동작한다 — 실험이 모델 유무에 묶이면
 * 현장에서 모델 로드가 실패했을 때 세션 전체를 버려야 하기 때문이다.
 */
class ParticipantStt(private val context: Context) {

    private val TAG = "ParticipantStt"
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _status = MutableStateFlow("모델 로딩 중…")
    val status: StateFlow<String> = _status

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /** 최종 인식 결과 (문장 단위) */
    var onFinal: ((String) -> Unit)? = null

    /** 중간 인식 결과 — 숫자 유출은 문장이 끝나기 전에 잡아야 개입 판단이 늦지 않다 */
    var onPartial: ((String) -> Unit)? = null

    fun load() {
        if (_ready.value) return
        scope.launch(Dispatchers.IO) {
            try {
                val destDir = File(context.filesDir, "model-ko")
                if (!File(destDir, "am/final.mdl").exists()) {
                    _status.value = "모델 복사 중… (첫 실행만, 1~2분 걸릴 수 있습니다)"
                    destDir.deleteRecursively()
                    destDir.mkdirs()
                    copyAssetFolder("model-ko", destDir)
                }
                if (!File(destDir, "am/final.mdl").exists()) {
                    _status.value = "모델 없음 — 녹음과 연구자 수동 기록으로 진행됩니다"
                    _ready.value = false
                    return@launch
                }
                model = Model(destDir.absolutePath)
                _ready.value = true
                _status.value = "음성 인식 준비 완료"
                Log.d(TAG, "Vosk 모델 로드 완료")
            } catch (e: Exception) {
                Log.e(TAG, "Vosk 모델 로드 실패", e)
                _ready.value = false
                _status.value = "모델 로드 실패 (" + (e.message ?: "알 수 없음") + ") — 녹음·수동 기록으로 진행됩니다"
            }
        }
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        val am = context.assets
        val names = am.list(assetPath) ?: return
        for (name in names) {
            // macOS 압축 해제 시 섞여 들어오는 메타데이터 파일은 건너뛴다
            if (name.startsWith("._") || name == "__MACOSX") continue
            val child = assetPath + "/" + name
            val dest = File(destDir, name)
            val sub = am.list(child)
            if (!sub.isNullOrEmpty()) {
                dest.mkdirs()
                copyAssetFolder(child, dest)
            } else {
                am.open(child).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** [MicCapture]가 넘겨 준 PCM을 인식기에 먹인다. */
    fun feed(pcm: ByteArray, length: Int, sampleRate: Int) {
        val m = model ?: return
        val rec = recognizer ?: try {
            Recognizer(m, sampleRate.toFloat()).also { recognizer = it }
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer 생성 실패", e)
            return
        }
        try {
            if (rec.acceptWaveForm(pcm, length)) {
                val text = parse(rec.result, "text")
                if (text.isNotBlank()) onFinal?.invoke(text)
            } else {
                val partial = parse(rec.partialResult, "partial")
                if (partial.isNotBlank()) onPartial?.invoke(partial)
            }
        } catch (e: Exception) {
            Log.e(TAG, "인식 실패", e)
        }
    }

    private fun parse(json: String, key: String): String {
        val m = Regex("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").find(json)
        return m?.groupValues?.getOrNull(1).orEmpty()
    }

    fun reset() {
        runCatching { recognizer?.reset() }
    }

    fun release() {
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
        _ready.value = false
        scope.cancel()
    }
}
