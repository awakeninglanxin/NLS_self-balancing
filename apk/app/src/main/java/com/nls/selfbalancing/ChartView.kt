package com.nls.selfbalancing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    enum class Mode { RADAR, BANDS, RIDGE, ALGO_COMPARE }
    var mode = Mode.RIDGE

    // Data for bands/ridge
    var deltaData: List<BandDelta> = emptyList()
    data class BandDelta(val b9: Int, val organ: String, val wuxing: String, val delta: Double)

    // Aggregated wuxing for radar
    var wuxingData: Map<String, Double> = emptyMap()

    // Algo compare stats: algoName -> (imp, wors)
    var algoCompareData: List<AlgoBar> = emptyList()
    data class AlgoBar(val algoKey: String, val name: String, val imp: Int, val wors: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = Color.parseColor("#0d0d1a") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222233"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555"); textSize = 24f; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0d0d1a"))
        when (mode) {
            Mode.RADAR -> drawRadar(canvas)
            Mode.BANDS -> drawBands(canvas)
            Mode.RIDGE -> drawRidge(canvas)
            Mode.ALGO_COMPARE -> drawAlgoCompare(canvas)
        }
    }

    private fun drawRadar(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2 + 10f; val r = min(w, h) * 0.35f

        val elements = listOf("火", "土", "金", "水", "木")
        val colors = mapOf("火" to "ff4444", "土" to "ffaa00", "金" to "ffff44", "水" to "4488ff", "木" to "44ff44")
        val angles = elements.mapIndexed { i, _ -> -PI / 2 + i * 2 * PI / 5 }

        // Grid circles
        for (g in 1..3) {
            val path = Path()
            for (i in 0..5) {
                val a = -PI / 2 + i * 2 * PI / 5
                val px = cx + r * g / 3 * cos(a).toFloat()
                val py = cy + r * g / 3 * sin(a).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, gridPaint)
        }

        // Axes
        for (a in angles) {
            canvas.drawLine(cx, cy, cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), gridPaint)
            textPaint.textSize = 26f
            canvas.drawText(elements[angles.indexOf(a)], cx + (r + 30f) * cos(a).toFloat(),
                cy + (r + 30f) * sin(a).toFloat() + 8f, textPaint)
        }

        // Radar polygon
        if (wuxingData.isNotEmpty()) {
            val path = Path()
            for ((i, a) in angles.withIndex()) {
                val val_ = (wuxingData[elements[i]] ?: 0.0).coerceIn(-100.0, 100.0)
                val ratio = (val_ + 100) / 200.0
                val px = cx + r * ratio.toFloat() * cos(a).toFloat()
                val py = cy + r * ratio.toFloat() * sin(a).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#4400ff88")
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.parseColor("#00ff88")
            canvas.drawPath(path, paint)
        } else {
            paint.color = Color.parseColor("#555555")
            paint.textSize = 32f
            canvas.drawText("等待扫描数据…", cx, cy + 8f, paint)
        }
    }

    private fun drawBands(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (deltaData.isEmpty()) {
            paint.color = Color.parseColor("#555555"); paint.textSize = 30f
            canvas.drawText("等待扫描数据…", w / 2, h / 2 + 8f, paint)
            return
        }

        val midY = h / 2; val barW = (w - 100f) / deltaData.size.coerceAtLeast(1)
        val maxD = deltaData.maxOfOrNull { abs(it.delta) }?.coerceAtLeast(1.0) ?: 1.0

        // Baseline
        paint.color = Color.parseColor("#333344"); paint.strokeWidth = 1f
        canvas.drawLine(50f, midY, w - 50f, midY, paint)
        paint.strokeWidth = 0f

        // Divider lines
        paint.color = Color.parseColor("#22223355"); paint.strokeWidth = 1f
        canvas.drawLine(50f + (w - 100f) * 9f / 18f, 0f, 50f + (w - 100f) * 9f / 18f, h, paint)

        for ((i, d) in deltaData.withIndex()) {
            val x = 50f + i * barW + barW / 2
            val barH = (abs(d.delta) / maxD * (h / 2 - 20f)).toFloat()
            paint.color = if (d.delta > 0) Color.parseColor("#44ff4444") else Color.parseColor("#444488ff")
            paint.style = Paint.Style.FILL
            canvas.drawRect(x - barW / 2 + 2f, if (d.delta > 0) midY - barH else midY, x + barW / 2 - 2f, if (d.delta > 0) midY else midY + barH, paint)

            // B9 label
            if (deltaData.size <= 18) {
                paint.color = Color.parseColor("#555555"); paint.textSize = 16f
                canvas.save()
                canvas.rotate(90f, x, midY)
                canvas.drawText("${d.b9}", x, midY + 2f, paint)
                canvas.restore()
            }
        }

        // Labels
        paint.color = Color.parseColor("#666666"); paint.textSize = 20f
        canvas.drawText("b9↑ 高频", 50f, 16f, paint)
        canvas.drawText("低频 ↓", w - 50f, 16f, paint)
        paint.color = Color.parseColor("#00ff88"); paint.textSize = 22f
        canvas.drawText("频段偏差 (红↑偏盛 蓝↓不足)", w / 2, h - 6f, paint)
    }

    private fun drawRidge(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (deltaData.isEmpty()) {
            paint.color = Color.parseColor("#555555"); paint.textSize = 30f
            canvas.drawText("等待扫描数据…", w / 2, h / 2 + 8f, paint)
            return
        }

        val midY = h / 2; val barW = (w - 40f) / deltaData.size
        val sorted = deltaData.sortedBy { it.b9 }
        val maxD = sorted.maxOfOrNull { abs(it.delta) }?.coerceAtLeast(1.0) ?: 1.0

        // Baseline
        paint.color = Color.parseColor("#333344"); paint.strokeWidth = 1f
        canvas.drawLine(20f, midY, w - 20f, midY, paint)

        // Band divider at ~1.8MHz (b9≈18)
        val idx18 = sorted.indexOfFirst { it.b9 >= 18 }
        if (idx18 > 0) {
            val x = 20f + idx18 * barW
            paint.color = Color.parseColor("#ffffff15"); paint.strokeWidth = 1f
            canvas.drawLine(x, 0f, x, h, paint)
            paint.color = Color.parseColor("#ffffff44"); paint.textSize = 18f
            canvas.drawText("~1.8M", x + 4f, 16f, paint)
        }

        for ((i, d) in sorted.withIndex()) {
            val x = 20f + i * barW + barW / 2
            val barH = (abs(d.delta) / maxD * (h / 2 - 10f)).toFloat()
            paint.color = if (d.delta > 0) Color.parseColor("#77ff4444") else Color.parseColor("#774488ff")
            paint.style = Paint.Style.FILL
            canvas.drawRect(x - barW / 2 + 1f, if (d.delta > 0) midY - barH else midY, x + barW / 2 - 1f, if (d.delta > 0) midY else midY + barH, paint)
        }

        paint.color = Color.parseColor("#00ff88"); paint.textSize = 22f
        canvas.drawText("频谱脊线 (红↑偏盛 蓝↓不足)", w / 2, h - 6f, paint)
    }

    private fun drawAlgoCompare(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (algoCompareData.isEmpty()) {
            paint.color = Color.parseColor("#555555"); paint.textSize = 30f
            canvas.drawText("等待统计…", w / 2, h / 2 + 8f, paint)
            return
        }

        val barH = (h - 60f) / algoCompareData.size.coerceAtLeast(1)
        val colors = intArrayOf(
            Color.parseColor("#ff4444"), Color.parseColor("#ffaa00"),
            Color.parseColor("#ffdd00"), Color.parseColor("#44ff44"),
            Color.parseColor("#4488ff"), Color.parseColor("#aa44ff"),
            Color.parseColor("#ff44aa")
        )

        for ((i, d) in algoCompareData.withIndex()) {
            val y = 30f + i * barH
            val total = (d.imp + d.wors).coerceAtLeast(1)
            val rate = d.imp.toFloat() / total
            val fullW = w - 200f

            // Label
            paint.color = Color.parseColor("#aaa"); paint.textSize = 22f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("${d.name}", 10f, y + barH / 2 + 7f, paint)

            // Bar background
            paint.color = Color.parseColor("#222233")
            paint.style = Paint.Style.FILL
            canvas.drawRect(130f, y + 2f, 130f + fullW, y + barH - 2f, paint)

            // Bar fill
            paint.color = colors[i % colors.size]
            canvas.drawRect(130f, y + 2f, 130f + fullW * rate, y + barH - 2f, paint)

            // Rate text
            paint.color = Color.parseColor("#00ff88"); paint.textSize = 18f
            paint.textAlign = Paint.Align.LEFT
            val rateStr = "%.0f%%  ↑%d ↓%d".format(rate * 100, d.imp, d.wors)
            canvas.drawText(rateStr, 136f, y + barH / 2 + 6f, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.parseColor("#00ff88"); paint.textSize = 20f
        canvas.drawText("七维算法对比 (改善率)", w / 2, 20f, paint)
    }

    fun setTab(m: Mode) { mode = m; invalidate() }
}
