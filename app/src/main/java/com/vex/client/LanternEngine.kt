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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
 * V5.9 — ریشه‌یابی «process is bad» (خطای گزارش‌شده‌ی کاربر):
 *  وقتی هسته‌ی Go (flashlight) در لحظه‌ی بوت خطای مرگبار می‌گیرد (log.Fatalf / panic)،
 *  کل پروسه‌ی :lantern می‌میرد و هیچ try/catch جاوا نمی‌تواند جلویش را بگیرد. اگر بلافاصله
 *  پروسه‌ی تازه ساخته شود و دوباره بمیرد، اندروید پروسه را «bad» علامت می‌زند و دیگر اجازه‌ی
 *  اجرا نمی‌دهد: «Unable to launch app ... process is bad». سه پادزهر در این نسخه:
 *   ۱) کانفیگ تازه برای هر بوت: هر بوتِ flashlight یک پوشه‌ی کانفیگ نو می‌گیرد
 *      (lantern_config/b<timestamp>) — اگر کانفیگ قبلی خراب/مسوم شده باشد، دیگر بوت‌های
 *      بعدی را نمی‌کشد و همیشه «بوت تمیز با state نو» یعنی همان چیزی که کاربر خواسته:
 *      هر بار انتخاب تازه‌ی بهترین سرور از شبکه‌ی ابری لنترن.
 *   ۲) بوت مجدد «درون-پروسه‌ای»: اگر بوت شکست بخورد ولی پروسه زنده باشد، همان پروسه با
 *      نمونه‌ی نو دوباره بوت می‌شود — بدون ساخت پروسه‌ی جدید، بدون ریسک black-list.
 *   ۳) PID + تشخیص مرگ پروسه: پروسه‌ی :lantern PID خودش را می‌نویسد؛ پروسه‌ی اصلی قبل از
 *      هر spawn جدید مطمئن می‌شود پروسه‌ی قبلی واقعاً مرده (چک /proc/<pid>/cmdline) و
 *      حداکثر ۲ پروسه در هر جلسه ساخته می‌شود — هرگز به «process is bad» نمی‌رسیم.
 *
 * نکته‌ی حیاتی: Go SDK فقط «یک» نمونه‌ی flashlight در هر لحظه بالا می‌آورد — هرگز نمونه‌ی
 * دومِ هم‌زمان نمی‌سازیم (قفل guard داخلی). بوت مجدد فقط بعد از شکست/توقف نمونه‌ی قبلی.
 *
 * لنترن هیچ سروری از کاربر نمی‌خواهد — هسته‌ی flashlight خودش بهترین مسیر را از
 * شبکه‌ی ابری لنترن (دامنه‌های فرانتینگ CDN + رله‌های پشتیبان) انتخاب می‌کند.
 */
object LanternEngine {

    private const val TAG = "VexLantern"

    private val bootLogLock = Any()
    private val tsFmt by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    /** فایل پورت — بین پروسه‌ها مشترک است */
    fun portFile(ctx: Context): File = File(ctx.filesDir, "lantern_port.txt")

    /** فایل PID پروسه‌ی :lantern — برای تشخیص مرگ پروسه از پروسه‌ی اصلی */
    fun pidFile(ctx: Context): File = File(ctx.filesDir, "lantern_pid.txt")

    /** فایل وضعیت — starting | ready | failed | stopped */
    private fun stateFile(ctx: Context): File = File(ctx.filesDir, "lantern_state.txt")

    /** لاگ بوت — برای نمایش در «جزئیات خطا» (دیگه لاگ‌کور کورکورانه نیستیم) */
    fun bootLogFile(ctx: Context): File = File(ctx.filesDir, "lantern_bootlog.txt")

    /** گزارش کرش — توسط سپر کرش LanternService نوشته می‌شود */
    fun crashFile(ctx: Context): File = File(ctx.filesDir, "lantern_crash.txt")

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

    fun readPid(ctx: Context): Int {
        return try { pidFile(ctx).readText().trim().toIntOrNull() ?: 0 } catch (_: Throwable) { 0 }
    }

    /** نوشتن PID — از داخل پروسه‌ی :lantern (LanternService.onCreate) */
    fun writePid(ctx: Context) {
        try { pidFile(ctx).writeText(android.os.Process.myPid().toString()) } catch (_: Throwable) {}
    }

    /**
     * آیا پروسه‌ی :lantern واقعاً زنده است؟
     * چک PID به‌تنهایی خطرناک است (PID بازیافتی ممکن است مال پروسه‌ی دیگری باشد) —
     * برای همین /proc/<pid>/cmdline هم خوانده می‌شود؛ فقط اگر مال خودمان بود «زنده» است.
     * PID نامعلوم (۰) → «زنده» فرض می‌شود تا مانع spawn نشویم.
     */
    fun isProcessAlive(ctx: Context): Boolean {
        val pid = readPid(ctx)
        if (pid <= 0) return true
        val cmd = try {
            val bytes = File("/proc/$pid/cmdline").readBytes()
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) { "" }
        return cmd.contains(":lantern")
    }

    /** انتظار برای مرگ واقعی پروسه‌ی :lantern (حداکثر timeoutMs) — قبل از هر spawn جدید */
    fun waitProcessGone(ctx: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pid = readPid(ctx)
            if (pid <= 0) return true
            val cmd = try { String(File("/proc/$pid/cmdline").readBytes(), Charsets.UTF_8) } catch (_: Throwable) { "" }
            if (!cmd.contains(":lantern")) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return true }
        }
        return !isProcessAlive(ctx)
    }

    fun clearPort(ctx: Context) {
        try { portFile(ctx).delete() } catch (_: Throwable) {}
        try { stateFile(ctx).delete() } catch (_: Throwable) {}
        try { pidFile(ctx).delete() } catch (_: Throwable) {}
    }

    /** پورت HTTP محلی لنترن — ۰ یعنی هنوز آماده نشده */
    @Volatile
    var httpPort: Int = 0
        private set

    /** idle | connecting | connected | failed | stopped | exhausted */
    @Volatile
    var state: String = "idle"
        private set

    // ------------------------------------------------------------------ پایش سلامت (V5.10)

    /**
     * سپر سلامت مسیر لنترن — ریشه‌ی «وصل میشه ولی کار نمیکنه»:
     * پورت HTTP لنترن باز می‌شود ولی ممکن است مسیر خروجیِ flashlight (سرور انتخابی از
     * شبکه‌ی ابری) روی اپراتور کاربر از کار بیفتد. واچ‌داگ هر ۵ ثانیه یک پروب واقعی از
     * «داخل» پروکسی لنترن می‌زند (بدون واسطه‌ی هسته/تونل)؛ ۴ شکست پشت‌سرهم یعنی مسیر مرده
     * ⇒ بوت مجدد درون-پروسه‌ای = انتخاب تازه‌ی بهترین سرور از شبکه‌ی ابری لنترن — کاملاً
     * بی‌صدا، بدون هیچ پیام و بدون ساخت پروسه‌ی جدید (ضد «process is bad»).
     * بعد از ۴ بوت مجدد ناموفق → وضعیت «exhausted» تا پروسه‌ی اصلی سریع و بی‌صدا فال‌بک کند.
     */
    private const val WATCHDOG_PROBE_INTERVAL_MS = 5_000L
    private const val WATCHDOG_FAIL_STREAK = 4
    private const val WATCHDOG_MAX_REBOOTS = 4
    private const val PROBE_TIMEOUT_MS = 9_000

    @Volatile
    private var watchdogThread: Thread? = null
    private val watchdogBusy = AtomicBoolean(false)

    /** نتیجه‌ی ۶ پروب آخر برای گزارش تشخیصی (۲۰۰ تا ۳۹۹ = سلامت) */
    private val probeHistory = ArrayDeque<String>(8)
    @Volatile
    var lastProbeCode: Int = -2
        private set

    private fun recordProbe(code: Int) {
        lastProbeCode = code
        synchronized(bootLogLock) {
            if (probeHistory.size >= 6) probeHistory.removeFirst()
            probeHistory.addLast(if (code in 200..399) "$code OK" else "$code FAIL")
        }
    }

    fun probeStats(): String = synchronized(bootLogLock) {
        if (probeHistory.isEmpty()) "—" else probeHistory.joinToString(" · ")
    }

    /**
     * یک پروب واقعی HTTP از داخل پروکسی لنترن (۱۲۷.۰.۰.۱:port) — خارج از تونل.
     * خروجی: کد HTTP (۲۰۰..۳۹۹ = مسیر سالم) یا -1 = شکست اتصال/تایم‌اوت.
     */
    fun probeThrough(port: Int): Int {
        if (port <= 0) return -1
        var code = -1
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            val conn = URL("https://www.gstatic.com/generate_204").openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "VexVPN-health")
            code = try { conn.responseCode } catch (_: Throwable) { -1 }
            try { conn.inputStream?.close() } catch (_: Throwable) {}
            try { conn.errorStream?.close() } catch (_: Throwable) {}
            try { conn.disconnect() } catch (_: Throwable) {}
        } catch (_: Throwable) {
            code = -1
        }
        return code
    }

    /**
     * شروع واچ‌داگ — فقط در پروسه‌ی :lantern بعد از READY صدا زده می‌شود.
     * هر پروب موفق شمارنده‌ها را صفر می‌کند؛ شکست پشت‌سرهم = بوت مجدد درون-پروسه‌ای.
     * V5.10 — واچ‌داگ در برابر بوت‌مجدها مقاوم است: restartInProcess آن را نمی‌کشد و
     * فقط پورت تازه را برمی‌دارد؛ تنها توقف کامل (stop) آن را جمع می‌کند.
     */
    private fun ensureWatchdog(app: Context) {
        if (watchdogThread?.isAlive == true) return
        val th = Thread {
            var reboots = 0
            var failStreak = 0
            try {
                while (!Thread.currentThread().isInterrupted) {
                    try { Thread.sleep(WATCHDOG_PROBE_INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                    // توقف کامل موتور (قطع کاربر) → پایان پایش؛ پورت صفر وسط بوت مجدد = صبر
                    if (state == "stopped" && httpPort <= 0 && !booting.get() && !isRunning()) return@Thread
                    val port = httpPort
                    if (port <= 0) {
                        // بوت در جریان است؛ اگر بوت قبلی صریحاً شکست خورده (state=failed)،
                        // واچ‌داگ خودش بوت مجدد بی‌صدا را امتحان می‌کند (با بودجه‌ی خودش)
                        if (state == "failed" && !booting.get() && !isRunning()) {
                            if (reboots >= WATCHDOG_MAX_REBOOTS) {
                                bootLog(app, "watchdog: boot-failure reboot budget exhausted — exhausted")
                                synchronized(this@LanternEngine) {
                                    state = "exhausted"
                                    writeState(app, "exhausted")
                                }
                                return@Thread
                            }
                            if (watchdogBusy.compareAndSet(false, true)) {
                                reboots++
                                bootLog(app, "watchdog: boot failed — silent reboot #$reboots (fresh best-server pick)")
                                try {
                                    restartInProcess(app)
                                    val deadline = System.currentTimeMillis() + 60_000L
                                    while (System.currentTimeMillis() < deadline) {
                                        if (Thread.currentThread().isInterrupted) return@Thread
                                        if (httpPort > 0) break
                                        try { Thread.sleep(800) } catch (_: InterruptedException) { return@Thread }
                                    }
                                } finally {
                                    watchdogBusy.set(false)
                                }
                            }
                        }
                        continue
                    }
                    val code = probeThrough(port)
                    recordProbe(code)
                    if (code in 200..399) {
                        failStreak = 0
                        reboots = 0 // مسیر سالم شد — سقف بوت مجدد از نو
                        continue
                    }
                    failStreak++
                    bootLog(app, "watchdog: probe FAIL ($failStreak/$WATCHDOG_FAIL_STREAK) code=$code")
                    if (failStreak < WATCHDOG_FAIL_STREAK) continue
                    // مسیر مرده — بوت مجدد بی‌صدا در همان پروسه
                    if (reboots >= WATCHDOG_MAX_REBOOTS) {
                        bootLog(app, "watchdog: reboot budget exhausted — engine marked exhausted")
                        synchronized(this@LanternEngine) {
                            if (client != null || pending != null) {
                                state = "exhausted"
                                writeState(app, "exhausted")
                            }
                        }
                        return@Thread
                    }
                    if (!watchdogBusy.compareAndSet(false, true)) continue
                    reboots++
                    failStreak = 0
                    bootLog(app, "watchdog: silent in-process reboot #$reboots (fresh best-server pick)")
                    try {
                        restartInProcess(app)
                        // صبر تا بوت جدید پورت تازه بدهد (حداکثر ~۶۰ ثانیه)
                        val deadline = System.currentTimeMillis() + 60_000L
                        while (System.currentTimeMillis() < deadline) {
                            if (Thread.currentThread().isInterrupted) return@Thread
                            val p = httpPort
                            if (p > 0) break
                            try { Thread.sleep(800) } catch (_: InterruptedException) { return@Thread }
                        }
                    } finally {
                        watchdogBusy.set(false)
                    }
                }
            } catch (_: Throwable) {}
        }.apply {
            name = "vex-lnt-watchdog"
            isDaemon = true
        }
        watchdogThread = th
        th.start()
    }

    private fun stopWatchdog() {
        try { watchdogThread?.interrupt() } catch (_: Throwable) {}
        watchdogThread = null
    }

    @Volatile
    private var client: lantern.LanternClient? = null

    /** کلاینتِ در حال راه‌اندازی (هنوز پورت نگرفته) — تا قطع حین اتصال هم کار کند */
    @Volatile
    private var pending: lantern.LanternClient? = null

    @Volatile
    private var appCtx: Context? = null

    /** guard بوت — فقط یک بوت هم‌زمان؛ بعد از شکست false می‌شود تا بوت مجدد ممکن باشد */
    private val booting = AtomicBoolean(false)

    /** شمارنده‌ی بوت — commitِ تردهای بوتِ کهنه (بعد از stop/restart) نامعتبر می‌شود */
    private val bootCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // ------------------------------------------------------------------ لاگ بوت

    private fun ts(): String = try { tsFmt.format(Date()) } catch (_: Throwable) { "" }

    private fun bootLogReset(app: Context?) {
        val c = app ?: return
        try { bootLogFile(c).writeText("") } catch (_: Throwable) {}
    }

    private fun bootLog(app: Context?, line: String) {
        Log.i(TAG, line)
        val c = app ?: return
        try {
            synchronized(bootLogLock) { bootLogFile(c).appendText("${ts()} | $line\n") }
        } catch (_: Throwable) {}
    }

    /** گزارش تشخیصی برای «جزئیات خطا» — وضعیت + لاگ بوت + آخرین کرش */
    fun diagReport(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("وضعیت: ").append(if (state != "idle") state else readState(ctx)).append('\n')
        sb.append("پورت: ").append(if (httpPort > 0) httpPort else readPort(ctx)).append('\n')
        sb.append("پروسه‌ی :lantern: ").append(if (isProcessAlive(ctx)) "زنده" else "مرده").append('\n')
        sb.append("پروب‌های اخیر مسیر: ").append(probeStats()).append('\n')
        try {
            val crash = crashFile(ctx)
            if (crash.exists() && crash.length() > 0) {
                sb.append("\n— آخرین کرش پروسه —\n").append(crash.readText().take(1200)).append('\n')
            }
        } catch (_: Throwable) {}
        try {
            val log = bootLogFile(ctx)
            if (log.exists() && log.length() > 0) {
                sb.append("\n— لاگ بوت لنترن —\n")
                val lines = log.readText().lines()
                sb.append(lines.takeLast(Math.min(30, lines.size)).joinToString("\n"))
            }
        } catch (_: Throwable) {}
        return sb.toString()
    }

    // ------------------------------------------------------------------ کانفیگ تازه برای هر بوت

    /**
     * هر بوتِ flashlight یک پوشه‌ی کانفیگ نو می‌گیرد — اگر کانفیگ قبلی (user.conf/
     * global.yaml/datab* نسخه‌های قبلی که وسط نوشتن کشته شده‌اند) خراب شده باشد،
     * دیگر بوت‌های بعدی را نمی‌کشد. فقط ۲ پوشه‌ی آخر برای اشکال‌زدایی نگه داشته می‌شود.
     */
    private fun prepareFreshConfigDir(app: Context): File {
        val root = File(app.filesDir, "lantern_config")
        try { root.mkdirs() } catch (_: Throwable) {}
        try {
            // فایل‌های سرگردان نسخه‌های قدیمی (کـنفیگ در ریشه) — حذف
            root.listFiles()?.filter { it.isFile }?.forEach { f -> try { f.delete() } catch (_: Throwable) {} }
            // پوشه‌های بوت قبلی — فقط ۲ تای آخر می‌ماند
            root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
                ?.drop(2)?.forEach { d -> try { d.deleteRecursively() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        val dir = File(root, "b" + System.currentTimeMillis())
        try { dir.mkdirs() } catch (_: Throwable) {}
        return dir
    }

    // ------------------------------------------------------------------ بوت

    /**
     * راه‌اندازی هسته‌ی لنترن (در پروسه‌ی :lantern) — بلاک نمی‌کند.
     *  • هرگز دو نمونه‌ی هم‌زمان ساخته نمی‌شود (قفل client/pending/booting)؛
     *  • بعد از شکستِ قابل‌گرفتن (state=failed) بوت مجدد در همان پروسه مجاز است —
     *    پروسه‌ی زنده یعنی بدون spawn جدید و بدون ریسک «process is bad».
     */
    fun start(ctx: Context) {
        synchronized(this) {
            if (client != null || pending != null) return
            if (!booting.compareAndSet(false, true)) return
            httpPort = 0
            state = "connecting"
            appCtx = ctx.applicationContext
            val app = appCtx ?: run { booting.set(false); return }
            val myBoot = bootCounter.incrementAndGet()
            clearPort(app)
            writePid(app)
            writeState(app, "starting")
            bootLogReset(app)
            bootLog(app, "boot start — fresh config dir (V5.9)")
            val cfgDir = prepareFreshConfigDir(app)
            bootLog(app, "configDir=${cfgDir.absolutePath}")
            Thread {
                var c: lantern.LanternClient? = null
                try {
                    val c0 = lantern.Lantern.newLanternClient()
                    c = c0
                    synchronized(this@LanternEngine) {
                        if (state == "stopped") { booting.set(false); return@Thread }
                        pending = c0
                    }
                    bootLog(app, "go runtime up — client created")
                    c0.setup("VexVPN", cfgDir.absolutePath)
                    bootLog(app, "setup done — starting flashlight (:0, proxyAll=true)")
                    // پورت ۰ = سیستم خودش پورت آزاد انتخاب می‌کند؛ آدرس واقعی از StartResult می‌آید
                    // proxyAll=true ⇒ همه‌ی ترافیک از شبکه‌ی ضدسانچر لنترن عبور کند
                    var port = 0
                    try {
                        val res = c0.start(":0", true)
                        port = res?.getAddr()?.substringAfterLast(':')?.toIntOrNull() ?: 0
                        bootLog(app, "start() returned addr-port=$port")
                    } catch (e: Throwable) {
                        // پنجره‌ی ۵ ثانیه‌ای رد شد — بوت flashlight هنوز ادامه دارد؛
                        // پول‌کردن پورت همین‌جا معنا دارد، ساختن نمونه‌ی جدید هرگز.
                        bootLog(app, "start() window missed (${e.message}) — keep polling the same instance")
                    }
                    if (port <= 0) {
                        for (i in 0 until 9) {
                            synchronized(this@LanternEngine) {
                                if (state == "stopped" || bootCounter.get() != myBoot) {
                                    booting.set(false); return@Thread
                                }
                            }
                            port = try { c0.httpProxyPort().toInt() } catch (_: Throwable) { 0 }
                            if (port > 0) break
                            try { Thread.sleep(1200) } catch (_: InterruptedException) { booting.set(false); return@Thread }
                        }
                    }
                    if (port <= 0) throw Exception("Lantern HTTP proxy never became ready (poll timeout ~50s)")
                    synchronized(this@LanternEngine) {
                        if (state == "stopped" || bootCounter.get() != myBoot) { // کاربر وسط راه قطع/ری‌استارت کرده
                            booting.set(false)
                            try { c0.stop() } catch (_: Throwable) {}
                            return@Thread
                        }
                        client = c0
                        pending = null
                        httpPort = port
                        state = "connected"
                        booting.set(false)
                        try { portFile(app).writeText(port.toString()) } catch (_: Throwable) {}
                        writeState(app, "ready")
                        bootLog(app, "READY — lantern http proxy listening on 127.0.0.1:$port")
                        // V5.10 — واچ‌داگ سلامت مسیر: پروب هر ۵ ثانیه + بوت مجدد بی‌صدای
                        // flashlight روی مسیر مرده (ریشه‌ی «وصل میشه ولی کار نمیکنه»)
                        ensureWatchdog(app)
                        // گرم‌کردن مسیر در پس‌زمینه (اولین اتصال به کانفیگ ابری لنترن)
                        warmUp(app, port)
                    }
                } catch (e: Throwable) {
                    bootLog(app, "BOOT FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    synchronized(this@LanternEngine) { pending = null }
                    try { c?.stop() } catch (_: Throwable) {}
                    synchronized(this@LanternEngine) {
                        if (state != "stopped") state = "failed"
                        booting.set(false) // بوت مجدد درون-پروسه‌ای مجاز است
                    }
                    writeState(app, "failed")
                }
            }.start()
        }
    }

    /** تست سبک HTTP از داخل پروکسی لنترن — فقط برای لاگ؛ مسیر را زودتر جا می‌اندازد */
    private fun warmUp(app: Context, port: Int) {
        Thread {
            var code = -1
            try {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                val url = URL("https://www.gstatic.com/generate_204")
                val conn = url.openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VexVPN")
                code = try { conn.responseCode } catch (_: Throwable) { -1 }
                try { conn.disconnect() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            bootLog(app, "warm-up probe through lantern proxy: HTTP $code")
        }.start()
    }

    /**
     * بوت مجدد flashlight «درون همان پروسه» — قلب failover بی‌صدای لنترن (V5.9):
     * وقتی سرور لنترن قطع می‌شود، نمونه‌ی فعلی متوقف و نمونه‌ای نو با کانفیگ تازه بوت
     * می‌شود = انتخاب تازه‌ی بهترین سرور از شبکه‌ی ابری لنترن. بدون ساخت پروسه‌ی جدید
     * (ریسک «process is bad» = صفر) و بدون هیچ پیامی به کاربر.
     *
     * V5.10 — واچ‌داگ در این مسیر «زنده می‌ماند» (فقط نمونه عوض می‌شود، نه پایش).
     */
    fun restartInProcess(ctx: Context) {
        synchronized(this) {
            bootCounter.incrementAndGet() // هر بوتِ در جریان را نامعتبر کن
            booting.set(false)
            val c = client
            val pnd = pending
            client = null
            pending = null
            httpPort = 0
            // «connecting» به‌جای «stopped» — تا واچ‌داگ وسط بوت مجدد، توقف کامل تشخیص ندهد
            state = "connecting"
            if (ctx != null) {
                clearPort(ctx)
                writeState(ctx, "starting")
            }
            Thread {
                try { c?.stop() } catch (_: Throwable) {}
                try { pnd?.stop() } catch (_: Throwable) {}
            }.start()
        }
        Thread {
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            start(ctx)
        }.start()
    }

    fun stop(ctx: Context?) {
        synchronized(this) {
            // توقف کامل — واچ‌داگ هم جمع می‌شود (قطع از سمت کاربر/پروسه‌ی اصلی)
            stopWatchdog()
            bootCounter.incrementAndGet() // هر بوتِ در جریان را نامعتبر کن
            booting.set(false)
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
