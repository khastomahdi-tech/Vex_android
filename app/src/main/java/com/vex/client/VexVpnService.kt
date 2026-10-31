package com.vex.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator as BoxNetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

/**
 * سرویس VPN: تونل اندروید را می‌سازد و fd آن را به هسته‌ی sing-box می‌دهد.
 * کلاس VexVpnService خودش PlatformInterface هسته است (الگوی رسمی sing-box-for-android).
 */
class VexVpnService : VpnService(), io.nekohasekai.libbox.PlatformInterface {

    companion object {
        const val ACTION_START = "com.vex.client.START"
        const val ACTION_STOP = "com.vex.client.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "vex_vpn"
        private const val NOTIF_ID = 1
        private const val TAG = "VexVpn"

        @Volatile var stateListener: ((String, String) -> Unit)? = null
        @Volatile var statsListener: ((Long, Long, Long, Long) -> Unit)? = null
        @Volatile var netListener: ((Boolean, Int, String) -> Unit)? = null // ok, ms, پیام
        /** کاشی Quick Settings — با هر تغییر وضعیت، آیکون کاشی تازه می‌شود */
        @Volatile var tileListener: ((String) -> Unit)? = null

        // ---- وضعیت فعلی برای بازگردانی UI (وقتی اکتیویتی دوباره باز می‌شود) ----
        @Volatile var uiState: String = "stopped"   // stopped | connecting | connected
        @Volatile var netState: Int = 0             // 0 ناشناخته، 1 تأیید، 2 رد شد
        @Volatile var netMsg: String = ""
        @Volatile var netMs: Int = -1
        @Volatile var snapDn: Long = 0
        @Volatile var snapUp: Long = 0
        @Volatile var snapTDn: Long = 0
        @Volatile var snapTUp: Long = 0

        /** نمایش سرعت زنده در اعلان (تنظیم کاربر) */
        @Volatile var showNotifSpeed: Boolean = true
        /** زمان اتصال موفق برای محاسبه‌ی مدت جلسه در صفحه‌ی آمار */
        @Volatile var connectedAtMs: Long = 0

        // ---- تنظیمات رفتاری که MainActivity قبل از شروع سرویس ست می‌کند ----
        @Volatile var autoReconnect: Boolean = true
        @Volatile var autoNetTest: Boolean = true

        // ---- per-app مثل v2rayNG: 0 = همه، 1 = فقط انتخاب‌شده‌ها، 2 = همه به‌جز انتخاب‌شده‌ها ----
        @Volatile var perAppMode: Int = 0
        @Volatile var perAppPackages: Set<String> = emptySet()

        // ---- آخرین بار اتصال (برای اتصال مجدد خودکار پس از کشته‌شدن سرویس) ----
        @Volatile var lastPayload: String? = null
        /** جلسه‌ی فعلی/آخر با موتور سایفون اجرا شده است؟ (برای خاموش‌کردن تونل سایفون هنگام قطع) */
        @Volatile var lastEnginePsiphon = false

        // ---- لاگ هسته (برای اشکال‌زدایی واقعی) ----
        private val logBuf = ArrayDeque<String>(400)
        fun pushCoreLog(line: String) {
            synchronized(logBuf) {
                if (logBuf.size >= 400) logBuf.removeFirst()
                logBuf.addLast(line)
            }
        }
        fun coreLogTail(n: Int = 8): String = synchronized(logBuf) {
            logBuf.takeLast(n).joinToString("\n")
        }
        fun coreLogAll(): String = synchronized(logBuf) {
            logBuf.joinToString("\n")
        }
        fun clearCoreLogs() = synchronized(logBuf) { logBuf.clear() }

        /** آخرین خطای ثبت‌شده‌ی هسته برای نمایش به کاربر */
        fun lastCoreError(): String? = synchronized(logBuf) {
            logBuf.lastOrNull { it.contains("ERROR", true) || it.contains("error", true) }?.let {
                it.substringAfter("ERROR ").ifEmpty { it }.take(220)
            }
        }

        /** درخواست تست فوری اینترنت (دکمه‌ی «تست اینترنت» در UI) */
        @Volatile var testNow = false
        fun requestNetTest() { testNow = true }
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: CoreEngine? = null
    private var timer: Timer? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private val stateLock = Any()
    private var generation = 0

    // ---- آزمون اینترنت پس‌زمینه (مانند v2rayNG: اتصال را قطع نمی‌کنیم) ----
    private var testThread: Thread? = null

    // ---- آمار واقعی از شمارنده‌ی اینترفیس tun ----
    @Volatile private var tunIfaceName: String? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var totalRx = 0L
    private var totalTx = 0L
    @Volatile private var statSource = 0 // 1 = شمارنده tun ، 2 = TrafficStats
    @Volatile private var verified = false // فقط بعد از تست واقعی اینترنت true می‌شود

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "وضعیت VEX", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_START -> startVpn(intent?.getStringExtra(EXTRA_CONFIG).orEmpty())
            intent?.action == ACTION_STOP -> stopVpn()
            // بازراه‌اندازی خودکار توسط سیستم (START_STICKY): دوباره به همان سرور وصل شو
            intent == null && autoReconnect && !lastPayload.isNullOrEmpty() && uiState == "stopped" -> {
                Log.i(TAG, "service restarted by system — auto reconnect")
                startVpn(lastPayload!!)
            }
        }
        return START_STICKY
    }

    private fun startVpn(config: String) {
        // باید سریع فورگراند شویم، وگرنه اندروید سرویس را می‌کشد
        // موتور اتصال: «psiphon» = فقط سایفون · «hybrid» = ترکیبی (سرور Vex از داخل سایفون)
        val engMode = try { JSONObject(config).optString("engine") } catch (_: Throwable) { "" }
        val usePsiphonProc = engMode == "psiphon" || engMode == "hybrid"
        lastEnginePsiphon = usePsiphonProc
        startForeground(NOTIF_ID, notif(
            when (engMode) {
                "hybrid" -> "در حال اتصال... (ترکیبی Vex + سایفون)"
                "psiphon" -> "در حال اتصال... (سایفون)"
                else -> "در حال اتصال..."
            },
            if (usePsiphonProc) "راه‌اندازی موتور سایفون..." else "راه‌اندازی هسته..."))
        uiState = "connecting"
        tileListener?.invoke("connecting")
        netState = 0; netMsg = ""; netMs = -1
        lastPayload = config
        clearCoreLogs()
        val gen: Int
        synchronized(stateLock) { gen = ++generation }
        thread(name = "vex-core") {
            try {
                CoreEngine.setup(
                    baseDir = File(filesDir, "box"),
                    workingDir = getExternalFilesDir(null) ?: File(filesDir, "work"),
                    tempDir = cacheDir
                )
                val coreConfig = if (usePsiphonProc) {
                    // موتور سایفون در پروسه‌ی جدا «:psiphon» بالا می‌آید و پورت SOCKS محلی‌اش
                    // را در فایل مشترک می‌نویسد؛ هسته‌ی sing-box ترافیک را به آن می‌فرستد
                    // (tun → sing-box → سایفون). هیچ کتابخانه‌ی بومی مشترکی در این پروسه لود نمی‌شود.
                    val ctx = applicationContext
                    PsiphonEngine.clearPort(ctx)
                    ContextCompat.startForegroundService(
                        ctx,
                        Intent(ctx, PsiphonService::class.java).setAction(PsiphonService.ACTION_START)
                    )
                    val port = awaitPsiphonPort(gen)
                    if (port == 0) throw Exception("اتصال سایفون برقرار نشد — دوباره امتحان کن")
                    if (engMode == "hybrid") ConfigBuilder.buildHybrid(port, config)
                    else ConfigBuilder.buildPsiphon(port, config)
                } else {
                    ConfigBuilder.build(config)
                }
                val eng = CoreEngine(coreConfig, this)
                eng.start() // داخل همین فراخوانی openTun() صدا زده می‌شود

                synchronized(stateLock) {
                    if (gen != generation) { // کاربر در این فاصله قطع کرده است
                        try { tun?.close() } catch (_: Exception) {}
                        eng.stop()
                        return@thread
                    }
                    engine = eng
                }

                lastRx = -1L; lastTx = -1L; totalRx = 0; totalTx = 0; statSource = 0
                verified = false
                startStatsTimer()
                startTrafficApi(gen)
                startFreeWatch(gen) // flush ترافیک + حذف سرور رایگان → قطع (مستقل از UI)

                // ---- اتصال اعلام می‌شود (مانند v2rayNG)؛ تست اینترنت در پس‌زمینه ----
                synchronized(stateLock) { if (gen != generation) return@thread }
                verified = false
                uiState = "connected"
                connectedAtMs = System.currentTimeMillis()
                tileListener?.invoke("connected")
                startForeground(NOTIF_ID, notif("متصل است", "در حال تست اینترنت..."))
                stateListener?.invoke("connected", "")
                if (autoNetTest) startNetTestLoop(gen)
                else startForeground(NOTIF_ID, notif("متصل است", "تست خودکار اینترنت خاموش است"))
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                if (gen == generation) stopVpn(error = e.message ?: "خطا در اتصال به هسته", includeLogs = true)
            }
        }
    }

    /**
     * حلقه‌ی تست اینترنت پس‌زمینه: هر ۱۵ ثانیه (یا فوری با requestNetTest) یک درخواست
     * واقعی HTTP از مسیر سرور می‌فرستد. اتصال هیچ‌وقت به‌خاطر شکست تست قطع نمی‌شود؛
     * فقط وضعیت «اینترنت تأیید شد / بدون اینترنت» به UI و نوتیفیکیشن داده می‌شود.
     */
    private fun startNetTestLoop(gen: Int) {
        testThread?.interrupt()
        val th = thread(name = "vex-nettest") {
            while (true) {
                synchronized(stateLock) { if (gen != generation) return@thread }
                val t0 = System.nanoTime()
                val err = testRealInternet(gen)
                val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                synchronized(stateLock) {
                    if (gen != generation) return@thread
                    if (err == null) {
                        verified = true
                        netState = 1; netMsg = ""; netMs = ms
                        netListener?.invoke(true, ms, "")
                        startForeground(NOTIF_ID, notif("متصل است", "اینترنت از مسیر سرور تأیید شد · ${ms}ms"))
                    } else {
                        verified = false
                        netState = 2
                        // دلیل واقعی را از لاگ هسته اضافه کن تا کاربر علت اصلی را ببیند
                        // (مثلاً TLS handshake failure / authentication failed / timeout)
                        val coreErr = lastCoreError()
                        val hint = hintFor((coreErr ?: "") + " " + err)
                        netMsg = "ترافیک از سرور رد نشد ($err)" +
                                (coreErr?.let { "\nهسته: $it" } ?: "") +
                                (hint?.let { "\nراهنما: $it" } ?: "")
                        netMs = -1
                        netListener?.invoke(false, -1, netMsg)
                        startForeground(NOTIF_ID, notif("متصل — بدون اینترنت", netMsg))
                    }
                }
                // منتظر تایمر بعدی یا درخواست تست فوری
                var waited = 0
                while (waited < 15_000) {
                    synchronized(stateLock) { if (gen != generation) return@thread }
                    if (testNow) { testNow = false; break }
                    try { Thread.sleep(250) } catch (_: InterruptedException) { return@thread }
                    waited += 250
                }
            }
        }
        testThread = th
    }

    /**
     * یک دور تست واقعی end-to-end: درخواست HTTP از مسیر inbound محلی → هسته → سرور → اینترنت.
     * خروجی null = موفق؛ رشته = پیام خطای قابل نمایش به کاربر.
     */
    private fun testRealInternet(gen: Int): String? {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.LOCAL_TEST_PORT))
        val urls = arrayOf(
            "https://www.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
            "http://www.msftconnecttest.com/connecttest.txt"
        )
        var lastMsg = ""
        for (u in urls) {
            synchronized(stateLock) { if (gen != generation) return null }
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "VEX-Client/1.3 (connection-test)")
                }
                val code = conn.responseCode
                try { conn.inputStream?.close() } catch (_: Exception) {}
                try { conn.errorStream?.close() } catch (_: Exception) {}
                if (code in 200..399) return null // مسیر واقعاً کار می‌کند
                lastMsg = "کد پاسخ: $code"
            } catch (e: Exception) {
                lastMsg = e.message ?: e.javaClass.simpleName
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        Log.e(TAG, "real internet test failed: $lastMsg")
        return "ترافیک از سرور رد نشد ($lastMsg) — کانفیگ یا سرور را بررسی کن"
    }

    /**
     * تبدیل خطای واقعی هسته به راهنمای قابل‌فهم فارسی برای رفع سریع مشکل «داده رد و بدل نشد»
     */
    private fun hintFor(err: String): String? = when {
        err.contains("CERTIFICATE_VERIFY_FAILED", true) || err.contains("x509", true) ||
                err.contains("certificate", true) ->
            "تأیید گواهی TLS شکست خورد — «رد کردن تأیید گواهی TLS» را در تنظیمات روشن کن یا SNI درست را در لینک بگذار"
        err.contains("handshake failure", true) || err.contains("handshake timeout", true) ||
                err.contains("tls", true) && err.contains("error", true) ->
            "TLS handshake رد شد — SNI یا فینگرپرینت (fp) کانفیگ را با نرم‌افزارهای دیگر مقایسه کن"
        err.contains("authentication", true) || err.contains("invalid password", true) ||
                err.contains("unauthorized", true) ->
            "رمز/شناسه‌ی کانفیگ اشتباه است — کانفیگ را دوباره از اپراتور بگیر"
        err.contains("connection reset", true) || err.contains("EOF", true) ||
                err.contains("broken pipe", true) ->
            "اتصال در میانه‌ی راه قطع شد (احتمالاً DPI) — اگر ادامه داشت، سرور یا پورت دیگری را امتحان کن"
        err.contains("timeout", true) || err.contains("timed out", true) ->
            "سرور پاسخ نداد — اینترنت گوشی یا پورت سرور را بررسی کن"
        err.contains("no route", true) || err.contains("network is unreachable", true) ||
                err.contains("unreachable", true) ->
            "شبکه در دسترس نیست — اتصال اینترنت گوشی را بررسی کن"
        else -> null
    }

    /** کاربر برنامه را از Recents کشید → اعلان دوستانه در شید می‌ماند */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            Notifier.showAppClosed(applicationContext, uiState == "connected" || uiState == "connecting")
        } catch (_: Throwable) {}
    }

    /**
     * انتظار برای پورت SOCKS سایفون (فایل مشترک بین پروسه‌ها) — حداکثر ۹۰ ثانیه.
     * اگر کاربر در این فاصله قطع کند (generation عوض شود)، ۰ برمی‌گردد.
     */
    private fun awaitPsiphonPort(gen: Int): Int {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            synchronized(stateLock) { if (gen != generation) return 0 }
            val p = PsiphonEngine.readPort(applicationContext)
            if (p > 0) return p
            try { Thread.sleep(500) } catch (_: InterruptedException) { return 0 }
        }
        Log.w(TAG, "psiphon port timeout")
        return 0
    }

    private fun stopVpn(error: String? = null, includeLogs: Boolean = false) {
        synchronized(stateLock) { generation++ }
        uiState = "stopped"
        connectedAtMs = 0
        tileListener?.invoke("stopped")
        // آمار لحظه‌ای و مجموع جلسه صفر می‌شود — طبق درخواست: با خاموش‌کردن پاور، همه‌چیز صفر
        snapDn = 0; snapUp = 0; snapTDn = 0; snapTUp = 0
        lastRx = -1L; lastTx = -1L; totalRx = 0L; totalTx = 0L
        timer?.cancel(); timer = null
        testThread?.interrupt(); testThread = null
        trafficThread?.interrupt(); trafficThread = null
        freeWatchThread?.interrupt(); freeWatchThread = null
        // آخرین دلتای جلسه به بک‌اند ربات می‌رود — حتی اگر UI اصلاً باز نبوده باشد
        try { TrafficStore.init(applicationContext) } catch (_: Throwable) {}
        try { TrafficStore.flushAsync(applicationContext) } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        // اگر این جلسه با موتور سایفون بود، پروسه‌ی سایفون هم خاموش می‌شود
        if (lastEnginePsiphon) {
            lastEnginePsiphon = false
            try {
                applicationContext.startService(
                    Intent(applicationContext, PsiphonService::class.java).setAction(PsiphonService.ACTION_STOP)
                )
            } catch (_: Throwable) {}
            PsiphonEngine.clearPort(applicationContext)
        }
        tunIfaceName = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (error != null) {
            // پیام خطا را کوتاه نگه می‌داریم؛ لاگ کامل از طریق VexNative.getLogs() در UI در دسترس است
            val coreErr = lastCoreError()
            val full = if (coreErr != null && !error.contains(coreErr)) "$error\nهسته: $coreErr" else error
            val msg = if (includeLogs) "$full\n[برای مشاهده‌ی لاگ کامل، «جزئیات خطا» را باز کن]" else full
            stateListener?.invoke("error", msg)
        }
        else stateListener?.invoke("stopped", "")
    }

    // ------------------------------------------------------------------ پایش اتصال

    private var freeWatchThread: Thread? = null

    /**
     * پایش دوره‌ای حین اتصال (هر ۶۰ ثانیه) — مستقل از UI کار می‌کند، پس حتی وقتی
     * اپ بسته یا از Recents حذف شده است هم فعال است:
     *  ۱) دلتاهای مصرف به بک‌اند ربات گزارش می‌شود (ریشه‌ی «صفر کیلوبایت» در ربات)؛
     *  ۲) اگر سرور رایگانی که به آن وصلیم از پنل ربات حذف شده باشد → اتصال فوراً قطع می‌شود.
     */
    private fun startFreeWatch(gen: Int) {
        freeWatchThread?.interrupt()
        freeWatchThread = thread(name = "vex-freewatch") {
            while (true) {
                // ۶۰ ثانیه انتظار — در قطعه‌های ۱ ثانیه‌ای تا قطع فوری در stopVpn
                for (i in 0 until 60) {
                    synchronized(stateLock) { if (gen != generation) return@thread }
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
                }
                synchronized(stateLock) { if (gen != generation) return@thread }
                // ۱) گزارش دوره‌ای مصرف (واترمارک — فقط تازه‌ها می‌روند)
                try { TrafficStore.flushAsync(applicationContext) } catch (_: Throwable) {}
                // ۲) اعلان‌های همگانی مدیر — حتی وقتی رابط برنامه بسته است،
                //    تا اعلان ربات مثل پیام‌رسان‌ها بالای صفحه بیاید
                try { Notifier.fetchAndShow(applicationContext) } catch (_: Throwable) {}
                // ۳) اگر مدیر برنامه را بسته باشد → تونل هم فوراً قطع می‌شود
                try {
                    if (TelegramGate.gateInfo().gate == TelegramGate.CLOSED) {
                        stopVpn()
                        break
                    }
                } catch (_: Throwable) {}
                // ۳.۵) اشتراک حذف/منقضی شده؟ هر ۶۰ ثانیه چک می‌شود — کیک از ربات
                //      یعنی همه‌ی دستگاه‌های آن کد حداکثر تا یک دقیقه بعد وسط اتصال قطع می‌شوند
                try {
                    val sc = SubStore.code(applicationContext)
                    if (SubStore.valid(sc)) {
                        val sub = TelegramGate.subStatus(sc, TelegramGate.deviceId(applicationContext))
                        if (sub.kicked || sub.expired || sub.notFound) {
                            SubStore.clear(applicationContext)
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    android.widget.Toast.makeText(
                                        applicationContext,
                                        if (sub.kicked) "نشست شما حذف شد — اتصال قطع شد"
                                        else "کد اشتراک شما منقضی شده است — اتصال قطع شد",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } catch (_: Throwable) {}
                            }
                            stopVpn()
                            break
                        }
                    }
                } catch (_: Throwable) {}
                // ۴) حذف سرور رایگان → قطع خودکار
                try {
                    val raw = lastPayload?.let {
                        try { org.json.JSONObject(it).optString("raw", "") } catch (_: Throwable) { "" }
                    } ?: ""
                    if (raw.isNotEmpty()) {
                        val st = ServersStore(applicationContext)
                        val sel = st.selected()
                        if (sel != null && st.isFree(sel)) {
                            val dtos = TelegramGate.fetchFreeServers() // null = شبکه نبود → کاری نکن
                            if (dtos != null && dtos.none { it.raw == raw }) {
                                synchronized(stateLock) { if (gen != generation) return@thread }
                                try {
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            android.widget.Toast.makeText(
                                                applicationContext,
                                                "این سرور رایگان حذف شد — اتصال قطع شد",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } catch (_: Throwable) {}
                                    }
                                } catch (_: Throwable) {}
                                stopVpn()
                                break
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    // ------------------------------------------------------------------ آمار

    private var trafficThread: Thread? = null

    /**
     * آمار واقعی ترافیک پروکسی از Clash API هسته (127.0.0.1:19090/traffic) —
     * همان روش v2rayNG: هر خط JSON یک ثانیه سرعت است {up,down}. این منبع، ترافیک واقعی
     * عبوری از هسته را نشان می‌دهد حتی وقتی شمارنده‌های tun صفر باشند (مثل ترافیک تست).
     * اگر Clash API در دسترس نباشد، شمارنده‌های tun/TrafficStats به‌عنوان جایگزین کار می‌کنند.
     */
    private fun startTrafficApi(gen: Int) {
        trafficThread?.interrupt()
        val th = thread(name = "vex-traffic") {
            while (true) {
                synchronized(stateLock) { if (gen != generation) return@thread }
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("http://127.0.0.1:${ConfigBuilder.CLASH_API_PORT}/traffic").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 0 // استریم بی‌پایان؛ هر ثانیه یک خط
                        useCaches = false
                    }
                    val code = conn.responseCode
                    if (code != 200) throw Exception("clash api: $code")
                    conn.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            synchronized(stateLock) { if (gen != generation) return@thread }
                            val j = org.json.JSONObject(line)
                            val up = j.optLong("up", 0)
                            val down = j.optLong("down", 0)
                            statSource = 3
                            totalRx += down
                            totalTx += up
                            snapDn = down; snapUp = up
                            snapTDn = totalRx; snapTUp = totalTx
                            statsListener?.invoke(down, up, totalRx, totalTx)
                            if (verified) {
                                val body = if (showNotifSpeed)
                                    "↓ ${fmt(down)}/s    ↑ ${fmt(up)}/s\nکل: ↓ ${fmt(totalRx)}   ↑ ${fmt(totalTx)}"
                                else
                                    "اتصال برقرار و پایدار است\nکل: ↓ ${fmt(totalRx)}   ↑ ${fmt(totalTx)}"
                                try { nm.notify(NOTIF_ID, notif("متصل است", body)) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Clash API نیست یا استریم قطع شد — بعد از یک ثانیه دوباره تلاش کن
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
                } finally {
                    try { conn?.disconnect() } catch (_: Throwable) {}
                }
                synchronized(stateLock) { if (gen != generation) return@thread }
            }
        }
        trafficThread = th
    }

    private fun startStatsTimer() {
        timer = Timer().also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    // اگر آمار از Clash API می‌آید، این تایمر نقشی ندارد
                    if (statSource == 3) return
                    val (dn, up) = readCounters() ?: return
                    if (lastRx < 0) { lastRx = dn; lastTx = up; return }
                    val dRx = (dn - lastRx).coerceAtLeast(0)
                    val dTx = (up - lastTx).coerceAtLeast(0)
                    lastRx = dn; lastTx = up
                    totalRx += dRx; totalTx += dTx
                    snapDn = dRx; snapUp = dTx; snapTDn = totalRx; snapTUp = totalTx
                    statsListener?.invoke(dRx, dTx, totalRx, totalTx)
                    // نوتیفیکیشن فقط بعد از تأیید واقعی مسیر به «متصل است» تغییر می‌کند
                    if (verified) {
                        val body = if (showNotifSpeed)
                            "↓ ${fmt(dRx)}/s    ↑ ${fmt(dTx)}/s\nکل: ↓ ${fmt(totalRx)}   ↑ ${fmt(totalTx)}"
                        else
                            "اتصال برقرار و پایدار است\nکل: ↓ ${fmt(totalRx)}   ↑ ${fmt(totalTx)}"
                        try { nm.notify(NOTIF_ID, notif("متصل است", body)) } catch (_: Exception) {}
                    }
                }
            }, 1000, 1000)
        }
    }

    /** خواندن بایت‌های واقعی اینترفیس tun از /sys؛ در نبود آن آمار UID خود برنامه (ترافیک واقعی تونل) */
    private fun readCounters(): Pair<Long, Long>? {
        val name = tunIfaceName
        if (name != null) {
            try {
                val rx = File("/sys/class/net/$name/statistics/rx_bytes").readText().trim().toLong()
                val tx = File("/sys/class/net/$name/statistics/tx_bytes").readText().trim().toLong()
                if (statSource != 1) { statSource = 1; lastRx = -1L }
                return rx to tx
            } catch (_: Exception) { /* اینترفیس هنوز آماده نیست یا حذف شده */ }
        }
        // جایگزین: فقط ترافیک UID خودِ برنامه (سوکت‌های محافظت‌شده‌ی هسته) —
        // آمار «کل دستگاه» را عمداً استفاده نمی‌کنیم چون ترافیک بقیه‌ی اپ‌ها را هم می‌شمارد
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        if (rx <= 0 && tx <= 0) return null
        if (statSource != 2) { statSource = 2; lastRx = -1L }
        return rx to tx
    }

    // ------------------------------------------------- پیاده‌سازی PlatformInterface

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        val before = ifaceNames()
        val builder = Builder()
            .setSession("VEX CLIENT")
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        fun drain(it: RoutePrefixIterator): MutableList<Pair<String, Int>> {
            val list = mutableListOf<Pair<String, Int>>()
            while (it.hasNext()) { val p = it.next(); list.add(p.address() to p.prefix()) }
            return list
        }

        drain(options.inet4Address).forEach { (a, p) -> builder.addAddress(a, p) }
        drain(options.inet6Address).forEach { (a, p) -> builder.addAddress(a, p) }

        if (options.autoRoute) {
            val dnsAddr = try { options.dnsServerAddress?.value } catch (_: Exception) { null }
            builder.addDnsServer(if (dnsAddr.isNullOrBlank()) "1.1.1.1" else dnsAddr)
            val r4 = drain(options.inet4RouteRange)
            val r6 = drain(options.inet6RouteRange)
            r4.forEach { (a, p) -> builder.addRoute(a, p) }
            r6.forEach { (a, p) -> builder.addRoute(a, p) }
            if (r4.isEmpty()) builder.addRoute("0.0.0.0", 0)
            if (r6.isEmpty()) builder.addRoute("::", 0)
        }

        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        // ---- per-app proxy (مثل v2rayNG) ----
        // حالت 1 = تونل فقط برای برنامه‌های انتخابی (addAllowedApplication)
        // حالت 2 = تونل برای همه به‌جز برنامه‌های انتخابی (addDisallowedApplication)
        if (perAppMode == 1 || perAppMode == 2) {
            var ok = 0
            for (pkg in perAppPackages) {
                try {
                    if (pkg.isBlank() || pkg == packageName) continue
                    if (perAppMode == 1) builder.addAllowedApplication(pkg)
                    else builder.addDisallowedApplication(pkg)
                    ok++
                } catch (_: Throwable) {
                    // برنامه نصب نیست یا پکیج نامعتبر است — نادیده گرفته می‌شود
                }
            }
            if (ok == 0 && perAppMode == 1) {
                // اگر هیچ برنامه‌ای قابل افزودن نبود، تونل بی‌فایده است — خطا بده تا کاربر بداند
                error("هیچ‌کدام از برنامه‌های انتخابی برای تونل قابل اعمال نبودند")
            }
        }

        val pfd = builder.establish() ?: error("ساخت تونل VPN ناموفق بود (دسترسی رد شد)")
        tun = pfd
        tunIfaceName = findNewIface(before)
        return pfd.fd
    }

    override fun writeLog(message: String?) {
        val line = message ?: return
        Log.i("VexCore", line)
        pushCoreLog(line)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int
    ): Int = -1 // پشتیبانی نمی‌شود؛ -1 یعنی «نامشخص» — اتصال را نباید قطع کنیم

    override fun packageNameByUid(uid: Int): String {
        val name = packageManager.getPackagesForUid(uid)?.firstOrNull()
        return name ?: error("package not found for uid $uid")
    }

    override fun uidByPackageName(packageName: String?): Int {
        return packageManager.getPackageUid(packageName ?: "", 0)
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || listener == null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { pushDefaultInterface(listener, network) }
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                pushDefaultInterface(listener, network)
            }
        }
        netCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
            cm.activeNetwork?.let { pushDefaultInterface(listener, it) }
        } catch (_: Exception) {}
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private fun pushDefaultInterface(listener: InterfaceUpdateListener?, network: Network) {
        if (listener == null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(network) ?: return
            val nif = NetworkInterface.getByName(lp.interfaceName) ?: return
            listener.updateDefaultInterface(lp.interfaceName, nif.index, false, false)
        } catch (_: Exception) {}
    }

    override fun getInterfaces(): BoxNetworkInterfaceIterator {
        val list = mutableListOf<BoxNetworkInterface>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (nif.name == tunIfaceName) continue
                val b = BoxNetworkInterface()
                b.name = nif.name
                b.index = nif.index
                try { b.mtu = nif.mtu } catch (_: Exception) {}
                b.addresses = StringArrayIterator(
                    nif.interfaceAddresses.mapNotNull { ia ->
                        val host = ia.address?.hostAddress ?: return@mapNotNull null
                        val h = if (ia.address is Inet6Address)
                            (ia.address as Inet6Address).hostAddress?.substringBefore('%') ?: host else host
                        "$h/${ia.networkPrefixLength}"
                    }
                )
                b.flags = if (nif.isLoopback) 0x9 else 0x41 // LOOPBACK | UP یا UP | RUNNING
                list.add(b)
            }
        } catch (_: Exception) {}
        return BoxInterfaceIterator(list)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {}

    override fun sendNotification(notification: BoxNotification?) {
        // نوتیفیکیشن اختصاصی خودمان را داریم
    }

    override fun onRevoke() {
        stopVpn()
    }

    // ------------------------------------------------------------------ ابزار

    private fun ifaceNames(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().asSequence().map { it.name }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun findNewIface(before: Set<String>): String? {
        repeat(10) {
            try {
                val now = NetworkInterface.getNetworkInterfaces().asSequence().toList()
                val fresh = now.firstOrNull { it.name !in before && !it.isLoopback }
                if (fresh != null) return fresh.name
                val tunLike = now.firstOrNull { it.name.startsWith("tun") }
                if (tunLike != null) return tunLike.name
            } catch (_: Exception) {}
            Thread.sleep(150)
        }
        return null
    }

    private class StringArrayIterator(private val items: List<String>) : StringIterator {
        private val it = items.iterator()
        override fun hasNext(): Boolean = it.hasNext()
        override fun len(): Int = items.size
        override fun next(): String = it.next()
    }

    private class BoxInterfaceIterator(private val items: List<BoxNetworkInterface>) : BoxNetworkInterfaceIterator {
        private val it = items.iterator()
        override fun hasNext(): Boolean = it.hasNext()
        override fun next(): BoxNetworkInterface = it.next()
    }

    // ------------------------------------------------------------------ نوتیفیکیشن

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
            .setSmallIcon(R.drawable.ic_stat_vex)
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
