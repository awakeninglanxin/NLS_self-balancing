package com.nls.selfbalancing

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {
    private lateinit var engine: BalancerEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var connectBtn: TextView
    private lateinit var calBtn: TextView
    private lateinit var balanceBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var roundText: TextView
    private lateinit var algoText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logContainer: LinearLayout
    private lateinit var chartView: ChartView

    private lateinit var tabRadar: TextView
    private lateinit var tabBands: TextView
    private lateinit var tabRidge: TextView

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
        setupCallbacks()
        setupTabs()

        connectBtn.setOnClickListener { onConnect() }
        calBtn.setOnClickListener { onCalibrate() }
        balanceBtn.setOnClickListener { onBalance() }
        stopBtn.setOnClickListener { engine.stop(); updateUI() }

        updateUI()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        connectBtn = findViewById(R.id.connectBtn)
        calBtn = findViewById(R.id.calBtn)
        balanceBtn = findViewById(R.id.balanceBtn)
        stopBtn = findViewById(R.id.stopBtn)
        roundText = findViewById(R.id.roundText)
        algoText = findViewById(R.id.algoText)
        progress = findViewById(R.id.progress)
        logContainer = findViewById(R.id.logContainer)
        chartView = findViewById(R.id.chartView)
        tabRadar = findViewById(R.id.tabRadar)
        tabBands = findViewById(R.id.tabBands)
        tabRidge = findViewById(R.id.tabRidge)
    }

    private fun setupTabs() {
        tabRadar.setOnClickListener { switchTab(ChartView.Mode.RADAR) }
        tabBands.setOnClickListener { switchTab(ChartView.Mode.BANDS) }
        tabRidge.setOnClickListener { switchTab(ChartView.Mode.RIDGE) }
        switchTab(ChartView.Mode.BANDS)  // default
    }

    private fun switchTab(mode: ChartView.Mode) {
        chartView.setTab(mode)
        tabRadar.setTextColor(if (mode == ChartView.Mode.RADAR) 0xFF00e5a0.toInt() else 0xFF888888.toInt())
        tabBands.setTextColor(if (mode == ChartView.Mode.BANDS) 0xFF00e5a0.toInt() else 0xFF888888.toInt())
        tabRidge.setTextColor(if (mode == ChartView.Mode.RIDGE) 0xFF00e5a0.toInt() else 0xFF888888.toInt())
    }

    private fun setupCallbacks() {
        engine.onRound = { r -> mainHandler.post { roundText.text = "第${r}轮" } }
        engine.onStatus = { s -> mainHandler.post { algoText.text = s } }
        engine.onProgress = { cur, max -> mainHandler.post { progress.progress = cur; progress.max = max } }
        engine.onLog = { msg -> mainHandler.post { addLog(msg) } }
        engine.onChart = { deltas, wuxing ->
            mainHandler.post {
                chartView.deltaData = deltas.map {
                    ChartView.BandDelta(it.b9, it.organ, it.wuxing, it.delta)
                }
                chartView.wuxingData = wuxing
                chartView.invalidate()
            }
        }
    }

    private fun onConnect() {
        if (engine.isConnected) { engine.disconnect(); updateUI(); return }
        connectBtn.isEnabled = false; connectBtn.text = "连接中…"
        engine.connect { ok, msg ->
            mainHandler.post { addLog(msg); updateUI() }
        }
    }

    private fun onCalibrate() {
        if (!engine.isConnected) { addLog("⚠ 请先连接手环"); return }
        calBtn.isEnabled = false; calBtn.text = "校准中…"
        addLog("📐 开始校准…传感器请悬空")
        engine.calibrate { ok, msg ->
            mainHandler.post {
                addLog(msg)
                calBtn.isEnabled = true; calBtn.text = "📐 校准"
                updateUI()
            }
        }
    }

    private fun onBalance() {
        if (engine.isPlaying) { engine.stop(); updateUI(); return }
        if (!engine.isConnected) { addLog("⚠ 请先连接手环"); return }
        engine.startBalance(); updateUI()
    }

    private fun updateUI() {
        val conn = engine.isConnected
        statusDot.setBackgroundResource(if (conn) android.R.color.holo_green_light else android.R.color.darker_gray)
        statusText.text = if (conn) "手环已连接" else "未连接"
        connectBtn.isEnabled = true
        connectBtn.text = if (conn) "断开" else "连接"
        calBtn.isEnabled = conn
        val cal = engine.isCalibrated
        balanceBtn.text = if (engine.isPlaying) "⏸ 停止" else "▶ 启动平衡"
        balanceBtn.background = if (cal) null else null  // keep default
        if (!cal) roundText.text = "待校准"
    }

    private fun addLog(msg: String) {
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

    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}
