package com.nls.selfbalancing

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var engine: BalancerEngine
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var connectBtn: Button
    private lateinit var balanceBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPct: TextView
    private lateinit var therapyInfo: TextView
    private lateinit var roundInfo: TextView
    private lateinit var logContainer: LinearLayout

    private var uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = BalancerEngine(this)
        bindViews()
        setupListeners()

        engine.onStatus = { msg -> uiHandler.post { statusSub.text = msg; therapyInfo.text = msg } }
        engine.onProgress = { cur, total ->
            uiHandler.post {
                val pct = if (total > 0) (cur.toFloat() / total * 100).toInt() else 0
                progressBar.progress = pct
                progressPct.text = "$pct%"
            }
        }
        engine.onRound = { round -> uiHandler.post { roundInfo.text = "第 $round 轮" } }
        engine.onLog = { msg -> uiHandler.post { addLog(msg) } }
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        statusSub = findViewById(R.id.status_sub)
        connectBtn = findViewById(R.id.connect_btn)
        balanceBtn = findViewById(R.id.balance_btn)
        stopBtn = findViewById(R.id.stop_btn)
        progressBar = findViewById(R.id.progress_bar)
        progressPct = findViewById(R.id.progress_pct)
        therapyInfo = findViewById(R.id.therapy_info)
        roundInfo = findViewById(R.id.round_info)
        logContainer = findViewById(R.id.log_container)
    }

    private fun setupListeners() {
        connectBtn.setOnClickListener {
            if (engine.isConnected) { engine.disconnect(); updateUI(); return@setOnClickListener }
            connectBtn.isEnabled = false; connectBtn.text = "连接中…"
            engine.connect { ok, msg ->
                uiHandler.post {
                    connectBtn.isEnabled = true
                    connectBtn.text = if (ok) "断开手环" else "连接手环 (USB OTG)"
                    statusText.text = if (ok) "手环已连接" else "未连接"
                    if (!ok) statusSub.text = msg
                    updateUI()
                }
            }
        }
        balanceBtn.setOnClickListener {
            if (engine.isPlaying) { engine.stop(); updateUI(); return@setOnClickListener }
            engine.startBalance()
            updateUI()
        }
        stopBtn.setOnClickListener { engine.stop(); updateUI() }
    }

    private fun updateUI() {
        val conn = engine.isConnected
        statusDot.setBackgroundResource(if (conn) android.R.color.holo_green_light else android.R.color.darker_gray)
        statusText.text = if (conn) "手环已连接" else "未连接"
        connectBtn.text = if (conn) "断开手环" else "连接手环 (USB OTG)"
        balanceBtn.isEnabled = conn
        stopBtn.visibility = if (engine.isPlaying) View.VISIBLE else View.GONE
        if (!engine.isPlaying) { progressBar.progress = 0; progressPct.text = "0%" }
    }

    private fun addLog(msg: String) {
        val tv = TextView(this).apply {
            text = msg; textSize = 10f; setTextColor(android.graphics.Color.parseColor("#666"))
            setPadding(4, 2, 4, 2)
        }
        logContainer.addView(tv, 0)
        while (logContainer.childCount > 30) logContainer.removeViewAt(logContainer.childCount - 1)
    }

    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}
