package com.vex.client

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
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
 *  ارتباط بین دو پروسه از طریق فایل پورت HTTP (filesDir/lantern_port.txt) و فایل وضعیت
 *  (filesDir/lantern_state.txt) انجام می‌شود — چون پروکسی محلی لنترن روی 127.0.0.1 گوش
 *  می‌دهد، هسته‌ی sing-box پروسه‌ی اصلی می‌تواند بدون هیچ واسطه‌ای به آن وصل شود.
 *
 * نکته‌ی حیاتی (رفع «اتصال برقرار نمیشه»):
 *  Go SDK فقط «یک» نمونه‌ی flashlight در هر پروسه بالا می‌آورد — پنجره‌ی داخلی Start فقط
 *  ۵ ثانیه است؛ اگر در این پنجره جا نیفتد، بوت در پس‌زمینه ادامه پیدا می‌کند. پس هرگز
 *  نمونه‌ی دوم نمی‌سازیم (نمونه‌ی دوم روی قفل فایل کانفیگ می‌میرد و پروسه را می‌کشد)
 *  و به‌جایش پورت را با httpProxyPort() تا آماده شدن واقعی پول می‌کنیم.
 *
 * لنترن هیچ سروری از کاربر نمی‌خواهد — هسته‌ی flashlight خودش بهترین مسیر را از
 * شبکه‌ی ابری لنترن (دامنه‌های فرانتینگ CDN + رله‌های پشتیبان) انتخاب می‌کند.
 */
object LanternEngine {

    private const val TAG = "VexLantern"

    /** فایل پورت — بین پروسه‌ها مشترک است */
    fun portFile(ctx: Context): File = File(ctx.filesDir, "lantern_port.txt")

    /** فایل وضعیت — starting | ready | failed | stopped */
    private fun stateFile(ctx: Context): File = File(ctx.filesDir, "lantern_state.txt")

    fun readState(ctx: Context): String {
        return try { stateFile(ctx).readText().trim() } catch (_: Throwable) { "" }
    }

    private fun writeState(ctx: Context, v: String) {
        try { stateFile(ctx).writeText(v) } catch (_: Throwable) {}
    }

    fun readPort(ctx: Context): Int {
        return try {
            val t = portFile(ctx).readText().trim()
            val p = t.toIntOrNull()?.coerceIn(0, 65535) ?: 0
            if (p > 0 && t.toLong() in 1..65535) p else 0
        } catch (_: Throwable) { 0 }
    }

    fun clearPort(ctx: Context) {
        try { portFile(ctx).delete() } catch (_: Throwable) {}
        try { stateFile(ctx).delete() } catch (_: Throwable) {}
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
     * راه‌اندازی هسته‌ی لنترن (در پروسه‌ی :lantern) — بلاک نمی‌کند.
     * حیاتی: فقط «یک» نمونه‌ی flashlight در عمر پروسه ساخته می‌شود.
     *  • پنجره‌ی داخلی Start در Go فقط ۵ ثانیه است — اگر رد شد، بوت هنوز در جریان است؛
     *    پس با httpProxyPort() تا ~۵۰ ثانیه پول می‌کنیم (هر فراخوانی خودش تا ۵ ثانیه صبر می‌کند).
     *  • ساختن نمونه‌ی دوم flashlight ممنوع: روی قفل انحصاری فایل کانفیگ (bbolt) می‌میرد
     *    و با log.Fatalf کل پروسه‌ی :lantern را می‌کشد — ریشه‌ی «اتصال برقرار نمیشه».
     */
    fun start(ctx: Context) {
        synchronized(this) {
            if (client != null || pending != null) return
            httpPort = 0
            state = "connecting"
            appCtx = ctx.applicationContext
            clearPort(ctx)
            val ctx0 = appCtx ?: return
            writeState(ctx0, "starting")
            Thread {
                var c: lantern.LanternClient? = null
                try {
                    val c0 = lantern.Lantern.newLanternClient()
                    c = c0
                    synchronized(this@LanternEngine) {
                        if (state == "stopped") return@Thread
                        pending = c0
                    }
                    val app = appCtx ?: return@Thread
                    // کانفیگ لنترن داخل حافظه‌ی خود اپ — بدون برنامه‌ی جداگانه
                    val cfgDir = File(app.filesDir, "lantern_config")
                    try { cfgDir.mkdirs() } catch (_: Throwable) {}
                    c0.setup("VexVPN", cfgDir.absolutePath)
                    // پورت ۰ = سیستم خودش پورت آزاد انتخاب می‌کند؛ آدرس واقعی از StartResult می‌آید
                    // proxyAll=true ⇒ همه‌ی ترافیک از شبکه‌ی ضدسانچر لنترن عبور کند
                    var port = 0
                    try {
                        val res = c0.start(":0", true)
                        port = res?.getAddr()?.substringAfterLast(':')?.toIntOrNull() ?: 0
                    } catch (e: Throwable) {
                        // پنجره‌ی ۵ ثانیه‌ای رد شد — بوت flashlight هنوز ادامه دارد؛
                        // پول‌کردن پورت همین‌جا معنا دارد، ساختن نمونه‌ی جدید هرگز.
                        Log.w(TAG, "start() window missed (${e.message}) — keep polling the same instance")
                    }
                    if (port <= 0) {
                        for (i in 0 until 9) {
                            synchronized(this@LanternEngine) { if (state == "stopped") return@Thread }
                            port = try { c0.httpProxyPort().toInt() } catch (_: Throwable) { 0 }
                            if (port > 0) break
                            try { Thread.sleep(1200) } catch (_: InterruptedException) { return@Thread }
                        }
                    }
                    if (port <= 0) throw Exception("Lantern HTTP proxy never became ready")
                    synchronized(this@LanternEngine) {
                        if (state == "stopped") { // کاربر وسط راه قطع کرده
                            try { c0.stop() } catch (_: Throwable) {}
                            return@Thread
                        }
                        client = c0
                        pending = null
                        httpPort = port
                        state = "connected"
                        try { portFile(app).writeText(port.toString()) } catch (_: Throwable) {}
                        writeState(app, "ready")
                        Log.i(TAG, "lantern http proxy listening on 127.0.0.1:$port")
                        // گرم‌کردن مسیر در پس‌زمینه (اولین اتصال به کانفیگ ابری لنترن)
                        warmUp(port)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "lantern start failed: ${e.message}")
                    synchronized(this@LanternEngine) { pending = null }
                    try { c?.stop() } catch (_: Throwable) {}
                    val app = appCtx
                    synchronized(this@LanternEngine) {
                        if (state != "stopped") state = "failed"
                    }
                    if (app != null) writeState(app, "failed")
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
        if (ctx != null) {
            clearPort(ctx)
            writeState(ctx, "stopped")
        }
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
