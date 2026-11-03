package com.vex.client

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * اجراکننده‌ی موتور لنترن — فقط داخل پروسه‌ی جدا «:lantern».
 *
 * چرا پروسه‌ی جدا؟
 *  هم libbox (هسته‌ی sing-box) و هم هسته‌ی Lantern با gomobile ساخته شده‌اند و هر دو
 *  ران‌تایم بومی go.Seq را دارند. اگر در یک پروسه بارگذاری شوند، متدهای بومیِ go.Seq
 *  بین دو کتابخانه‌ی بومی تداخل می‌کند. با جداکردن پروسه:
 *   • پروسه‌ی اصلی → فقط libbox (VPN و هسته‌ی اصلی)
 *   • پروسه‌ی :lantern → فقط libgojni (هسته‌ی رسمی Lantern / flashlight)
 *  ارتباط بین دو پروسه از طریق فایل پورت HTTP (filesDir/lantern_port.txt) انجام می‌شود —
 *  چون پروکسی محلی لنترن روی 127.0.0.1 گوش می‌دهد، هسته‌ی sing-box پروسه‌ی اصلی می‌تواند
 *  بدون هیچ واسطه‌ای به آن وصل شود (loopback بین پروسه‌های یک اپ مشترک است).
 *
 * لنترن هیچ سروری از کاربر نمی‌خواهد — هسته‌ی flashlight خودش بهترین مسیر را از
 * شبکه‌ی ابری لنترن (دامنه‌های فرانتینگ CDN + رله‌های پشتیبان) انتخاب می‌کند.
 */
object LanternEngine {

    private const val TAG = "VexLantern"

    /** فایل پورت — بین پروسه‌ها مشترک است */
    fun portFile(ctx: Context): File = File(ctx.filesDir, "lantern_port.txt")

    fun readPort(ctx: Context): Int {
        return try {
            val t = portFile(ctx).readText().trim()
            val p = t.toIntOrNull()?.coerceIn(0, 65535) ?: 0
            if (p > 0 && t.toLong() in 1..65535) p else 0
        } catch (_: Throwable) { 0 }
    }

    fun clearPort(ctx: Context) {
        try { portFile(ctx).delete() } catch (_: Throwable) {}
    }

    /** پورت HTTP محلی لنترن — ۰ یعنی هنوز آماده نشده */
    @Volatile
    var httpPort: Int = 0
        private set

    /** idle | connecting | connected | failed | stopped */
    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    private var client: lantern.LanternClient? = null

    /** کلاینتِ در حال راه‌اندازی (هنوز پورت نگرفته) — تا قطع حین اتصال هم کار کند */
    @Volatile
    private var pending: lantern.LanternClient? = null

    @Volatile
    private var appCtx: Context? = null

    /**
     * راه‌اندازی هسته‌ی لنترن (در پروسه‌ی :lantern) — بلاک نمی‌کند؛
     * پورت به‌محض آماده‌شدن پروکسی HTTP در فایل و httpPort نوشته می‌شود.
     * تا ۳ تلاش — روی شبکه‌های شلوغ ممکن است بار اول جا نیفتد.
     */
    fun start(ctx: Context) {
        synchronized(this) {
            if (client != null || pending != null) return
            httpPort = 0
            state = "connecting"
            appCtx = ctx.applicationContext
            clearPort(ctx)
            Thread {
                var attempt = 0
                while (attempt < 3 && client == null && state != "stopped") {
                    attempt++
                    var lc: lantern.LanternClient? = null
                    try {
                        val c = lantern.Lantern.newLanternClient()
                        lc = c
                        synchronized(this@LanternEngine) {
                            if (state == "stopped") return@Thread
                            pending = c
                        }
                        val ctx0 = appCtx ?: break
                        // کانفیگ لنترن داخل حافظه‌ی خود اپ — بدون برنامه‌ی جداگانه
                        val cfgDir = File(ctx0.filesDir, "lantern_config")
                        try { cfgDir.mkdirs() } catch (_: Throwable) {}
                        c.setup("VexVPN", cfgDir.absolutePath)
                        // پورت ۰ = سیستم خودش پورت آزاد انتخاب می‌کند؛ آدرس واقعی از StartResult می‌آید
                        // proxyAll=true ⇒ همه‌ی ترافیک از شبکه‌ی ضدسانچر لنترن عبور کند
                        val res = c.start(":0", true)
                        val addr = res?.getAddr() ?: throw Exception("Lantern returned null addr")
                        // addr مثل «127.0.0.1:53210» است — پورت مستقیم از رشته خوانده می‌شود
                        val port = addr.substringAfterLast(':').toIntOrNull() ?: 0
                        if (port <= 0) throw Exception("bad lantern addr: $addr")
                        synchronized(this@LanternEngine) {
                            if (state == "stopped") { // کاربر وسط راه قطع کرده
                                try { c.stop() } catch (_: Throwable) {}
                            } else {
                                client = c
                                pending = null
                                httpPort = port
                                state = "connected"
                                val ctx1 = appCtx
                                if (ctx1 != null) {
                                    try { portFile(ctx1).writeText(port.toString()) } catch (_: Throwable) {}
                                }
                                Log.i(TAG, "lantern http proxy listening on 127.0.0.1:$port")
                                // گرم‌کردن مسیر در پس‌زمینه (اولین اتصال به کانفیگ ابری لنترن)
                                warmUp(port)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "lantern start attempt $attempt failed: ${e.message}")
                        synchronized(this@LanternEngine) { if (pending === lc) pending = null }
                        try { lc?.stop() } catch (_: Throwable) {}
                        if (attempt >= 3) {
                            state = "failed"
                        } else {
                            try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
                        }
                    }
                }
            }.start()
        }
    }

    /** تست سبک HTTP از داخل پروکسی لنترن — فقط برای لاگ؛ مسیر را زودتر جا می‌اندازد */
    private fun warmUp(port: Int) {
        Thread {
            try {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                val url = URL("https://www.gstatic.com/generate_204")
                val conn = url.openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VexVPN")
                val code = try { conn.responseCode } catch (_: Throwable) { -1 }
                try { conn.disconnect() } catch (_: Throwable) {}
                Log.i(TAG, "lantern warm-up: HTTP $code")
            } catch (_: Throwable) {}
        }.start()
    }

    fun stop(ctx: Context?) {
        val c = client
        val pnd = pending
        client = null
        pending = null
        httpPort = 0
        state = "stopped"
        if (ctx != null) clearPort(ctx)
        Thread {
            try { c?.stop() } catch (_: Throwable) {}
            try { pnd?.stop() } catch (_: Throwable) {}
        }.start()
    }

    fun isRunning(): Boolean = client != null || pending != null

    /** اعلان ساوت پیش‌زمینه برای پروسه‌ی لنترن (تا سیستم وسط کار نکشدش) */
    fun buildForegroundNotification(ctx: Context): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(ctx, Notifier.STATUS_CHANNEL_ID)
            .setContentTitle("VEX CLIENT — لنترن")
            .setContentText("موتور لنترن فعال است")
            .setSmallIcon(R.drawable.ic_stat_vex)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }
}
