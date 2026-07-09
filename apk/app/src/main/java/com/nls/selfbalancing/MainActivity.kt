package com.nls.selfbalancing

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // Service 绑定
    private var service: BalancerService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as BalancerService.LocalBinder).getService()
            bound = true
            updateUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false; updateUI()
        }
    }

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
    private lateinit var originalBtn: TextView
    private lateinit var roundText: TextView
    private lateinit var algoText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bgTip: TextView
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

        // 启动+绑定前台服务
        val intent = Intent(this, BalancerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        bindViews()
        buildTabHost()

        // 桥接 Service 状态回调
        BalancerService.uiRound = { r -> mainHandler.post { roundText.text = "第${r}轮" } }
        BalancerService.uiStatus = { s -> mainHandler.post { algoText.text = s } }
        BalancerService.uiProgress = { cur, max ->
            mainHandler.post { progress.progress = cur; progress.max = max }
        }
        BalancerService.uiLog = { msg -> mainHandler.post { addLog(msg) } }
        BalancerService.uiChart = { deltas, wuxing ->
            mainHandler.post {
                chartView?.let { cv ->
                    cv.deltaData = deltas.map { ChartView.BandDelta(it.b9, it.organ, it.wuxing, it.delta) }
                    cv.wuxingData = wuxing
                    cv.invalidate()
                }
            }
        }
        BalancerService.uiBatchReport = { batchNum, stats ->
            mainHandler.post {
                val excl = ::engine.isInitialized && engine.excludeOriginal
                val filtered = if (excl) stats.filterKeys { it != "original" } else stats
                val bars = filtered.map { (key, s) ->
                    val name = mapOf("original" to "🔗原版", "legacy" to "同频反相", "yinyang" to "☀☽7族双频",
                        "fusion" to "⚡融合", "schumann" to "🌍舒曼锚", "water" to "💧水共振", "jellium" to "⚛幻数",
                        "multiharm" to "🎵多谐波")[key] ?: key
                    ChartView.AlgoBar(key, name, s.imp, s.wors)
                }.sortedByDescending { val t = it.imp + it.wors; if (t > 0) it.imp.toDouble() / t else 0.0 }
                chartView?.let { cv ->
                    cv.algoCompareData = bars
                    cv.invalidate()
                }
            }
        }

        connectBtn.setOnClickListener { onConnect() }
        calBtn.setOnClickListener { onCalibrate() }
        exportBtn.setOnClickListener { onExport() }
        balanceBtn.setOnClickListener { onBalance() }
        stopBtn.setOnClickListener { service?.stop(); updateUI() }

        // 一键排除/包括 🔗原版 算法
        originalBtn.setOnClickListener {
            val eng = ensureEngine() ?: return@setOnClickListener
            eng.excludeOriginal = !eng.excludeOriginal
            updateOriginalBtn()
            val msg = if (eng.excludeOriginal) "⚠ 已排除原版算法 (后续轮次跳过🔗原版)" else "✅ 已恢复原版算法 (下次轮次包括🔗原版)"
            addLog(msg)
            Toast.makeText(this, if (eng.excludeOriginal) "已排除🔗原版" else "已恢复🔗原版", Toast.LENGTH_SHORT).show()
        }

        // Android 13+ 通知权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    /**
     * 延迟初始化: 等待 Service 绑定完成（首个回调调用时 engine 已就绪）
     */
    private fun ensureEngine(): BalancerEngine? {
        val svc = service ?: return null
        if (!::engine.isInitialized) {
            engine = svc.engine
            engine.initialize()
        }
        return engine
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        calBtn = findViewById(R.id.calBtn)
        exportBtn = findViewById(R.id.exportBtn)
        connectBtn = findViewById(R.id.connectBtn)
        balanceBtn = findViewById(R.id.balanceBtn)
        stopBtn = findViewById(R.id.stopBtn)
        originalBtn = findViewById(R.id.originalBtn)
        roundText = findViewById(R.id.roundText)
        algoText = findViewById(R.id.algoText)
        progress = findViewById(R.id.progress)
        bgTip = findViewById(R.id.bgTip)
        logContainer = findViewById(R.id.logContainer)
        tabHost = findViewById(R.id.tabHost)

        val speedSlider = findViewById<SeekBar>(R.id.speedSlider)
        val speedLabel = findViewById<TextView>(R.id.speedLabel)
        speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                ensureEngine()?.let { it.treatSpeed = (v + 1).toFloat() }
                speedLabel.text = "×${v + 1}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun buildTabHost() {
        val ctx = this
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

    private fun onConnect() {
        val svc = service ?: return
        if (svc.isConnected) { svc.disconnect(); updateUI(); return }
        connectBtn.isEnabled = false; connectBtn.text = "…"
        svc.connect { ok, msg -> mainHandler.post { addLog(msg); updateUI() } }
    }

    private fun onCalibrate() {
        val eng = ensureEngine() ?: return
        if (!eng.isConnected) { addLog("⚠ 请先连接手环"); return }
        calBtn.isEnabled = false; calBtn.text = "校准中…"
        addLog("📐 校准中…传感器请悬空")
        eng.calibrate { ok, msg ->
            mainHandler.post { addLog(msg); calBtn.isEnabled = true; calBtn.text = "📐 校准"; updateUI() }
        }
    }

    private fun onBalance() {
        val svc = service ?: return
        val eng = ensureEngine() ?: return
        if (eng.isPlaying) { svc.stop(); updateUI(); return }
        // 通过 engine 启动平衡（service 管理 WakeLock）
        eng.startBalance()
        // 平衡启动后获取 WakeLock + 前台通知
        svc.acquireWakeLock()
        svc.startForegroundSafe("动态平衡")
        updateUI()
    }

    private fun updateUI() {
        val svc = service
        val eng = if (::engine.isInitialized) engine else null
        val conn = svc?.isConnected == true
        statusDot.setBackgroundResource(if (conn) android.R.color.holo_green_light else android.R.color.darker_gray)
        statusText.text = if (conn) "手环已连接" else "未连接"
        connectBtn.isEnabled = true
        connectBtn.text = if (conn) "断开" else "连接"
        balanceBtn.text = if (eng?.isPlaying == true) "⏸ 停止" else "▶ 启动平衡"
        bgTip.visibility = if (eng?.isPlaying == true) View.VISIBLE else View.GONE
        updateOriginalBtn()
    }

    private fun updateOriginalBtn() {
        val eng = if (::engine.isInitialized) engine else null
        val excl = eng?.excludeOriginal == true
        originalBtn.text = if (excl) "✕ 已排除原版" else "🔗 包括原版"
        originalBtn.setBackgroundColor(if (excl) 0xFFcc3333.toInt() else 0xFF226644.toInt())
        originalBtn.setTextColor(if (excl) 0xFFffcccc.toInt() else 0xFFccffdd.toInt())
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
            Thread.sleep(200)
            val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
            val filename = "NLS_Balance_${sdf.format(Date())}.txt"
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, filename)
            val lines = logHistory.toList()
            PrintWriter(file).use { pw ->
                pw.println("NLS 动态平衡仪 — 完整日志导出")
                pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                pw.println("记录数: ${lines.size} 条")
                pw.println("=" .repeat(40))
                for (line in lines) pw.println(line)
                pw.println("=" .repeat(40))
                pw.println("文件路径: $file")
            }
            addLog("💾 已导出: $filename (${lines.size}条记录)")
            Toast.makeText(this, "已保存 ${lines.size} 条: $filename", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            addLog("导出失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}
