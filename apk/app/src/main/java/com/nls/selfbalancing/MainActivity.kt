package com.nls.selfbalancing

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var engine: BalancerEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private var chartView: ChartView? = null
    private var tabRadar: TextView? = null
    private var tabRidge: TextView? = null
    private var tabCompare: TextView? = null

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var calBtn: TextView
    private lateinit var exportBtn: TextView
    private lateinit var connectBtn: TextView
    private lateinit var balanceBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var roundText: TextView
    private lateinit var algoText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logContainer: LinearLayout
    private lateinit var tabHost: LinearLayout
    private val logHistory = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            val sw = StringWriter(); ex.printStackTrace(PrintWriter(sw))
            val msg = "崩溃: ${ex.javaClass.simpleName}\n${ex.message ?: ""}\n${sw.toString().take(500)}"
            try {
                AlertDialog.Builder(this@MainActivity).setTitle("💥 闪退诊断").setMessage(msg.take(800))
                    .setPositiveButton("知道了") { _, _ -> oldHandler?.uncaughtException(thread, ex) }
                    .setCancelable(false).show()
            } catch (_: Exception) {
                try { Toast.makeText(this@MainActivity, msg.take(200), Toast.LENGTH_LONG).show(); Thread.sleep(3000) } catch (_: Exception) {}
                oldHandler?.uncaughtException(thread, ex)
            }
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = BalancerEngine(this)
        engine.initialize()

        bindViews()
        buildTabHost()
        setupCallbacks()

        connectBtn.setOnClickListener { onConnect() }
        calBtn.setOnClickListener { onCalibrate() }
        exportBtn.setOnClickListener { onExport() }
        balanceBtn.setOnClickListener { onBalance() }
        stopBtn.setOnClickListener { engine.stop(); updateUI() }

        updateUI()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        calBtn = findViewById(R.id.calBtn)
        exportBtn = findViewById(R.id.exportBtn)
        connectBtn = findViewById(R.id.connectBtn)
        balanceBtn = findViewById(R.id.balanceBtn)
        stopBtn = findViewById(R.id.stopBtn)
        roundText = findViewById(R.id.roundText)
        algoText = findViewById(R.id.algoText)
        progress = findViewById(R.id.progress)
        logContainer = findViewById(R.id.logContainer)
        tabHost = findViewById(R.id.tabHost)
    }

    private fun buildTabHost() {
        val ctx = this

        // Tab row — only 2 tabs
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 2, 0, 2)
        }
        fun makeTab(label: String, onClick: () -> Unit): TextView {
            return TextView(ctx).apply {
                text = label; textSize = 12f
                setTextColor(Color.parseColor("#888888"))
                setPadding(24, 8, 24, 8)
                setBackgroundColor(Color.parseColor("#111122"))
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        tabRadar = makeTab("☯ 五行") { switchTab(ChartView.Mode.RADAR) }
        tabRidge = makeTab("📈 脊线") { switchTab(ChartView.Mode.RIDGE) }
        tabCompare = makeTab("⚖ 对比") { switchTab(ChartView.Mode.ALGO_COMPARE) }
        tabRow.addView(tabRadar)
        tabRow.addView(tabRidge)
        tabRow.addView(tabCompare)

        // Chart area
        val chartFrame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#0d0d1a"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        }
        chartView = ChartView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        }
        chartFrame.addView(chartView)

        tabHost.addView(tabRow)
        tabHost.addView(chartFrame)
        switchTab(ChartView.Mode.RIDGE)
    }

    private fun switchTab(mode: ChartView.Mode) {
        chartView?.setTab(mode)
        val active = 0xFF00e5a0.toInt(); val inactive = 0xFF888888.toInt()
        tabRadar?.setTextColor(if (mode == ChartView.Mode.RADAR) active else inactive)
        tabRidge?.setTextColor(if (mode == ChartView.Mode.RIDGE) active else inactive)
        tabCompare?.setTextColor(if (mode == ChartView.Mode.ALGO_COMPARE) active else inactive)
    }

    private fun setupCallbacks() {
        engine.onRound = { r -> mainHandler.post { roundText.text = "第${r}轮" } }
        engine.onStatus = { s -> mainHandler.post { algoText.text = s } }
        engine.onProgress = { cur, max ->
            mainHandler.post { progress.progress = cur; progress.max = max }
        }
        engine.onLog = { msg -> mainHandler.post { addLog(msg) } }
        engine.onChart = { deltas, wuxing ->
            mainHandler.post {
                chartView?.let { cv ->
                    cv.deltaData = deltas.map { ChartView.BandDelta(it.b9, it.organ, it.wuxing, it.delta) }
                    cv.wuxingData = wuxing
                    cv.invalidate()
                }
            }
        }
        engine.onBatchReport = { batchNum, stats ->
            mainHandler.post {
                val bars = stats.map { (key, s) ->
                    val name = mapOf("original" to "🔗原版", "legacy" to "同频反相", "yinyang" to "☀☽双频",
                        "fusion" to "⚡融合", "schumann" to "🌍舒曼锚", "water" to "💧水共振", "jellium" to "⚛幻数")[key] ?: key
                    ChartView.AlgoBar(key, name, s.imp, s.wors)
                }.sortedByDescending { val t = it.imp + it.wors; if (t > 0) it.imp.toDouble() / t else 0.0 }
                chartView?.let { cv ->
                    cv.algoCompareData = bars
                    cv.invalidate()
                }
            }
        }
    }

    private fun onConnect() {
        if (engine.isConnected) { engine.disconnect(); updateUI(); return }
        connectBtn.isEnabled = false; connectBtn.text = "…"
        engine.connect { ok, msg -> mainHandler.post { addLog(msg); updateUI() } }
    }

    private fun onCalibrate() {
        if (!engine.isConnected) { addLog("⚠ 请先连接手环"); return }
        calBtn.isEnabled = false; calBtn.text = "校准中…"
        addLog("📐 校准中…传感器请悬空")
        engine.calibrate { ok, msg ->
            mainHandler.post { addLog(msg); calBtn.isEnabled = true; calBtn.text = "📐 校准"; updateUI() }
        }
    }

    private fun onBalance() {
        if (engine.isPlaying) { engine.stop(); updateUI(); return }
        engine.startBalance(); updateUI()
    }

    private fun updateUI() {
        val conn = engine.isConnected
        statusDot.setBackgroundResource(if (conn) android.R.color.holo_green_light else android.R.color.darker_gray)
        statusText.text = if (conn) "手环已连接" else "未连接"
        connectBtn.isEnabled = true
        connectBtn.text = if (conn) "断开" else "连接"
        balanceBtn.text = if (engine.isPlaying) "⏸ 停止" else "▶ 启动平衡"
    }

    private fun addLog(msg: String) {
        logHistory.add(msg)
        val tv = TextView(this).apply {
            text = msg; textSize = 11f
            val color = when {
                msg.contains("⚡") || msg.contains("📐") -> android.R.color.holo_orange_light
                msg.contains("✓") || msg.contains("✅") -> android.R.color.holo_green_light
                msg.contains("──") -> android.R.color.holo_blue_light
                msg.contains("⚠") -> android.R.color.holo_red_light
                else -> android.R.color.darker_gray
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
            setPadding(2, 1, 2, 1)
        }
        logContainer.addView(tv, 0)
        if (logContainer.childCount > 50) logContainer.removeViewAt(logContainer.childCount - 1)
    }

    private fun onExport() {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
            val filename = "NLS_Balance_${sdf.format(Date())}.txt"
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, filename)
            PrintWriter(file).use { pw ->
                pw.println("NLS 动态平衡仪 — 日志导出")
                pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                pw.println("=" .repeat(40))
                for (line in logHistory) pw.println(line)
                pw.println("=" .repeat(40))
                pw.println("文件路径: $file")
            }
            addLog("💾 已导出: $filename")
            Toast.makeText(this, "已保存: $file", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            addLog("导出失败: ${e.message}")
        }
    }

    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}
