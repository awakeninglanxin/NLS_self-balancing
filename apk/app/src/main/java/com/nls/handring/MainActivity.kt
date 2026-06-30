package com.nls.handring

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var engine: TherapyEngine
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var connectBtn: Button
    private lateinit var progContainer: ScrollView
    private lateinit var progLayout: LinearLayout
    private lateinit var powerContainer: LinearLayout
    private lateinit var playBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var playAllBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var therapyInfo: TextView
    private lateinit var intervalSeek: SeekBar
    private lateinit var intervalLabel: TextView
    private lateinit var repeatSeek: SeekBar
    private lateinit var repeatLabel: TextView

    private var selProg: String? = null
    private var selPower: Int? = null
    private var looping = true
    private var uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = TherapyEngine(this)
        bindViews()
        buildProgramList()
        setupListeners()

        engine.onStatus = { msg -> uiHandler.post { statusSub.text = msg; therapyInfo.text = msg } }
        engine.onProgress = { cur, total ->
            uiHandler.post { progressBar.progress = (cur.toFloat() / total * 100).toInt() }
        }
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        statusSub = findViewById(R.id.status_sub)
        connectBtn = findViewById(R.id.connect_btn)
        progContainer = findViewById(R.id.program_scroll)
        progLayout = findViewById(R.id.program_list)
        powerContainer = findViewById(R.id.power_chips)
        playBtn = findViewById(R.id.play_btn)
        pauseBtn = findViewById(R.id.pause_btn)
        stopBtn = findViewById(R.id.stop_btn)
        loopBtn = findViewById(R.id.loop_btn)
        playAllBtn = findViewById(R.id.playall_btn)
        progressBar = findViewById(R.id.progress_bar)
        therapyInfo = findViewById(R.id.therapy_info)
        intervalSeek = findViewById(R.id.interval_seek)
        intervalLabel = findViewById(R.id.interval_label)
        repeatSeek = findViewById(R.id.repeat_seek)
        repeatLabel = findViewById(R.id.repeat_label)
    }

    private fun buildProgramList() {
        val keys = TherapyData.programs.keys.sortedBy { TherapyData.programs[it]!!.b9 }
        for (key in keys) {
            val prog = TherapyData.programs[key]!!
            val btn = Button(this).apply {
                text = "${prog.organ}  ${prog.freqMhz}MHz  (${prog.cmds.size})"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                setBackgroundResource(android.R.color.transparent)
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(32, 18, 32, 18)
                setOnClickListener { selectProgram(key) }
            }
            progLayout.addView(btn)
        }
    }

    private fun selectProgram(key: String) { selProg = key; selPower = null; buildPowerChips(); updateUI() }
    private fun buildPowerChips() {
        powerContainer.removeAllViews()
        val prog = selProg?.let { TherapyData.programs[it] } ?: return
        val done = mutableSetOf<Int>()
        for (cmd in prog.cmds) {
            if (cmd.b11 in done) continue
            done.add(cmd.b11)
            val chip = Button(this).apply {
                text = "${cmd.b11}"; textSize = 11f; setPadding(24, 8, 24, 8)
                setOnClickListener { selectPower(cmd.b11) }
            }
            powerContainer.addView(chip)
        }
    }
    private fun selectPower(v: Int) { selPower = if (selPower == v) null else v; buildPowerChips() }

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
        playBtn.setOnClickListener {
            val key = selProg
            if (engine.isPlaying && engine.isPaused) { engine.resume(); updateUI(); return@setOnClickListener }
            if (engine.isPlaying) { engine.stop(); updateUI(); return@setOnClickListener }
            if (key == null) return@setOnClickListener
            engine.playProgram(key, selPower, looping)
            updateUI()
        }
        pauseBtn.setOnClickListener { engine.togglePause(); updateUI() }
        stopBtn.setOnClickListener { engine.stop(); updateUI() }
        loopBtn.setOnClickListener { looping = !looping; loopBtn.text = if (looping) "循环" else "单次" }
        playAllBtn.setOnClickListener {
            if (engine.isPlaying && engine.isPaused) { engine.resume(); updateUI(); return@setOnClickListener }
            if (engine.isPlaying) { engine.stop(); updateUI(); return@setOnClickListener }
            engine.playAllPrograms(looping)
            updateUI()
        }
        intervalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, b: Boolean) {
                intervalLabel.text = String.format("%.2fs", v / 1000.0)
                engine.setInterval(v.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        repeatSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, b: Boolean) {
                repeatLabel.text = "×$v"
                engine.setRepeat(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun updateUI() {
        val conn = engine.isConnected; val play = engine.isPlaying; val paused = engine.isPaused
        statusDot.setBackgroundResource(if (conn) android.R.color.holo_green_light else android.R.color.darker_gray)
        statusText.text = if (conn) "手环已连接" else "未连接"
        connectBtn.text = if (conn) "断开手环" else "连接手环 (USB OTG)"

        // Play+Pause+Stop 三态切换
        if (play) {
            playBtn.visibility = if (paused) View.VISIBLE else View.GONE
            pauseBtn.visibility = if (paused) View.GONE else View.VISIBLE
            stopBtn.visibility = View.VISIBLE
            playBtn.text = "继续"
        } else {
            playBtn.visibility = View.VISIBLE
            pauseBtn.visibility = View.GONE
            stopBtn.visibility = View.GONE
            playBtn.text = "开始"
        }
        playBtn.isEnabled = conn && (selProg != null || play)
        playAllBtn.isEnabled = conn
        if (!play) progressBar.progress = 0
    }

    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}
