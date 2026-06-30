package com.nls.selfbalancing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import kotlinx.coroutines.*

class TherapyEngine(private val ctx: Context) {
    private var connection: UsbDeviceConnection? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null
    private var job: Job? = null
    private var cmds: List<Cmd> = emptyList()
    private var index = 0
    private var interval = 500L
    private var repeatCount = 1
    private var loopMode = true
    private var paused = false
    private val buf = ByteArray(128)

    var onStatus: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null
    var isPlaying = false
        private set
    var isPaused = false
        private set
    var isConnected = false
        private set

    private val actionUsbPermission = "com.nls.handring.USB_PERMISSION"
    private var permCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                permCallback?.invoke(granted)
                permCallback = null
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
        isConnected = false; isPlaying = false; isPaused = false
        onStatus?.invoke("已断开")
    }

    fun playProgram(programKey: String, powerLevel: Int?, loop: Boolean) {
        stop()
        val prog = TherapyData.programs[programKey] ?: return
        val filtered = if (powerLevel != null) {
            prog.cmds.filter { it.b11 == powerLevel || kotlin.math.abs(it.b11 - powerLevel) <= 2 }.ifEmpty { prog.cmds }
        } else prog.cmds
        startPlaying(prog.organ, filtered, loop)
    }

    fun playAllPrograms(loop: Boolean) {
        stop()
        val all = mutableListOf<Cmd>()
        for (key in TherapyData.programs.keys.sortedBy { TherapyData.programs[it]!!.b9 }) {
            all.addAll(TherapyData.programs[key]!!.cmds)
        }
        startPlaying("全部器官", all, loop)
    }

    private fun startPlaying(label: String, cmdList: List<Cmd>, loop: Boolean) {
        cmds = cmdList; index = 0; loopMode = loop; isPlaying = true; isPaused = false; paused = false
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isPlaying && cmds.isNotEmpty()) {
                // 等待暂停恢复
                while (paused && isPlaying) { delay(200) }
                if (!isPlaying) break

                val cmd = cmds[index % cmds.size]
                for (r in 0 until repeatCount) {
                    if (!isPlaying || paused) break
                    sendRaw(cmd)
                    if (r < repeatCount - 1) delay(interval)
                }
                if (!isPlaying) break
                index++
                onProgress?.invoke(index, cmds.size)
                onStatus?.invoke("$label #$index/${cmds.size} | ${cmdInfo(cmd)}")
                if (!loop && index >= cmds.size) { stop(); break }
                delay(interval)
            }
        }
    }

    fun pause() {
        if (isPlaying && !isPaused) { paused = true; isPaused = true; onStatus?.invoke("⏸ 已暂停") }
    }

    fun resume() {
        if (isPlaying && isPaused) { paused = false; isPaused = false; onStatus?.invoke("▶ 已继续") }
    }

    fun togglePause() { if (isPaused) resume() else pause() }

    private fun cmdInfo(cmd: Cmd): String {
        val f1 = 7.3728 * Math.pow(2.0, cmd.b9 / 4.0) * 3
        val f2 = 7.3728 * Math.pow(2.0, cmd.b13 / 4.0) * 3
        val a1 = cmd.b11 * 100 / 172
        val a2 = cmd.b15 * 100 / 172
        return buildString {
            append(String.format("CH1:%.0fMHz %d%%", f1, a1))
            append(String.format(" CH2:%.0fMHz %d%%", f2, a2))
            if (cmd.b9 == cmd.b13 && cmd.b11 == cmd.b15) append(" 同相同幅")
            else if (cmd.b9 == cmd.b13) append(" 同频异幅")
            else append(" 异频")
        }
    }

    private fun sendRaw(cmd: Cmd) {
        buf[9] = cmd.b9.toByte(); buf[11] = cmd.b11.toByte()
        buf[13] = cmd.b13.toByte(); buf[15] = cmd.b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 1000) } catch (_: Exception) {}
    }

    fun startDynamicBalance(loop: Boolean) {
        stop()
        // 构建全部器官命令列表，每个器官使用反相双通道
        val allCmds = mutableListOf<Cmd>()
        val keys = TherapyData.programs.keys.sortedBy { TherapyData.programs[it]!!.b9 }
        for (key in keys) {
            val prog = TherapyData.programs[key]!!
            for (cmd in prog.cmds) {
                // 反相平衡: CH1正常发, CH2反向振幅(激发vs抑制)
                val balanceB15 = if (cmd.b11 > 15) (15 - (cmd.b11 - 15)).coerceIn(3, 80) else (15 + (15 - cmd.b11)).coerceIn(3, 80)
                allCmds.add(Cmd(cmd.b9, cmd.b11, cmd.b13, balanceB15))
            }
        }
        
        cmds = allCmds; index = 0; loopMode = loop; isPlaying = true; isPaused = false; paused = false
        onStatus?.invoke("🧬 动态平衡启动 (${allCmds.size}条)")
        
        job = CoroutineScope(Dispatchers.IO).launch {
            var round = 0
            while (isPlaying && cmds.isNotEmpty()) {
                round++
                onStatus?.invoke("🧬 动态平衡 第$round 轮")
                
                for (i in cmds.indices) {
                    if (!isPlaying) break
                    if (paused) { while (paused && isPlaying) delay(200) }
                    if (!isPlaying) break
                    
                    sendRaw(cmds[i])
                    index = i + 1
                    onProgress?.invoke(i + 1, cmds.size)
                    delay(interval)
                }
                
                if (!loopMode) { isPlaying = false; break }
            }
            
            if (isPlaying) { stop() }
            onStatus?.invoke("🧬 动态平衡完成")
        }
    }

    fun stop() {
        isPlaying = false; isPaused = false; paused = false
        job?.cancel(); job = null
        // 发送关闭命令（全零128字节，关闭LED）
        try {
            val zero = ByteArray(128)
            connection?.bulkTransfer(epOut, zero, zero.size, 500)
            Thread.sleep(50)
            connection?.bulkTransfer(epOut, zero, zero.size, 500)
        } catch (_: Exception) {}
        index = 0; cmds = emptyList()
        onProgress?.invoke(0, 0)
        onStatus?.invoke("⏹ 已停止")
    }

    fun setInterval(ms: Long) { interval = ms }
    fun setRepeat(n: Int) { repeatCount = n.coerceIn(1, 20) }
    fun destroy() { disconnect(); try { ctx.unregisterReceiver(usbReceiver) } catch (_: Exception) {} }
}
