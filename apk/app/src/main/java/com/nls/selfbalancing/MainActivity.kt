package com.nls.selfbalancing

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val engine = BalancerEngine(this)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var balanceBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var roundText: TextView
    private lateinit var algoText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bandsContainer: LinearLayout
    private lateinit var logContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.initialize()  // 必须在 super.onCreate 之后注册 receiver
        setContentView(R.layout.activity_main)
        initViews()
        setupEngine()
        updateUI()
    }

    private fun initViews() {
        statusDot = findViewById<View>(R.id.statusDot)
        statusText = findViewById<TextView>(R.id.statusText)
        connectBtn = findViewById<Button>(R.id.connectBtn)
        balanceBtn = findViewById<Button>(R.id.balanceBtn)
        stopBtn = findViewById<Button>(R.id.stopBtn)
        roundText = findViewById<TextView>(R.id.roundText)
        algoText = findViewById(R.id.algoText)
        progress = findViewById(R.id.progress)
        bandsContainer = findViewById(R.id.bandsContainer)
        logContainer = findViewById(R.id.logContainer)

        connectBtn.setOnClickListener {
            if (engine.isConnected) { engine.disconnect(); updateUI() }
            else {
                runOnUiThread { connectBtn.text = "连接中…"; connectBtn.isEnabled = false }
                engine.connect { ok, msg ->
                    runOnUiThread {
                        addLog(msg)
                        updateUI()
                    }
                }
            }
        }
        balanceBtn.setOnClickListener {
            if (engine.isPlaying) { engine.stop(); updateUI(); return@setOnClickListener }
            engine.startBalance(); updateUI()
        }
        stopBtn.setOnClickListener { engine.stop(); updateUI() }
    }

    private fun setupEngine() {
        engine.onRound = { r -> runOnUiThread { roundText.text = "第${r}轮" } }
        engine.onStatus = { s -> runOnUiThread { algoText.text = s } }
        engine.onProgress = { cur, max ->
            runOnUiThread { progress.progress = cur; progress.max = max }
        }
        engine.onLog = { msg -> runOnUiThread { addLog(msg) } }
    }

    private fun updateUI() {
        val conn = engine.isConnected
        statusDot.setBackgroundResource(
            if (conn) android.R.color.holo_green_light else android.R.color.darker_gray
        )
        statusText.text = if (conn) "手环已连接" else "未连接"
        connectBtn.text = if (conn) "断开" else "连接手环"
        connectBtn.isEnabled = true
        balanceBtn.text = if (engine.isPlaying) "⏸ 停止" else "▶ 启动平衡"
    }

    private fun addLog(msg: String) {
        val tv = TextView(this).apply {
            text = msg; textSize = 12f
            setTextColor(if (msg.contains("⚡")) ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light)
            else if (msg.contains("✓")) ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light)
            else if (msg.contains("──")) ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_light)
            else ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            setPadding(4, 2, 4, 2)
        }
        logContainer.addView(tv, 0)
        while (logContainer.childCount > 50) logContainer.removeViewAt(logContainer.childCount - 1)
    }

    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}
