package com.example.callguard.testapp.domain.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * 개입 경고음 — 두 개입 조건이 공유하는 공통 자극.
 *
 * 오디오 리소스 파일 대신 사인파를 직접 합성한다. 파일을 쓰면 인코딩·기기별 디코더에 따라
 * 실제 출력 음량과 시작 지연이 달라지는데, 경고음이 반응시간(RT) 측정의 기준점 중 하나라
 * 참가자 간 편차를 최소화해야 하기 때문이다.
 *
 * 패턴: 880Hz 짧은 톤 2회. 문헌(시각장애인 청각 경고 연구)에서 참가자들이
 * "경고임을 구분할 수 있게 톤을 1~2회 울려 달라"고 직접 요구한 형태를 따랐다.
 */
object AlertTone {

    private const val SAMPLE_RATE = 44100
    private const val FREQ_HZ = 880.0
    private const val TONE_MS = 220
    private const val GAP_MS = 120

    fun play() {
        thread(isDaemon = true) {
            runCatching { playBlocking() }
        }
    }

    private fun playBlocking() {
        val samples = buildPattern()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 통화 중에도 확실히 들리도록 알림 용도로 재생한다.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(samples, 0, samples.size)
        track.play()

        val durationMs = (samples.size * 1000L / SAMPLE_RATE) + 150
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { track.stop() }
            runCatching { track.release() }
        }, durationMs)
    }

    private fun buildPattern(): ShortArray {
        val toneLen = SAMPLE_RATE * TONE_MS / 1000
        val gapLen = SAMPLE_RATE * GAP_MS / 1000
        val out = ShortArray(toneLen * 2 + gapLen)

        for (repeat in 0 until 2) {
            val offset = repeat * (toneLen + gapLen)
            for (i in 0 until toneLen) {
                // 시작·끝을 부드럽게 깎아 클릭 잡음을 없앤다 (딸깍 소리가 톤보다 크게 들리는 것 방지)
                val fade = when {
                    i < 400 -> i / 400.0
                    i > toneLen - 400 -> (toneLen - i) / 400.0
                    else -> 1.0
                }
                val v = sin(2.0 * PI * FREQ_HZ * i / SAMPLE_RATE) * fade * 0.55
                out[offset + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
