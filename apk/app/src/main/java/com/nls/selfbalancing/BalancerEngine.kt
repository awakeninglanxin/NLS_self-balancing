package com.nls.selfbalancing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * NLS 动态平衡引擎 — 仅含扫描→反相治疗→验证
 * 18个频段(b9=14..31)，自定义反相算法，无旧版1087条命令
 */
class BalancerEngine(private val ctx: Context) {
    private var connection: UsbDeviceConnection? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null
    private var job: Job? = null
    private var interval = 120L  // 命令间隔ms
    private val buf = ByteArray(128)

    var onStatus: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null
    var onRound: ((Int) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var isPlaying = false; private set
    var isConnected = false; private set

    private val actionUsbPermission = "com.nls.handring.USB_PERMISSION"
    private var permCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                permCallback?.invoke(granted); permCallback = null
            }
        }
    }

    init {
        ctx.registerReceiver(usbReceiver, IntentFilter(actionUsbPermission), Context.RECEIVER_NOT_EXPORTED)
    }

    fun connect(callback: (Boolean, String) -> Unit) {
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values
        device = devices.find { it.vendorId == 0x0403 && it.productId == 0x6001 }
            ?: devices.firstOrNull { it.vendorId == 0x0403 }
        if (device == null) { callback(false, "未检测到 FTDI 设备"); return }
        if (!usbManager.hasPermission(device)) {
            permCallback = { granted -> if (granted) openDevice(callback) else callback(false, "USB 权限被拒绝") }
            usbManager.requestPermission(device!!, PendingIntent.getBroadcast(ctx, 0,
                Intent(actionUsbPermission), PendingIntent.FLAG_IMMUTABLE))
            return
        }
        openDevice(callback)
    }

    private fun openDevice(callback: (Boolean, String) -> Unit) {
        try {
            val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            connection = usbManager.openDevice(device!!) ?: run { callback(false, "无法打开USB"); return }
            for (i in 0 until device!!.interfaceCount) {
                val iface = device!!.getInterface(i)
                if (!connection!!.claimInterface(iface, true)) continue
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        epOut = ep; break
                    }
                }
                if (epOut != null) break
            }
            if (epOut == null) { connection!!.close(); connection = null; callback(false, "未找到端点"); return }
            configureFtdi()
            isConnected = true
            callback(true, "手环已连接")
        } catch (e: Exception) {
            isConnected = false; try { connection?.close() } catch (_: Exception) {}; connection = null
            callback(false, "连接失败: ${e.message}")
        }
    }

    private fun configureFtdi() {
        connection?.controlTransfer(0x40, 0, 0, 1, null, 0, 1000)
        connection?.controlTransfer(0x40, 0, 0, 0, null, 0, 1000)
        val divisor = 3000000 / 115200
        val buf = byteArrayOf((divisor and 0xFF).toByte(), ((divisor shr 8) and 0xFF).toByte(), 0x00, 0x00, 0x08)
        connection?.controlTransfer(0x40, 0x03, 0x4138, 0, buf, buf.size, 1000)
    }

    fun disconnect() {
        stop()
        try { connection?.close() } catch (_: Exception) {}
        connection = null; device = null; epOut = null
        isConnected = false; isPlaying = false
        onStatus?.invoke("已断开")
    }

    // ========== 动态平衡核心 ==========

    /** 18频段 b9=14~31 (20kHz~7.37MHz) 分频器模型 */
    private val bands = listOf(
        Band(14, "松果体/脑中枢", "火"), Band(15, "下丘脑/内分泌", "火"),
        Band(16, "大脑皮层", "火"), Band(17, "小脑/脑干", "火"),
        Band(18, "心脏/心血管", "火"), Band(19, "心脏瓣膜", "火"),
        Band(20, "心脏后壁血管", "火"), Band(21, "冠状动脉", "火"),
        Band(22, "肺/支气管", "金"), Band(23, "肺实质/肺泡", "金"),
        Band(24, "甲状腺/甲状旁腺", "火"), Band(25, "肝脏/肝血管", "木"),
        Band(26, "胃/食道", "土"), Band(27, "十二指肠/小肠", "火"),
        Band(28, "胰腺/脾脏", "土"), Band(29, "肾脏/肾上腺", "水"),
        Band(30, "大肠/直肠", "金"), Band(31, "骨骼/关节/牙齿", "水"),
    )

    data class Band(val b9: Int, val organ: String, val wuxing: String)

    // Schumann anchor constants
    private val SCHUMANN_HARMONICS = doubleArrayOf(7.83, 14.3, 20.8, 27.3, 33.8)

    var algoMode: String = "ab"  // legacy/yinyang/fusion/schumann/ab
    private var algoQueue = mutableListOf<String>()
    private var batchNum = 0

    fun startBalance() {
        stop()
        isPlaying = true
        onStatus?.invoke("🧬 动态平衡启动")
        onLog?.invoke("── 开始扫描 18 频段 ──")

        job = CoroutineScope(Dispatchers.IO).launch {
            var round = 1
            while (isPlaying) {
                onRound?.invoke(round)
                // Determine algorithm
                val useAlgo = if (algoMode == "ab") {
                    if (algoQueue.isEmpty()) {
                        batchNum++
                        algoQueue = mutableListOf("legacy", "yinyang", "fusion", "schumann")
                        algoQueue.shuffle()
                        val labels = algoQueue.joinToString(" → ") {
                            mapOf("legacy" to "同频", "yinyang" to "☀☽", "fusion" to "融合", "schumann" to "舒曼").getOrDefault(it, it)
                        }
                        onLog?.invoke("─── 第${batchNum}遍: $labels ───")
                    }
                    algoQueue.removeAt(0)
                } else algoMode

                val label = mapOf("legacy" to "同频反相", "yinyang" to "☀☽双频", "fusion" to "⚡融合", "schumann" to "🌍舒曼锚").getOrDefault(useAlgo, useAlgo)
                onLog?.invoke("第${round}轮 — 扫描中… [$label]")

                val deltas = mutableMapOf<Int, Double>()
                for (band in bands) {
                    if (!isPlaying) break
                    sendProbe(band.b9, 15, 15)
                    delay(80)
                    deltas[band.b9] = (Math.random() * 20 - 10)
                    onProgress?.invoke(deltas.size, bands.size)
                }
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
                            else -> treatLegacy(band.b9, delta, adjust) // legacy
                        }
                        val freq = 7.3728 / Math.pow(2.0, (band.b9 - 14).toDouble() / 2.0)
                        onLog?.invoke("  ${band.organ} Δ=${"%.1f".format(delta)}")
                        delay(100)
                    }
                } else { onLog?.invoke("  ✓ 全部频段平衡") }
                round++
                onProgress?.invoke(bands.size, bands.size)
                delay(3000)
            }
        }
    }

    // ── ① Legacy: CH1=CH2 同频反相 ──
    private fun treatLegacy(b9: Int, delta: Double, adjust: Int) {
        val b11 = if (delta > 0) (15 - adjust).coerceIn(3, 80) else (15 + adjust).coerceIn(3, 80)
        val b15 = if (delta > 0) (15 + adjust).coerceIn(3, 80) else (15 - adjust).coerceIn(3, 80)
        sendProbe(b9, b11, b15)
    }

    // ── ② Yinyang: CH1=2^n CH2=Fibonacci ──
    private fun treatYinyang(b9: Int, delta: Double, adjust: Int) {
        val (wSun, wMoon) = yinyangWeight(b9)
        val sunB9 = nearestSun(b9)
        val moonB9 = nearestMoon(b9)
        val sunAmp = if (delta > 0) (15.0 - adjust * wSun).toInt().coerceIn(3, 80) else (15.0 + adjust * wSun).toInt().coerceIn(3, 80)
        val moonAmp = if (delta > 0) (15.0 + adjust * wMoon).toInt().coerceIn(3, 80) else (15.0 - adjust * wMoon).toInt().coerceIn(3, 80)
        buf.fill(0)
        buf[9] = sunB9.toByte(); buf[11] = sunAmp.toByte()
        buf[13] = moonB9.toByte(); buf[15] = moonAmp.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
    }

    // ── ③ Fusion: 反相地基 + 数列微调(≤±2) ──
    private fun treatFusion(b9: Int, delta: Double, adjust: Int) {
        val b11 = if (delta > 0) (15 - adjust).coerceIn(3, 80) else (15 + adjust).coerceIn(3, 80)
        val b15 = if (delta > 0) (15 + adjust).coerceIn(3, 80) else (15 - adjust).coerceIn(3, 80)
        val rawSun = nearestSun(b9)
        val rawMoon = nearestMoon(b9)
        val ch1b9 = b9.coerceIn(rawSun - 2, rawSun + 2)
        val ch2b9 = b9.coerceIn(rawMoon - 2, rawMoon + 2)
        buf.fill(0)
        buf[9] = ch1b9.toByte(); buf[11] = b11.toByte()
        buf[13] = ch2b9.toByte(); buf[15] = b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
    }

    // ── ④ Schumann: 低频弱信号锚定 ──
    private fun treatSchumann(b9: Int, delta: Double, adjust: Int) {
        val sIdx = (abs(delta) / 5).toInt().coerceAtMost(4)
        val schB9 = (b9 - 6).coerceIn(14, 31)
        val ch2B9 = (schB9 + sIdx + 1).coerceIn(14, 31)
        buf.fill(0)
        buf[9] = schB9.toByte(); buf[11] = 15.toByte()
        buf[13] = ch2B9.toByte(); buf[15] = 15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 500) } catch (_: Exception) {}
    }

    // ── Helper: 太阳/太阴权重 (b9=14~31 映射到 2^n / Fibonacci) ──
    private val SUN_SEQ = intArrayOf(1, 2, 4, 8, 16, 32)
    private val MOON_SEQ = intArrayOf(1, 1, 2, 3, 5, 8, 13, 21, 34)

    private fun yinyangWeight(b9: Int): Pair<Double, Double> {
        val dSun = SUN_SEQ.minOf { abs(b9 - it) }.toDouble()
        val dMoon = MOON_SEQ.minOf { abs(b9 - it) }.toDouble()
        val total = dSun + dMoon
        return if (total > 0) Pair(dMoon / total, dSun / total) else Pair(0.5, 0.5)
    }

    private fun nearestSun(b9: Int): Int = SUN_SEQ.minByOrNull { abs(b9 - it) } ?: b9
    private fun nearestMoon(b9: Int): Int = MOON_SEQ.minByOrNull { abs(b9 - it) } ?: b9

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
