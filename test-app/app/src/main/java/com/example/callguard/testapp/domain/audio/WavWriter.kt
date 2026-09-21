package com.example.callguard.testapp.domain.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * 16-bit PCM 스트림을 WAV 파일로 쓴다.
 *
 * MediaRecorder를 쓰지 않는 이유: 마이크는 한 번에 한 곳만 열 수 있어서
 * MediaRecorder(녹음)와 Vosk(STT)가 동시에 마이크를 잡을 수 없다.
 * [MicCapture]가 AudioRecord로 한 번만 열고 PCM을 여기와 STT로 함께 흘려보낸다.
 *
 * WAV 헤더의 길이 필드는 녹음이 끝나야 알 수 있으므로, 처음에 0으로 써 두고
 * [close]에서 되돌아가 채운다. 앱이 강제 종료돼 close가 불리지 못해도
 * 데이터 자체는 파일에 남아 있어 헤더만 복구하면 재생 가능하다.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private var raf: RandomAccessFile? = null
    private var dataBytes: Long = 0

    val target: File get() = file

    fun open() {
        file.parentFile?.mkdirs()
        val f = RandomAccessFile(file, "rw")
        f.setLength(0)
        f.write(header(0))
        raf = f
        dataBytes = 0
    }

    fun write(pcm: ByteArray, length: Int) {
        val f = raf ?: return
        f.write(pcm, 0, length)
        dataBytes += length
    }

    /** @return 기록된 오디오 길이(ms) */
    fun close(): Long {
        val f = raf ?: return 0
        raf = null
        return try {
            f.seek(0)
            f.write(header(dataBytes))
            f.close()
            durationMs()
        } catch (e: Exception) {
            runCatching { f.close() }
            durationMs()
        }
    }

    fun durationMs(): Long {
        val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
        if (bytesPerSecond == 0L) return 0
        return dataBytes * 1000 / bytesPerSecond
    }

    private fun header(dataLength: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalLength = 36 + dataLength

        val h = ByteArray(44)
        "RIFF".toByteArray().copyInto(h, 0)
        writeIntLE(h, 4, totalLength.toInt())
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        writeIntLE(h, 16, 16)                 // PCM 서브청크 크기
        writeShortLE(h, 20, 1)                // 포맷 = PCM
        writeShortLE(h, 22, channels)
        writeIntLE(h, 24, sampleRate)
        writeIntLE(h, 28, byteRate)
        writeShortLE(h, 32, blockAlign)
        writeShortLE(h, 34, bitsPerSample)
        "data".toByteArray().copyInto(h, 36)
        writeIntLE(h, 40, dataLength.toInt())
        return h
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
