package com.nls.selfbalancing

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * NLS 治疗音频反馈: b9→432Hz调律频率→纯正弦短音
 * 分频降频: 7.3728MHz晶振 / 2^((b9-14)/2) → 多次÷2 至人耳范围
 */
object AudioTone {
    private var track: AudioTrack? = null
    private const val SAMPLE_RATE = 44100
    private const val A4 = 432.0   // 疗愈频率锚点

    /** b9(14-31) → 可听频率(Hz), 432律五声音阶量化 */
    fun b9toHz(b9: Int): Double {
        // 晶振基频 7.3728MHz, b9=14→7.37MHz, 每+2→÷2
        var hz = 7372800.0 / Math.pow(2.0, (b9 - 14) / 2.0)
        // 反复降频到人耳范围(27-6912Hz, A0~A8)
        while (hz > 6912) hz /= 2.0
        while (hz < 27) hz *= 2.0
        return quantize432(hz)
    }

    /** 量化到432律五声音阶 */
    private fun quantize432(hz: Double): Double {
        val semi = 12.0 * kotlin.math.log2(hz / A4)
        val pent = intArrayOf(0, 2, 4, 7, 9)  // 大调五音
        val oct = semi.toInt() / 12
        var best = A4 * Math.pow(2.0, oct.toDouble())
        for (d in pent) {
            val f = A4 * Math.pow(2.0, (oct * 12 + d) / 12.0)
            if (kotlin.math.abs(f - hz) < kotlin.math.abs(best - hz)) best = f
        }
        return best
    }

    fun play(b9: Int, ms: Long) {
        stop()
        val freq = b9toHz(b9)
        val samples = (SAMPLE_RATE * ms / 1000.0).toInt().coerceAtLeast(64)
        val buf = ShortArray(samples)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            // 正弦 × 指数衰减包络
            val env = kotlin.math.exp(-t * 5.0 / (ms / 1000.0))
            buf[i] = (sin(2.0 * PI * freq * t) * 4096 * env).toInt().toShort()
        }
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.size * 2).build()
            track?.play()
            track?.write(buf, 0, buf.size)
        } catch (_: Exception) {}
    }

    fun stop() { try { track?.stop(); track?.release(); track = null } catch (_: Exception) {} }
}
