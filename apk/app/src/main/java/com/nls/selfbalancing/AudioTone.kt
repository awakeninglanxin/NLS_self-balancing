package com.nls.selfbalancing

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * NLS 治疗音频反馈: 双声道(CH1左/CH2右) → 432Hz调律 → 正弦短音
 */
object AudioTone {
    private var track: AudioTrack? = null
    private const val SAMPLE_RATE = 44100
    private const val A4 = 432.0

    /** b9 → 可听频率, 432律五声音阶量化 */
    private fun b9toHz(b9: Int): Double {
        var hz = 7372800.0 / Math.pow(2.0, (b9 - 14) / 2.0)
        while (hz > 6912) hz /= 2.0
        while (hz < 27) hz *= 2.0
        val semi = 12.0 * kotlin.math.log2(hz / A4)
        val pent = intArrayOf(0, 2, 4, 7, 9)
        val oct = semi.toInt() / 12
        var best = A4 * Math.pow(2.0, oct.toDouble())
        for (d in pent) {
            val f = A4 * Math.pow(2.0, (oct * 12 + d) / 12.0)
            if (kotlin.math.abs(f - hz) < kotlin.math.abs(best - hz)) best = f
        }
        return best
    }

    /** 双声道播放: ch1B9→左声道, ch2B9→右声道 */
    fun play(ch1B9: Int, ch2B9: Int, ms: Long) {
        stop()
        val fL = b9toHz(ch1B9); val fR = b9toHz(ch2B9)
        val samples = (SAMPLE_RATE * ms / 1000.0).toInt().coerceAtLeast(64)
        val buf = ShortArray(samples * 2)  // 立体声: L,R 交错
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = kotlin.math.exp(-t * 5.0 / (ms / 1000.0))
            buf[i * 2] = (sin(2.0 * PI * fL * t) * 8192 * env).toInt().toShort()     // 左
            buf[i * 2 + 1] = (sin(2.0 * PI * fR * t) * 8192 * env).toInt().toShort() // 右
        }
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(buf.size * 2).build()
            track?.play()
            track?.write(buf, 0, buf.size)
        } catch (_: Exception) {}
    }

    fun stop() { try { track?.stop(); track?.release(); track = null } catch (_: Exception) {} }
}
