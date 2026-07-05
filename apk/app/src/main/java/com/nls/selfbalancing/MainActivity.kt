package com.nls.selfbalancing

import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var engine: BalancerEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var connectBtn: Button? = null
    private var balanceBtn: Button? = null
    private var stopBtn: Button? = null
    private var roundText: TextView? = null
    private var algoText: TextView? = null
    private var progress: ProgressBar? = null
    private var bandsContainer: LinearLayout? = null
    private var logContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = BalancerEngine(applicationContext)
        engine.initialize()
        bindViews()
        setupCallbacks()
        updateUI()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        connectBtn = findViewById(R.id.connectBtn)
        balanceBtn = findViewById(R.id.balanceBtn)
        stopBtn = findViewById(R.id.stopBtn)
        roundText = findViewById(R.id.roundText)
        algoText = findViewById(R.id.algoText)
        progress = findViewById(R.id.progress)
        bandsContainer = findViewById(R.id.bandsContainer)
        logContainer = findViewById(R.id.logContainer)

        connectBtn?.setOnClickListener { onConnect() }
        balanceBtn?.setOnClickListener { onBalance() }
        stopBtn?.setOnClickListener { onStopClick() }
    }

    private fun setupCallbacks() {
        engine.onRound = { r -> runOnUi { roundText?.text = "第${r}轮" } }
        engine.onStatus = { s -> runOnUi { algoText?.text = s } }
        engine.onProgress = { cur, max -> runOnUi { progress?.also { it.progress = cur; it.max = max } } }
        engine.onLog = { msg -> runOnUi { addLog(msg) } }
    }

    private fun onConnect() {
        if (engine.isConnected) {
            engine.disconnect()
            updateUI()
            return
        }
        runOnUi { connectBtn?.isEnabled = false; connectBtn?.text = "连接中…" }
        engine.connect { ok, msg ->
            runOnUi {
                addLog(msg)
                updateUI()
            }
        }
    }

    private fun onBalance() {
        if (engine.isPlaying) { engine.stop(); updateUI(); return }
        engine.startBalance()
        updateUI()
    }

    private fun onStopClick() {
        engine.stop()
        updateUI()
    }

    private fun updateUI() {
        val conn = engine.isConnected
        statusDot?.setBackgroundResource(if (conn) android.R.color.holo_green_light else android.R.color.darker_gray)
        statusText?.text = if (conn) "手环已连接" else "未连接"
        connectBtn?.also {
            it.isEnabled = true
            it.text = if (conn) "断开" else "连接手环"
        }
        balanceBtn?.text = if (engine.isPlaying) "⏸ 停止" else "▶ 启动平衡"
    }

    private fun addLog(msg: String) {
        val ctx = this
        val tv = TextView(ctx).apply {
            text = msg; textSize = 12f
            val color = when {
                msg.contains("⚡") -> android.R.color.holo_orange_light
                msg.contains("✓") -> android.R.color.holo_green_light
                msg.contains("──") -> android.R.color.holo_blue_light
                else -> android.R.color.darker_gray
            }
            setTextColor(ContextCompat.getColor(ctx, color))
            setPadding(4, 2, 4, 2)
        }
        logContainer?.addView(tv, 0)
        val count = logContainer?.childCount ?: 0
        if (count > 50) logContainer?.removeViewAt(count - 1)
    }

    private fun runOnUi(action: () -> Unit) {
        mainHandler.post(action)
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }
}
