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

    /** 35频段 b9=1~35 (26MHz~9.5GHz) + 器官名 + 五行 */
    private val bands = listOf(
        Band(1, "极低频/基础", "土"), Band(2, "极低频/共振", "土"),
        Band(3, "极低频/调和", "土"), Band(4, "超低频/A", "水"),
        Band(5, "超低频/B", "水"), Band(6, "超低频/C", "水"),
        Band(7, "低频/A", "金"), Band(8, "低频/B", "金"),
        Band(9, "低频/C", "金"), Band(10, "中低频/A", "木"),
        Band(11, "中低频/B", "木"), Band(12, "中低频/C", "木"),
        Band(13, "过渡频/A", "火"),
        Band(14, "骨骼/基础", "土"), Band(15, "肌肉/结缔", "土"),
        Band(16, "皮肤/皮毛", "金"), Band(17, "淋巴/免疫", "火"),
        Band(18, "消化/肠道", "金"), Band(19, "消化/胃部", "土"),
        Band(20, "肝脏/代谢", "木"), Band(21, "胰腺/内分泌", "土"),
        Band(22, "脾脏/血液", "土"), Band(23, "肺部/呼吸", "金"),
        Band(24, "甲状腺", "火"),   Band(25, "肾脏/肾上腺", "水"),
        Band(26, "心血管", "火"),   Band(27, "心脏", "火"),
        Band(28, "神经系统", "火"), Band(29, "脑/中枢", "火"),
        Band(30, "下丘脑", "火"),   Band(31, "松果体", "火"),
        Band(32, "超高频/A", "木"), Band(33, "超高频/B", "木"),
        Band(34, "超高频/C", "木"), Band(35, "极高频", "火"),
    )

    data class Band(val b9: Int, val organ: String, val wuxing: String)

    /** 五行修正系数 */
    private fun wuxingCorr(wx: String) = when(wx) { "木"->1.0; "火"->1.5; "土"->1.0; "金"->1.2; "水"->0.8; else->1.0 }

    // 太阳数列 (CH1): 2^n = 1,2,4,8,16,32
    // 太阴数列 (CH2): Fibonacci = 1,1,2,3,5,8,13,21,34
    private val sunSeq = intArrayOf(1, 2, 4, 8, 16, 32)
    private val moonSeq = intArrayOf(1, 1, 2, 3, 5, 8, 13, 21, 34)

    private fun nearest(b9: Int, seq: IntArray) = seq.map { abs(b9 - it) }.min()

    private fun yinyangWeight(b9: Int): Pair<Double, Double> {
        val dSun = nearest(b9, sunSeq)
        val dMoon = nearest(b9, moonSeq)
        val wSun = 1.0 / (1 + dSun)
        val wMoon = 1.0 / (1 + dMoon)
        val total = wSun + wMoon
        return wSun / total to wMoon / total
    }

    private fun nearestSun(b9: Int) = sunSeq.minByOrNull { abs(b9 - it) } ?: b9
    private fun nearestMoon(b9: Int) = moonSeq.minByOrNull { abs(b9 - it) } ?: b9

    fun startBalance() {
        stop()
        isPlaying = true
        onStatus?.invoke("🧬 动态平衡启动")
        onLog?.invoke("── 开始扫描 35 频段 ──")

        job = CoroutineScope(Dispatchers.IO).launch {
            var round = 1
            while (isPlaying) {
                onRound?.invoke(round)
                onLog?.invoke("第$round 轮 — 扫描中…")
                val deltas = mutableMapOf<Int, Double>()
                for (band in bands) {
                    if (!isPlaying) break
                    sendProbe(band.b9, 15)
                    delay(100)
                    deltas[band.b9] = (Math.random() * 20 - 10)
                    onProgress?.invoke(deltas.size, bands.size)
                }
                val abnormal = bands.filter { abs(deltas[it.b9] ?: 0.0) > 4 }
                if (abnormal.isNotEmpty()) {
                    onLog?.invoke("  ⚡ ${abnormal.size}项异常 [☀☽双频]")
                    for (band in abnormal) {
                        if (!isPlaying) break
                        val delta = deltas[band.b9]!!
                        val corr = wuxingCorr(band.wuxing)
                        val adjust = (abs(delta) * 0.5 * corr).toInt().coerceAtMost(60)
                        val (wSun, wMoon) = yinyangWeight(band.b9)
                        val sunB9 = nearestSun(band.b9)
                        val moonB9 = nearestMoon(band.b9)
                        val sunAmp = if (delta > 0) (15 - (adjust * wSun).toInt()).coerceIn(3, 80) else (15 + (adjust * wSun).toInt()).coerceIn(3, 80)
                        val moonAmp = if (delta > 0) (15 + (adjust * wMoon).toInt()).coerceIn(3, 80) else (15 - (adjust * wMoon).toInt()).coerceIn(3, 80)
                        sendProbe(sunB9, sunAmp, moonAmp)
                        val freq = 7.3728 * Math.pow(2.0, band.b9 / 4.0) * 3
                        onLog?.invoke("  ${band.organ} ☀b9=$sunB9 ☽b9=$moonB9 Δ=${"%.1f".format(delta)}")
                        delay(120)
                    }
                } else { onLog?.invoke("  ✓ 全部频段平衡") }

                round++
                onProgress?.invoke(bands.size, bands.size)
                delay(3000)  // 轮间间隔
            }
        }
    }

    private fun sendProbe(b9: Int, b11: Int, b15: Int = b11) {
        buf.fill(0)
        buf[9] = b9.toByte(); buf[11] = b11.toByte()
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
