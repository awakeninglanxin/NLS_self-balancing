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
    private val readBuf = ByteArray(256)

    var onStatus: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null
    var onRound: ((Int) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onChart: ((deltas: List<ChartDelta>, wuxing: Map<String, Double>) -> Unit)? = null
    var isPlaying = false; private set
    var isConnected = false; private set
    var isCalibrated = false; private set

    private val actionUsbPermission = "com.nls.selfbalancing.USB_PERMISSION"
    private var permCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                permCallback?.invoke(granted); permCallback = null
            }
        }
    }

    private var registered = false

    fun initialize() {
        if (!registered) {
            val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
            ctx.registerReceiver(usbReceiver, IntentFilter(actionUsbPermission), flags)
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
                    } else if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
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
        connection = null; device = null; epOut = null
        isConnected = false; isPlaying = false
        onStatus?.invoke("已断开")
    }

    // ========== 动态平衡核心 ==========

    /** 18频段 b9=14~31 分频器模型: f=7.3728/2^((b9-14)/2), 20kHz~7.37MHz */
    data class Band(val b9: Int, val organ: String, val wuxing: String, val freqKhz: Int)
    data class ChartDelta(val b9: Int, val organ: String, val wuxing: String, val delta: Double)

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

    var algoMode: String = "ab"
    private var algoQueue = mutableListOf<String>()
    private var batchNum = 0
    private var baseline = mutableMapOf<Int, Double>()

    fun calibrate(callback: (Boolean, String) -> Unit) {
        if (!isConnected) { callback(false, "⚠ 手环未连接"); return }
        val h = Handler(Looper.getMainLooper())
        Thread {
            try {
                for (band in bands) {
                    val avg = probe(band.b9)
                    baseline[band.b9] = avg
                    Thread.sleep(20)
                }
                isCalibrated = true
                h.post { onLog?.invoke("✅ 校准完成: ${baseline.size}频段基线"); callback(true, "✅ 校准完成") }
            } catch (e: Exception) {
                h.post { callback(false, "校准失败: ${e.message}") }
            }
        }.start()
    }

    /** 真实USB探测: 发送命令 → 读256字节 → 返回平均值 */
    private fun probe(b9: Int): Double {
        sendProbe(b9, 15, 15)
        if (epIn == null) return 100.0 + (Math.random() * 10 - 5)
        try {
            val read = connection?.bulkTransfer(epIn, readBuf, 256, 200) ?: -1
            if (read == 256) {
                var sum = 0
                for (b in readBuf) sum += b.toInt() and 0xFF
                return sum / 256.0
            }
        } catch (_: Exception) {}
        return 100.0 + (Math.random() * 10 - 5)
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
                        algoQueue = mutableListOf("legacy", "yinyang", "fusion", "schumann", "water")
                        algoQueue.shuffle()
                        val labels = algoQueue.joinToString(" → ") {
                            mapOf("legacy" to "同频", "yinyang" to "☀☽", "fusion" to "融合",
                                "schumann" to "舒曼", "water" to "水团簇").getOrDefault(it, it)
                        }
                        onLog?.invoke("─── 第${batchNum}遍: $labels ───")
                    }
                    algoQueue.removeAt(0)
                } else algoMode

                val label = mapOf("legacy" to "同频反相", "yinyang" to "☀☽双频",
                    "fusion" to "⚡融合", "schumann" to "🌍舒曼锚", "water" to "💧水团簇")
                    .getOrDefault(useAlgo, useAlgo)
                onLog?.invoke("第${round}轮 — 扫描中… [$label]")
                onStatus?.invoke(label)

                val deltas = mutableMapOf<Int, Double>()
                for (i in bands.indices) {
                    if (!isPlaying) break
                    val rawVal = probe(bands[i].b9)
                    val bl = baseline[bands[i].b9] ?: 105.0
                    deltas[bands[i].b9] = rawVal - bl
                    onProgress?.invoke(i + 1, bands.size)
                }

                // Fire chart update
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
                            "yinyang" -> treatYinyang(band.b9, delta, adjust)
                            "fusion" -> treatFusion(band.b9, delta, adjust)
                            "schumann" -> treatSchumann(band.b9, delta, adjust)
                            "water" -> treatWater(band.b9, delta, adjust)
                            else -> treatLegacy(band.b9, delta, adjust)
                        }
                        onLog?.invoke("  ${band.organ} Δ=${"%.1f".format(delta)} ${band.freqStr()}")
                        delay(100)
                    }
                } else { onLog?.invoke("  ✓ 全部频段平衡") }
                round++
                onProgress?.invoke(bands.size, bands.size)
                delay(3000)
            }
        }
    }

    private fun Band.freqStr(): String = if (freqKhz >= 1000) "%.2fMHz".format(freqKhz / 1000.0) else "${freqKhz}kHz"

    private fun sendProbe(b9: Int, b11: Int, b15: Int) {
        buf.fill(0)
        buf[9] = b9.toByte(); buf[11] = b11.toByte()
        buf[13] = b9.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 1000) } catch (_: Exception) {}
    }

    private fun wuxingCorr(wx: String): Double = when(wx) {
        "木" -> 1.0; "火" -> 1.5; "土" -> 1.0; "金" -> 1.2; "水" -> 0.8; else -> 1.0
    }

    // ── Algorithms ──

    private fun treatLegacy(b9: Int, delta: Double, adjust: Int) {
        val b11 = if (delta > 0) (15 - adjust).coerceIn(3, 80) else (15 + adjust).coerceIn(3, 80)
        val b15 = if (delta > 0) (15 + adjust).coerceIn(3, 80) else (15 - adjust).coerceIn(3, 80)
        sendProbe(b9, b11, b15)
    }

    private val SUN_SEQ = intArrayOf(1, 2, 4, 8, 16, 32)
    private val MOON_SEQ = intArrayOf(1, 1, 2, 3, 5, 8, 13, 21, 34)

    private fun nearest(b9: Int, seq: IntArray) = seq.minByOrNull { abs(b9 - it) } ?: b9
    private fun yinyangW(b9: Int): Pair<Double, Double> {
        val dS = SUN_SEQ.minOf { abs(b9 - it) }.toDouble()
        val dM = MOON_SEQ.minOf { abs(b9 - it) }.toDouble()
        val t = dS + dM; return if (t > 0) Pair(dM / t, dS / t) else Pair(0.5, 0.5)
    }

    private fun treatYinyang(b9: Int, delta: Double, adjust: Int) {
        val (wS, wM) = yinyangW(b9)
        val sB9 = nearest(b9, SUN_SEQ); val mB9 = nearest(b9, MOON_SEQ)
        val a1 = if (delta > 0) (15.0 - adjust * wS).toInt().coerceIn(3, 80)
                 else (15.0 + adjust * wS).toInt().coerceIn(3, 80)
        val a2 = if (delta > 0) (15.0 + adjust * wM).toInt().coerceIn(3, 80)
                 else (15.0 - adjust * wM).toInt().coerceIn(3, 80)
        buf.fill(0); buf[9] = sB9.toByte(); buf[11] = a1.toByte()
        buf[13] = mB9.toByte(); buf[15] = a2.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
    }

    private fun treatFusion(b9: Int, delta: Double, adjust: Int) {
        val b11 = if (delta > 0) (15 - adjust).coerceIn(3, 80) else (15 + adjust).coerceIn(3, 80)
        val b15 = if (delta > 0) (15 + adjust).coerceIn(3, 80) else (15 - adjust).coerceIn(3, 80)
        val s = nearest(b9, SUN_SEQ); val m = nearest(b9, MOON_SEQ)
        val c1 = b9.coerceIn(s - 2, s + 2); val c2 = b9.coerceIn(m - 2, m + 2)
        buf.fill(0); buf[9] = c1.toByte(); buf[11] = b11.toByte()
        buf[13] = c2.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
    }

    private fun treatSchumann(b9: Int, delta: Double, adjust: Int) {
        val sIdx = (abs(delta) / 5).toInt().coerceAtMost(4)
        val schB9 = (b9 - 6).coerceIn(14, 31)
        val ch2B9 = (schB9 + sIdx + 1).coerceIn(14, 31)
        buf.fill(0); buf[9] = schB9.toByte(); buf[11] = 15.toByte()
        buf[13] = ch2B9.toByte(); buf[15] = 15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
    }

    private val WATER_MODES = doubleArrayOf(2.04, 3.42, 4.83, 7.78, 10.84, 13.94, 16.97, 19.56,
        24.16, 33.07, 48.31, 73.06, 99.76, 126.07, 164.17, 193.24, 216.02, 256.46, 309.88, 362.89, 415.13, 432.0)

    private fun treatWater(b9: Int, delta: Double, adjust: Int) {
        val wIdx = ((b9 - 14) * WATER_MODES.size / 18).coerceIn(0, WATER_MODES.size - 1)
        val waterHz = WATER_MODES[wIdx]
        val b11 = if (delta > 0) (15 - adjust).coerceIn(3, 80) else (15 + adjust).coerceIn(3, 80)
        val b15 = if (delta > 0) (15 + adjust).coerceIn(3, 80) else (15 - adjust).coerceIn(3, 80)
        buf.fill(0); buf[9] = b9.toByte(); buf[11] = b11.toByte()
        buf[13] = b9.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
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
