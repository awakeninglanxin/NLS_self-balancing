package com.nls.selfbalancing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * NLS 动态平衡引擎 v7 — 18频段 (b9=14..31), 分频器模型, 5算法AB轮转
 * 镜像 PC Web 版 balancer_web.py
 */
class BalancerEngine(private val ctx: Context) {
    private var connection: UsbDeviceConnection? = null
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null
    private var device: UsbDevice? = null
    private var job: Job? = null
    private val buf = ByteArray(128)

    var onStatus: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null
    var onRound: ((Int) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onChart: ((deltas: List<ChartDelta>, wuxing: Map<String, Double>) -> Unit)? = null
    var onBatchReport: ((batchNum: Int, stats: Map<String, AlgoStat>) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null   // USB断连回调
    var isPlaying = false; private set
    var isConnected = false; private set
    var isCalibrated = false; private set

    private val actionUsbPermission = "com.nls.selfbalancing.USB_PERMISSION"
    private var permCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    permCallback?.invoke(granted); permCallback = null
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (isPlaying) {
                        isPlaying = false
                        job?.cancel()
                        onLog?.invoke("⚠ 手环已断开! 请重新连接并校准后再次启动")
                        onStatus?.invoke("手环已断开")
                    }
                    isConnected = false
                    try { connection?.close() } catch (_: Exception) {}
                    connection = null; epOut = null; epIn = null
                    onDisconnect?.invoke()
                }
            }
        }
    }

    private var registered = false

    fun initialize() {
        if (!registered) {
            val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
            val filter = IntentFilter().apply {
                addAction(actionUsbPermission)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ctx.registerReceiver(usbReceiver, filter, flags)
            registered = true
        }
    }

    fun connect(callback: (Boolean, String) -> Unit) {
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values
        device = devices.find { it.vendorId == 0x0403 && it.productId == 0x6001 }
            ?: devices.firstOrNull { it.vendorId == 0x0403 }
        if (device == null) { callback(false, "未检测到FTDI设备"); return }
        if (!usbManager.hasPermission(device)) {
            permCallback = { granted ->
                if (granted) openDevice(callback) else callback(false, "USB权限被拒绝")
            }
            usbManager.requestPermission(device!!,
                PendingIntent.getBroadcast(ctx, 0, Intent(actionUsbPermission), PendingIntent.FLAG_IMMUTABLE))
            return
        }
        openDevice(callback)
    }

    private fun openDevice(callback: (Boolean, String) -> Unit) {
        try {
            val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            connection = usbManager.openDevice(device!!)
                ?: run { callback(false, "无法打开USB"); return }
            for (i in 0 until device!!.interfaceCount) {
                val iface = device!!.getInterface(i)
                if (!connection!!.claimInterface(iface, true)) continue
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        epOut = ep
                    }
                    if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        epIn = ep
                    }
                }
                if (epOut != null) break
            }
            if (epOut == null) { connection!!.close(); connection = null; callback(false, "未找到端点"); return }
            configureFtdi()
            isConnected = true
            callback(true, "手环已连接")
        } catch (e: Exception) {
            isConnected = false
            try { connection?.close() } catch (_: Exception) {}
            connection = null
            callback(false, "连接失败: ${e.message}")
        }
    }

    private fun configureFtdi() {
        connection?.controlTransfer(0x40, 0, 0, 1, null, 0, 1000)
        connection?.controlTransfer(0x40, 0, 0, 0, null, 0, 1000)
        val divisor = 3000000 / 115200
        val cfg = byteArrayOf((divisor and 0xFF).toByte(), ((divisor shr 8) and 0xFF).toByte(), 0x00, 0x00, 0x08)
        connection?.controlTransfer(0x40, 0x03, 0x4138, 0, cfg, cfg.size, 1000)
    }

    fun disconnect() {
        stop()
        try { connection?.close() } catch (_: Exception) {}
        connection = null; device = null; epOut = null; epIn = null
        isConnected = false; isPlaying = false
        onStatus?.invoke("已断开")
    }

    // ========== 动态平衡核心 ==========

    /** 18频段 b9=14~31 分频器模型: f=7.3728/2^((b9-14)/2), 20kHz~7.37MHz */
    data class Band(val b9: Int, val organ: String, val wuxing: String, val freqKhz: Int)
    data class ChartDelta(val b9: Int, val organ: String, val wuxing: String, val delta: Double)
    data class AlgoStat(var imp: Int = 0, var wors: Int = 0, var rounds: Int = 0)
    data class TxInfo(var ch1B9: Int = 0, var ch1Amp: Int = 0, var ch2B9: Int = 0, var ch2Amp: Int = 0, var count: Int = 1) {
        val ch1FreqKhz: Double get() = 7372.8 / Math.pow(2.0, (ch1B9 - 14) / 2.0)
        val ch2FreqKhz: Double get() = 7372.8 / Math.pow(2.0, (ch2B9 - 14) / 2.0)
        val freqDiffKhz: Double get() = Math.abs(ch1FreqKhz - ch2FreqKhz)
        fun fmt(): String {
            val ch1f = if (ch1FreqKhz >= 1000) "%.2fM".format(ch1FreqKhz / 1000) else "%.0fk".format(ch1FreqKhz)
            val ch2f = if (ch2FreqKhz >= 1000) "%.2fM".format(ch2FreqKhz / 1000) else "%.0fk".format(ch2FreqKhz)
            val diff = if (freqDiffKhz >= 1000) "%.2fM".format(freqDiffKhz / 1000) else "%.0fk".format(freqDiffKhz)
            val ratio = if (ch2Amp > 0) "%.2f".format(ch1Amp.toDouble() / ch2Amp) else "∞"
            return "CH1[b9=$ch1B9 a=$ch1Amp $ch1f] CH2[b9=$ch2B9 a=$ch2Amp $ch2f] Δf=$diff a:R=$ratio"
        }
    }
    var lastTx = TxInfo()

    /** Per-b9 CH1/CH2 振幅映射 — 来自双用户PCAP检测协议中位值
     *  高频(MHz)高功率穿透, 中频(kHz生物共振)极低功率, 低频(ELF)低功率 */
    private fun ch1Amp(b9: Int): Int = when (b9) {
        14 -> 90; 15 -> 90; 16 -> 56; 17 -> 63; 18 -> 67; 19 -> 74
        20 -> 80; 21 -> 80  // 低频大功率穿透
        22 -> 3;  23 -> 3;  24 -> 3   // 中频极低功率(组织敏感)
        25 -> 4;  26 -> 5;  27 -> 10
        28 -> 15  // 参考频段
        30 -> 9;  31 -> 9   // ELF低功率尾
        else -> 15
    }
    /** CH2振幅=CH1振幅±偏移, |b11-b15|中位≈12 (匹配PCAP检测非对称特征) */
    private fun ch2Amp(b9: Int): Int = when (b9) {
        14 -> 78; 15 -> 78; 16 -> 44; 17 -> 51; 18 -> 55; 19 -> 62
        20 -> 68; 21 -> 68
        22 -> 3;  23 -> 3;  24 -> 3   // 极低功率区保持一致
        25 -> 4;  26 -> 5;  27 -> 10
        28 -> 15
        30 -> 9;  31 -> 9
        else -> 15
    }

    /** 疗愈模式振幅基底 — 来自丽梦儿PCAP疗愈中位值
     *  比检测更对称(b11≈b15), b9=28使用最大功率172作载波 */
    private fun cureBaseAmp(b9: Int): Int = when (b9) {
        14 -> 90; 15 -> 88; 16 -> 55; 17 -> 62; 18 -> 66; 19 -> 74
        20 -> 77; 21 -> 127
        22 -> 3;  23 -> 3;  24 -> 26; 25 -> 20; 26 -> 21; 27 -> 22
        28 -> 172  // 最大功率载波(PCAP疗愈48%命令用b9=28,b11=172)
        30 -> 33; 31 -> 8
        else -> 30
    }

    private val bands = listOf(
        Band(14, "松果体/脑中枢", "火", 7373), Band(15, "下丘脑/内分泌", "火", 5213),
        Band(16, "大脑皮层", "火", 3686), Band(17, "小脑/脑干", "火", 2607),
        Band(18, "心脏/心血管", "火", 1843), Band(19, "心脏瓣膜", "火", 1303),
        Band(20, "心脏后壁血管", "火", 922), Band(21, "冠状动脉", "火", 652),
        Band(22, "肺/支气管", "金", 461), Band(23, "肺实质/肺泡", "金", 326),
        Band(24, "甲状腺/甲状旁腺", "火", 230), Band(25, "肝脏/肝血管", "木", 163),
        Band(26, "胃/食道", "土", 115), Band(27, "十二指肠/小肠", "火", 81),
        Band(28, "胰腺/脾脏", "土", 58), Band(29, "肾脏/肾上腺", "水", 41),
        Band(30, "大肠/直肠", "金", 29), Band(31, "骨骼/关节/牙齿", "水", 20),
    )

    var algoMode: String = "ab"  // legacy / yinyang / fusion / schumann / water / ab
    var excludeOriginal: Boolean = false  // 一键排除/包括 🔗原版 算法
    var treatSpeed: Float = 1f  // 治疗时间周期倍数 1~12
    var minInterval: Float = 0.49f  // 最小命令间隔 (0.1~1s, 默认0.49s)
    var pauseThreshold: Float = 1.0f  // 长暂停阈值 (0.1~6s, 默认1s)
    var maxBurst: Int = 8  // 最长连续同频burst (1~12, 默认8)
    private var algoQueue = mutableListOf<String>()
    private var batchNum = 0
    private var baseline = mutableMapOf<Int, Double>()
    private val algoStats = mutableMapOf<String, AlgoStat>()

    fun calibrate(callback: (Boolean, String) -> Unit) {
        if (!isConnected) { callback(false, "⚠ 请先连接手环"); return }
        val h = Handler(Looper.getMainLooper())
        Thread {
            try {
                for (band in bands) {
                    val raw: Double = probe(band.b9)
                    baseline[band.b9] = raw
                    Thread.sleep(20)
                }
                isCalibrated = true
                h.post { onLog?.invoke("✅ 校准完成: ${baseline.size}频段 (PCAP振幅)"); callback(true, "✅ 校准完成") }
            } catch (e: Exception) {
                h.post { callback(false, "校准失败: ${e.message}") }
            }
        }.start()
    }

    fun startBalance() {
        stop()
        isPlaying = true
        onStatus?.invoke("🧬 动态平衡启动")
        onLog?.invoke("── 扫描18频段 ──")

        job = CoroutineScope(Dispatchers.IO).launch {
            var round = 1
            while (isPlaying) {
                onRound?.invoke(round)

                val useAlgo = if (algoMode == "ab") {
                    if (algoQueue.isEmpty()) {
                        batchNum++
                        algoQueue = mutableListOf("original", "legacy", "yinyang", "fusion", "schumann", "water", "jellium", "multiharm", "wuyin", "septet")
                        if (excludeOriginal) algoQueue.removeAll { it == "original" }
                        algoQueue.shuffle()
                        val labels = algoQueue.joinToString(" → ") {
                            mapOf("original" to "🔗原版", "legacy" to "同频", "yinyang" to "☀☽",
                                "fusion" to "融合", "schumann" to "舒曼", "water" to "水共振", "jellium" to "⚛幻数",
                                "multiharm" to "🎵多谐波", "wuyin" to "🎵五音").getOrDefault(it, it)
                        }
                        onLog?.invoke("─── 第${batchNum}遍: $labels ───")
                    }
                    algoQueue.removeAt(0)
                } else algoMode

                val label = mapOf("original" to "🔗原版", "legacy" to "同频反相", "yinyang" to "☀☽双频",
                    "fusion" to "⚡融合", "schumann" to "🌍舒曼锚", "water" to "💧水共振", "jellium" to "⚛幻数",
                    "multiharm" to "🎵多谐波", "wuyin" to "🎵五音")
                    .getOrDefault(useAlgo, useAlgo)
                onLog?.invoke("第${round}轮 — 扫描中… [$label]")
                onStatus?.invoke(label)

                val deltas = mutableMapOf<Int, Double>()
                for (i in bands.indices) {
                    if (!isPlaying) break
                    val raw = probe(bands[i].b9)
                    val bl = baseline[bands[i].b9] ?: 105.0
                    deltas[bands[i].b9] = raw - bl
                    onProgress?.invoke(i + 1, bands.size)
                }

                // Fire chart data
                val cd = bands.map { ChartDelta(it.b9, it.organ, it.wuxing, deltas[it.b9] ?: 0.0) }
                val wx = mutableMapOf("火" to 0.0, "土" to 0.0, "金" to 0.0, "水" to 0.0, "木" to 0.0)
                for (b in bands) wx[b.wuxing] = (wx[b.wuxing] ?: 0.0) + (deltas[b.b9] ?: 0.0)
                onChart?.invoke(cd, wx)

                val abnormal = bands.filter { abs(deltas[it.b9] ?: 0.0) > 4 }
                if (abnormal.isNotEmpty()) {
                    onLog?.invoke("  ⚡ ${abnormal.size}项异常 [$label]")
                    for (band in abnormal) {
                        if (!isPlaying) break
                        val delta = deltas[band.b9]!!
                        val corr = wuxingCorr(band.wuxing)
                        val adjust = (abs(delta) * 0.5 * corr).toInt().coerceAtMost(60)
                        when (useAlgo) {
                            "original" -> treatOriginal(band.b9, delta, adjust)
                            "yinyang" -> treatYinyang(band.b9, delta, adjust)
                            "fusion" -> treatFusion(band.b9, delta, adjust)
                            "schumann" -> treatSchumann(band.b9, delta, adjust)
                            "water" -> treatWater(band.b9, delta, adjust)
                            "jellium" -> treatJellium(band.b9, delta, adjust)
                            "multiharm" -> treatMultiHarmonic(band.b9, delta, adjust)
                            "wuyin" -> treatWuyin(band.b9, delta, adjust)
                            "septet" -> treatSeptet(band.b9, delta, adjust)
                            else -> treatLegacy(band.b9, delta, adjust)
                        }
                        AudioTone.play(lastTx.ch1B9, lastTx.ch1Amp, lastTx.ch2B9, lastTx.ch2Amp, treatMs(80))  // 双声道+振幅比功率治疗音频
                        val extra = if (lastTx.count > 1) " ×${lastTx.count}对" else ""
                        onLog?.invoke("  ${band.organ} Δ=${"%.1f".format(delta)} ${band.freqStr()} | ${lastTx.fmt()}$extra")
                        delay(treatMs(50))
                        // Verify after treatment
                        Thread.sleep(treatMs(50))
                        val afterRaw = probe(band.b9)
                        val bl = baseline[band.b9] ?: 105.0
                        val afterDelta = afterRaw - bl
                        val diff = abs(delta) - abs(afterDelta)
                        val stat = algoStats.getOrPut(useAlgo) { AlgoStat() }
                        stat.rounds++
                        if (diff > 0.5) stat.imp++ else if (diff < -0.5) stat.wors++
                        delay(treatMs(50))
                    }
                } else { onLog?.invoke("  ✓ 全部频段平衡") }
                round++

                // Batch report: after every 7 rounds
                if (round % 7 == 1 && round > 1) {
                    val prevBatch = batchNum
                    // Deep copy stats for thread safety
                    val report = algoStats.mapValues { e -> AlgoStat(e.value.imp, e.value.wors, e.value.rounds) }
                    onBatchReport?.invoke(prevBatch, report)
                    // Log summary
                    val parts = mutableListOf<String>()
                    for ((algo, st) in report) {
                        val total = st.imp + st.wors
                        val rate = if (total > 0) "${(st.imp * 100 / total).toInt()}%" else "--"
                        val name = mapOf("original" to "🔗原版", "legacy" to "同频", "yinyang" to "☀☽",
                            "fusion" to "融合", "schumann" to "舒曼", "water" to "水共振", "jellium" to "⚛幻数",
                            "multiharm" to "🎵多谐波", "wuyin" to "🎵五音")[algo] ?: algo
                        parts.add("$name $rate")
                    }
                    onLog?.invoke("════ 第${prevBatch}遍九维对比: ${parts.joinToString(" │ ")} ════")
                }

                onProgress?.invoke(bands.size, bands.size)
                delay(3000)
            }
        }
    }

    private fun Band.freqStr(): String = if (freqKhz >= 1000) "%.2fMHz".format(freqKhz / 1000.0) else "${freqKhz}kHz"

    /** 治疗延时 = max(minInterval*1000, 基础毫秒 × treatSpeed) */
    private fun treatMs(ms: Long): Long = maxOf((minInterval * 1000).toLong(), (ms * treatSpeed).toLong())

    private fun sendProbe(b9: Int, b11: Int, b15: Int, b13: Int = b9) {
        buf.fill(0)
        buf[9] = b9.toByte(); buf[11] = b11.toByte()
        buf[13] = b13.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 1000) } catch (_: Exception) {}
    }

    /** 使用PCAP振幅映射发送探针(检测模式: CH1≠CH2振幅) */
    private fun sendPcapProbe(b9: Int) = sendProbe(b9, ch1Amp(b9), ch2Amp(b9), b9)

    /** USB探测: 发送命令并读取IN响应(134字节, 132个有符号byte检测值) */
    private fun probe(b9: Int): Double {
        if (!isConnected || epIn == null) return 105.0  // 断连返回中值
        try {
            sendPcapProbe(b9)
            Thread.sleep(80)
            val inBuf = ByteArray(256)
            val r = connection?.bulkTransfer(epIn, inBuf, inBuf.size, 100) ?: 0
            if (r < 2) return 105.0
            // 取前132字节(跳过包头)的均值→值域偏移
            var sum = 0.0; var cnt = 0
            for (i in 2 until minOf(r, 134)) {
                sum += inBuf[i].toDouble(); cnt++
            }
            return if (cnt > 0) 100.0 + (sum / cnt) * 0.5 else 105.0
        } catch (_: Exception) { return 105.0 }
    }

    private fun wuxingCorr(wx: String): Double = when(wx) {
        "木" -> 1.0; "火" -> 1.5; "土" -> 1.0; "金" -> 1.2; "水" -> 0.8; else -> 1.0
    }

    // ── Algorithms ──

    // ── ② Legacy: 异频反相 — CH1≠CH2, 反相振幅(基于PCAP疗愈基底) ──
    private fun treatLegacy(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        val b11 = if (delta > 0) (base - adjust).coerceIn(3, 172) else (base + adjust).coerceIn(3, 172)
        val b15 = if (delta > 0) (base + adjust).coerceIn(3, 172) else (base - adjust).coerceIn(3, 172)
        val offset = (abs(delta) / 4).toInt().coerceIn(1, 3)
        val ch2b9 = if (delta > 0) (b9 + offset).coerceIn(14, 31) else (b9 - offset).coerceIn(14, 31)
        buf.fill(0); buf[9] = b9.toByte(); buf[11] = b11.toByte()
        buf[13] = ch2b9.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(b9, b11, ch2b9, b15)
    }

    // ═══════ 7族M集内生序列 (全映射到b9空间 1~34) ═══════
    private data class Mix4(val ch1B9: Int, val ch1W: Double, val ch2B9: Int, val ch2W: Double)
    private val F7_0 = intArrayOf(1, 2, 4, 8, 16, 32)          // ①Feigenbaum 2^n
    private val F7_1 = intArrayOf(1, 1, 2, 3, 5, 8, 13, 21, 34) // ②Fibonacci
    private val F7_2 = intArrayOf(3, 5, 7, 9, 11, 13, 15, 6, 10, 14, 18, 22, 26, 30, 12, 20, 28, 16, 32) // ③Sharkovsky
    private val F7_3 = intArrayOf(2, 3, 3, 4, 5, 4, 5, 6, 5, 6, 7, 7, 8, 9, 10, 11) // ④Farey
    private val F7_4 = intArrayOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31) // ⑤Kneading素数
    private val F7_5 = intArrayOf(4, 6, 9, 14, 21, 25, 30)      // ⑥Misiurewicz
    private val F7_6 = intArrayOf(1, 3, 5, 7, 9, 13, 17, 21, 25, 29) // ⑦InternalAddr
    private val F7_ALL = arrayOf(F7_0, F7_1, F7_2, F7_3, F7_4, F7_5, F7_6)

    private val SUN_SEQ = F7_0; private val MOON_SEQ = F7_1  // 旧名兼容

    private fun nearest(b9: Int, seq: IntArray) = seq.minByOrNull { abs(b9 - it) } ?: b9

    /** 7族混音: CH1=结构族(0-2) CH2=动力族(3-6) */
    private fun septetMix(b9: Int): Mix4 {
        val dists = DoubleArray(7) { F7_ALL[it].minOf { v -> abs(b9 - v) }.toDouble() }
        val w = if (dists.sum() > 0) dists.map { 1.0 - it / (it + 4.0) }.toDoubleArray() else DoubleArray(7) { 1.0 }
        val w1 = w[0] + w[1] + w[2]; val w2 = w[3] + w[4] + w[5] + w[6]
        val c1 = if (w1 > 1e-6) ((nearest(b9, F7_0) * w[0] + nearest(b9, F7_1) * w[1] + nearest(b9, F7_2) * w[2]) / w1).toInt().coerceIn(14, 31) else b9
        val c2 = if (w2 > 1e-6) ((nearest(b9, F7_3) * w[3] + nearest(b9, F7_4) * w[4] + nearest(b9, F7_5) * w[5] + nearest(b9, F7_6) * w[6]) / w2).toInt().coerceIn(14, 31) else b9
        val tw = w.sum()
        return Mix4(c1, (w1 / tw * 1.5).coerceIn(0.3, 1.5), c2, (w2 / tw * 1.5).coerceIn(0.3, 1.5))
    }

    private fun yinyangW(b9: Int): Pair<Double, Double> {
        val dS = SUN_SEQ.minOf { abs(b9 - it) }.toDouble()
        val dM = MOON_SEQ.minOf { abs(b9 - it) }.toDouble()
        val t = dS + dM; return if (t > 0) Pair(dM / t, dS / t) else Pair(0.5, 0.5)
    }

    /** ☀☽双频 (原版2族) */
    private fun treatYinyang(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9); val (wS, wM) = yinyangW(b9)
        val sB9 = nearest(b9, SUN_SEQ); val mB9 = nearest(b9, MOON_SEQ)
        val a1 = if (delta > 0) (base - adjust * wS).toInt().coerceIn(3, 172)
                 else (base + adjust * wS).toInt().coerceIn(3, 172)
        val a2 = if (delta > 0) (base + adjust * wM).toInt().coerceIn(3, 172)
                 else (base - adjust * wM).toInt().coerceIn(3, 172)
        buf.fill(0); buf[9] = sB9.toByte(); buf[11] = a1.toByte()
        buf[13] = mB9.toByte(); buf[15] = a2.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(sB9, a1, mB9, a2)
    }

    /** ☀☽7族混音双频 (新增算法: 7族M集内生序列加权) */
    private fun treatSeptet(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9); val m = septetMix(b9); val ad = abs(adjust).toDouble()
        val a1 = if (delta > 0) (base - ad * m.ch1W).toInt().coerceIn(3, 172) else (base + ad * m.ch1W).toInt().coerceIn(3, 172)
        val a2 = if (delta > 0) (base + ad * m.ch2W).toInt().coerceIn(3, 172) else (base - ad * m.ch2W).toInt().coerceIn(3, 172)
        buf.fill(0); buf[9] = m.ch1B9.toByte(); buf[11] = a1.toByte()
        buf[13] = m.ch2B9.toByte(); buf[15] = a2.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(m.ch1B9, a1, m.ch2B9, a2)
    }

    private fun treatFusion(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        val b11 = if (delta > 0) (base - adjust).coerceIn(3, 172) else (base + adjust).coerceIn(3, 172)
        val b15 = if (delta > 0) (base + adjust).coerceIn(3, 172) else (base - adjust).coerceIn(3, 172)
        val s = nearest(b9, SUN_SEQ); val m = nearest(b9, MOON_SEQ)
        val c1 = b9.coerceIn(s - 2, s + 2); val c2 = b9.coerceIn(m - 2, m + 2)
        buf.fill(0); buf[9] = c1.toByte(); buf[11] = b11.toByte()
        buf[13] = c2.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(c1, b11, c2, b15)
    }

    private fun treatSchumann(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        val sIdx = (abs(delta) / 5).toInt().coerceAtMost(4)
        val schB9 = (b9 - 6).coerceIn(14, 31)
        val ch2B9 = (schB9 + sIdx + 1).coerceIn(14, 31)
        buf.fill(0); buf[9] = schB9.toByte(); buf[11] = base.toByte()
        buf[13] = ch2B9.toByte(); buf[15] = base.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(schB9, base, ch2B9, base)
    }

    private val WATER_MODES = doubleArrayOf(2.04, 3.42, 4.83, 7.78, 10.84, 13.94, 16.97, 19.56,
        24.16, 33.07, 48.31, 73.06, 99.76, 126.07, 164.17, 193.24, 216.02, 256.46, 309.88, 362.89, 415.13, 432.0)

    // ── ⑥ Water: 异频水共振 — CH2=水团簇频率索引偏移, 振幅反相 ──
    private fun treatWater(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        val wIdx = ((b9 - 14) * WATER_MODES.size / 18).coerceIn(0, WATER_MODES.size - 1)
        val waterHz = WATER_MODES[wIdx]
        val b11 = if (delta > 0) (base - adjust).coerceIn(3, 172) else (base + adjust).coerceIn(3, 172)
        val b15 = if (delta > 0) (base + adjust).coerceIn(3, 172) else (base - adjust).coerceIn(3, 172)
        val ch2offset = if (waterHz < 50) 2 else if (waterHz < 200) 1 else -1
        val ch2b9 = (b9 + ch2offset).coerceIn(14, 31)
        buf.fill(0); buf[9] = b9.toByte(); buf[11] = b11.toByte()
        buf[13] = ch2b9.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(b9, b11, ch2b9, b15)
    }

    // ── ⑥ Original: PCAP逆推原版逼近 (54%同频+46%异频, 疗愈基底振幅) ──
    private fun treatOriginal(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        val samePct = 54
        val isSame = (abs(b9 * 7 + delta.toInt()) % 100) < samePct
        val b11 = if (delta > 0) maxOf(3, base - adjust) else minOf(172, base + adjust)
        val b15 = if (delta > 0) minOf(172, base + adjust) else maxOf(3, base - adjust)
        if (isSame) {
            sendProbe(b9, b11, b15)
            lastTx = TxInfo(b9, b11, b9, b15)
        } else {
            val offset = (abs(delta) / 3).toInt().coerceIn(1, 15)  // 异频偏移正比|delta|
            val ch2b9 = minOf(31, b9 + offset)
            buf.fill(0); buf[9] = b9.toByte(); buf[11] = b11.toByte()
            buf[13] = ch2b9.toByte(); buf[15] = minOf(172, b15).toByte()
            try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
            lastTx = TxInfo(b9, b11, ch2b9, minOf(172, b15))
        }
    }

    // ── ⑦ Jellium: 幻数共振 (JELLIUM_SEQUENCE阶梯振幅) ──
    private val JELLIUM_SEQ = intArrayOf(2, 8, 20, 28, 50, 82, 92, 126, 138, 184, 258, 322)
    private val JELLIUM_AMP = doubleArrayOf(0.25, 0.40, 0.60, 0.85, 1.15, 1.50, 0.80, 1.85, 2.20, 2.60, 3.00, 0.60)

    private fun treatJellium(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        val tier = (abs(delta) / 10).toInt().coerceIn(0, JELLIUM_SEQ.size - 1)
        val shellNum = JELLIUM_SEQ[tier]
        val ampBoost = JELLIUM_AMP[tier]
        val adj = minOf(60, (abs(delta) * ampBoost).toInt())
        val b11 = maxOf(3, base - adj)
        val b15 = minOf(172, base + adj)
        val ch2b9 = if (shellNum % 2 == 0) b9 else minOf(31, b9 + (shellNum % 5) + 1)
        buf.fill(0); buf[9] = b9.toByte(); buf[11] = b11.toByte()
        buf[13] = ch2b9.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(b9, b11, ch2b9, b15)
    }

    // ── ⑧ MultiHarmonic: 多谐波无理数比扫掠 (CH1→φ偏移, CH2→√2偏移, 5对/器官) ──
    // 原理: 两路独立可调频率, 每路5层谐波, 10对组合产生密集频谱
    // 振幅: 中心100% → 外围逐层衰减 (100%, 80%, 65%, 50%, 35%)
    // CH1频率偏移: [0, +1, -2, +3, -1]  — 无理化间隔
    // CH2频率偏移: [0, -1, +2, -3, +1]  — 反向偏移, 差频两两无理
    private fun treatMultiHarmonic(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        val ch1Offsets = intArrayOf(0, 1, -2, 3, -1)
        val ch2Offsets = intArrayOf(0, -1, 2, -3, 1)
        val ampFactors = doubleArrayOf(1.0, 0.80, 0.65, 0.50, 0.35)
        val b11Base = if (delta > 0) maxOf(3, base - adjust) else minOf(172, base + adjust)
        val b15Base = if (delta > 0) minOf(172, base + adjust) else maxOf(3, base - adjust)

        for (i in 0 until 5) {
            val ch1b9 = (b9 + ch1Offsets[i]).coerceIn(14, 31)
            val ch2b9 = (b9 + ch2Offsets[i]).coerceIn(14, 31)
            val ch1Amp = (b11Base * ampFactors[i]).toInt().coerceIn(3, 172)
            val ch2Amp = (b15Base * ampFactors[i]).toInt().coerceIn(3, 172)
            buf.fill(0)
            buf[9] = ch1b9.toByte(); buf[11] = ch1Amp.toByte()
            buf[13] = ch2b9.toByte(); buf[15] = ch2Amp.toByte()
            try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
            lastTx = TxInfo(ch1b9, ch1Amp, ch2b9, ch2Amp, 5)
            if (i < 4) try { Thread.sleep(treatMs(40)) } catch (_: Exception) {}
        }
    }

    // ── ⑨ 五音: 宫商角徵羽→五行→b9异频双通道 (√2频率比, PCAP振幅) ──
    // 原理: b9步进1对应频率比√2≈1.414(近纯五度), 双通道差拍产生五行调谐干涉
    data class WuyinNote(val label: String, val ch1b9: Int, val ch2b9: Int, val wx: String)
    private val WUYIN_SEQ = listOf(
        WuyinNote("宫(土)", 22, 20, "土"),  // 脾 461k/922k 纯八度
        WuyinNote("商(金)", 23, 22, "金"),  // 肺 326k/461k 近纯四度
        WuyinNote("角(木)", 16, 15, "木"),  // 肝 3.69M/5.21M 近纯四度
        WuyinNote("羽(水)", 30, 28, "水"),  // 肾 29k/58k 纯八度
        WuyinNote("徵(火)", 27, 25, "火"),  // 心 81k/163k 纯八度
    )
    private var wuyinIdx = 0  // 轮转索引

    private fun treatWuyin(b9: Int, delta: Double, adjust: Int) {
        val base = cureBaseAmp(b9)
        // 找到最近的五音映射
        val note = WUYIN_SEQ[wuyinIdx % WUYIN_SEQ.size]
        wuyinIdx++
        val b11 = if (delta > 0) (base - adjust).coerceIn(3, 172)
                 else (base + adjust).coerceIn(3, 172)
        val b15 = if (delta > 0) (base + adjust).coerceIn(3, 172)
                 else (base - adjust).coerceIn(3, 172)
        buf.fill(0); buf[9] = note.ch1b9.toByte(); buf[11] = b11.toByte()
        buf[13] = note.ch2b9.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
        lastTx = TxInfo(note.ch1b9, b11, note.ch2b9, b15)
        onLog?.invoke("    🎵${note.label} CH1=${note.ch1b9} CH2=${note.ch2b9} | ${lastTx.fmt()}")
    }

    fun stop() {
        isPlaying = false
        job?.cancel(); job = null
        try {
            val zero = ByteArray(128)
            connection?.bulkTransfer(epOut, zero, zero.size, 500)
            Thread.sleep(50)
            connection?.bulkTransfer(epOut, zero, zero.size, 500)
        } catch (_: Exception) {}
        onProgress?.invoke(0, 0)
        onStatus?.invoke("⏹ 已停止")
    }

    fun destroy() { disconnect(); try { ctx.unregisterReceiver(usbReceiver) } catch (_: Exception) {} }
}
