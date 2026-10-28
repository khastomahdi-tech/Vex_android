package com.vex.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.util.Timer
import java.util.TimerTask

class VexVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.vex.client.START"
        const val ACTION_STOP = "com.vex.client.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "vex_vpn"
        private const val NOTIF_ID = 1

        @Volatile var stateListener: ((String, String) -> Unit)? = null
        @Volatile var statsListener: ((Long, Long, Long, Long) -> Unit)? = null
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: CoreEngine? = null
    private var timer: Timer? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        // کانال با اهمیت پایین: بدون صدا و بدون پاپ‌آپ مزاحم
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "وضعیت VEX", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent?.getStringExtra(EXTRA_CONFIG).orEmpty())
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(config: String) {
        // باید سریع فورگراند شویم، وگرنه اندروید سرویس را می‌کشد
        startForeground(NOTIF_ID, notif("در حال اتصال...", "لطفاً چند لحظه صبر کن"))
        try {
            val coreConfig = ConfigBuilder.build(config)

            val fd = Builder()
                .setSession("VEX CLIENT")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)
                .establish() ?: throw IllegalStateException("ساخت تونل ناموفق بود")
            tun = fd

            // اگر هسته وصل نباشد، اینجا خطا می‌دهد و تونل بسته می‌شود تا اینترنت گوشی قطع نماند
            val eng = CoreEngine(coreConfig, fd)
            eng.start()
            engine = eng

            startStatsTimer()
            startForeground(NOTIF_ID, notif("متصل است", "آمار در حال به‌روزرسانی..."))
            stateListener?.invoke("connected", "")
        } catch (e: Exception) {
            stopVpn(error = e.message ?: "خطا در اتصال")
        }
    }

    private fun stopVpn(error: String? = null) {
        timer?.cancel(); timer = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        tun?.close(); tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (error != null) stateListener?.invoke("error", error)
        else stateListener?.invoke("stopped", "")
    }

    private fun startStatsTimer() {
        timer = Timer().also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val s = engine?.stats() ?: return
                    statsListener?.invoke(s[0], s[1], s[2], s[3])
                    // نوتیفیکیشن همیشگی با آمار زنده
                    val body = "↓ ${fmt(s[0])}/s    ↑ ${fmt(s[1])}/s\nکل: ↓ ${fmt(s[2])}   ↑ ${fmt(s[3])}"
                    nm.notify(NOTIF_ID, notif("متصل است", body))
                }
            }, 1000, 1000)
        }
    }

    override fun onRevoke() {
        stopVpn()
    }

    private fun notif(title: String, body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VexVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VEX CLIENT — $title")
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .addAction(0, "قطع اتصال", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun fmt(n: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${v.toLong()} B" else String.format("%.2f %s", v, units[i])
    }
}
