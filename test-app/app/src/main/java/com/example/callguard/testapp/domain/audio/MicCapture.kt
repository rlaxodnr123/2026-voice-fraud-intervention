package com.example.callguard.testapp.domain.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * 마이크를 **한 번만** 열고 PCM을 두 갈래로 흘려보낸다.
 *   ① Vosk STT — 참가자 발화 자동 인식 (주 지표: 정보 유출 여부)
 *   ② WAV 파일 — 사후 검증용 녹음 (STT가 틀렸을 때 되돌아가 확인할 근거)
 *
 * 마이크는 프로세스 간에도 배타적이라 AudioRecord와 MediaRecorder를 동시에 쓸 수 없다.
 * 따라서 "STT도 하고 녹음도 한다"는 요구는 이렇게 한 소스에서 팬아웃하는 방식으로만 된다.
 *
 * 개입으로 마이크가 "차단"돼도 캡처는 계속된다. 실험에서 중요한 관측이 바로
 * **차단 이후에도 참가자가 계속 말했는가**이기 때문이다. 차단은 참가자에게 보이는
 * 상태와 "상대에게 전달됨" 판정에만 적용되고, 기록은 끊기지 않는다.
 */
class MicCapture(
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "MicCapture"

    companion object {
        /** Vosk 한국어 모델이 기대하는 샘플레이트 */
        const val SAMPLE_RATE = 16000
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @Volatile private var running = false
    @Volatile private var writer: WavWriter? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(recordTo: File?): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("마이크 버퍼 크기를 가져오지 못했습니다 (minBufferSize=" + minBuf + ")")
            return false
        }
        val bufferSize = minBuf * 2

        val ar = try {
            AudioRecord(
                // VOICE_COMMUNICATION 소스는 단말의 에코 제거·잡음 억제를 태워 준다.
                // 공격자 음성이 수화부로 나가 마이크로 되들어오는 양을 줄여
                // STT가 공격자 대사를 참가자 발화로 오인할 확률을 낮춘다.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            onError("마이크를 열지 못했습니다: " + (e.message ?: "unknown"))
            return false
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { ar.release() }
            onError("마이크 초기화에 실패했습니다. 권한과 다른 앱의 마이크 점유를 확인하세요.")
            return false
        }

        enableEffects(ar.audioSessionId)

        val w = recordTo?.let { WavWriter(it, SAMPLE_RATE) }
        try {
            w?.open()
        } catch (e: Exception) {
            // 녹음 파일을 못 열어도 STT와 실험 자체는 계속돼야 한다.
            Log.e(TAG, "녹음 파일 생성 실패", e)
            onError("녹음 파일을 만들지 못했습니다 — STT만으로 진행합니다: " + (e.message ?: ""))
        }
        writer = w

        record = ar
        running = true
        ar.startRecording()

        worker = thread(name = "MicCapture", isDaemon = true) {
            val buf = ByteArray(bufferSize)
            while (running) {
                val read = try {
                    ar.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    Log.e(TAG, "마이크 읽기 실패", e)
                    break
                }
                if (read <= 0) continue
                runCatching { writer?.write(buf, read) }
                runCatching { onPcm(buf.copyOf(read), read) }
            }
        }
        Log.d(TAG, "마이크 캡처 시작 (16kHz mono, 녹음=" + (recordTo?.name ?: "없음") + ")")
        return true
    }

    private fun enableEffects(sessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
            }
        }
    }

    /** @return 녹음 파일과 길이(ms). 녹음하지 않았으면 null */
    fun stop(): Pair<File, Long>? {
        if (!running) return null
        running = false
        runCatching { worker?.join(1500) }
        worker = null

        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null

        val w = writer
        writer = null
        return w?.let {
            val ms = it.close()
            it.target to ms
        }
    }
}
