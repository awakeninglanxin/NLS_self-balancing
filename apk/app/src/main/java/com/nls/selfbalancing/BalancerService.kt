package com.nls.selfbalancing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 前台服务：持有 BalancerEngine + WakeLock，确保息屏后 USB 手环不断电
 */
class BalancerService : Service() {

    lateinit var engine: BalancerEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = LocalBinder()

    companion object {
        const val CHANNEL_ID = "balancer_foreground"
        const val NOTIFY_ID = 1

        var uiRound: ((Int) -> Unit)? = null
        var uiStatus: ((String) -> Unit)? = null
        var uiProgress: ((Int, Int) -> Unit)? = null
        var uiLog: ((String) -> Unit)? = null
        var uiChart: ((List<BalancerEngine.ChartDelta>, Map<String, Double>) -> Unit)? = null
        var uiBatchReport: ((Int, Map<String, BalancerEngine.AlgoStat>) -> Unit)? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): BalancerService = this@BalancerService
    }

    override fun onCreate() {
        super.onCreate()
        engine = BalancerEngine(this)
        engine.onRound = { r -> uiRound?.invoke(r) }
        engine.onStatus = { msg -> uiStatus?.invoke(msg) }
        engine.onProgress = { cur, total -> uiProgress?.invoke(cur, total) }
        engine.onLog = { msg -> uiLog?.invoke(msg) }
        engine.onChart = { deltas, wx -> uiChart?.invoke(deltas, wx) }
        engine.onBatchReport = { n, s -> uiBatchReport?.invoke(n, s) }
        createNotificationChannel()
        startForeground(NOTIFY_ID, buildNotification("就绪"))  // 必须在5秒内调用, 否则Android杀进程
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // MainActivity 在 engine.startBalance() 后调用这两个
    fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NLSBalance::WakeLock")
        wakeLock?.acquire()
    }

    fun startForegroundSafe(label: String) {
        startForeground(NOTIFY_ID, buildNotification(label))
    }

    fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    fun connect(callback: (Boolean, String) -> Unit) = engine.connect(callback)

    fun disconnect() {
        engine.disconnect()
        releaseWakeLock()
        updateNotification("就绪")
    }

    fun stop() {
        engine.stop()
        releaseWakeLock()
        updateNotification("就绪")
    }

    private fun updateNotification(label: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification(label))
    }

    val isConnected get() = engine.isConnected

    // ── 通知 ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "动态平衡服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "NLS动态平衡后台运行通知"
                setShowBadge(false)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(this)
            }
        }
    }

    private fun buildNotification(label: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("NLS动态平衡 · 正在运行")
            .setContentText(label)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        engine.stop()
        engine.destroy()
        releaseWakeLock()
        super.onDestroy()
    }
}
