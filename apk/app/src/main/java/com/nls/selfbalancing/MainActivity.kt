package com.nls.selfbalancing

import android.app.AlertDialog
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
    private lateinit var balanceBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var roundText: TextView
    private lateinit var algoText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bandsContainer: LinearLayout
    private lateinit var logContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // ★ 全局崩溃捕获
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            val sw = StringWriter()
            ex.printStackTrace(PrintWriter(sw))
            val msg = "崩溃: ${ex.javaClass.simpleName}\n${ex.message ?: ""}\n${sw.toString().take(500)}"
            try {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("💥 闪退诊断")
                    .setMessage(msg.take(800))
                    .setPositiveButton("知道了") { _, _ -> oldHandler?.uncaughtException(thread, ex) }
                    .setCancelable(false)
                    .show()
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

        connectBtn.setOnClickListener { onConnect() }
        balanceBtn.setOnClickListener { onBalance() }
        stopBtn.setOnClickListener { engine.stop(); updateUI() }

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
    }

    private fun setupCallbacks() {
        engine.onRound = { r -> mainHandler.post { roundText.text = "第${r}轮" } }
        engine.onStatus = { s -> mainHandler.post { algoText.text = s } }
        engine.onProgress = { cur, max ->
            mainHandler.post { progress.progress = cur; progress.max = max }
        }
        engine.onLog = { msg -> mainHandler.post { addLog(msg) } }
    }

    private fun onConnect() {
        if (engine.isConnected) { engine.disconnect(); updateUI(); return }
        connectBtn.isEnabled = false; connectBtn.text = "连接中…"
        engine.connect { ok, msg ->
            mainHandler.post { addLog(msg); updateUI() }
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
        connectBtn.text = if (conn) "断开" else "连接手环"
        balanceBtn.text = if (engine.isPlaying) "⏸ 停止" else "▶ 启动平衡"
    }

    private fun addLog(msg: String) {
        val tv = TextView(this).apply {
            text = msg; textSize = 12f
            val color = when {
                msg.contains("⚡") -> android.R.color.holo_orange_light
                msg.contains("✓") -> android.R.color.holo_green_light
                msg.contains("──") -> android.R.color.holo_blue_light
                else -> android.R.color.darker_gray
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
            setPadding(4, 2, 4, 2)
        }
        logContainer.addView(tv, 0)
        if (logContainer.childCount > 50) logContainer.removeViewAt(logContainer.childCount - 1)
    }

    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}
