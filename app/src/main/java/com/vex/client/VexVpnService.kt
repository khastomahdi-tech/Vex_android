package com.vex.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        /** نوسازی بی‌صدای هسته هنگام هنداور WiFi↔دیتای موبایل (تنظیم کاربر — پیش‌فرض روشن) */
        @Volatile var handoverReload: Boolean = true

        // ---- per-app مثل v2rayNG: 0 = همه، 1 = فقط انتخاب‌شده‌ها، 2 = همه به‌جز انتخاب‌شده‌ها ----
        @Volatile var perAppMode: Int = 0
        @Volatile var perAppPackages: Set<String> = emptySet()

        // ---- آخرین بار اتصال (برای اتصال مجدد خودکار پس از کشته‌شدن سرویس) ----
        @Volatile var lastPayload: String? = null
        /** جلسه‌ی فعلی/آخر با موتور لنترن اجرا شده است؟ (برای خاموش‌کردن هسته‌ی لنترن هنگام قطع) */
        @Volatile var lastEngineLantern = false

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

        // ---- ثابت‌های پایداری (الگوی v2rayNG/PattNG/ArasClient) ----
        /** تأخیر ضدلرزش هنداور — همان HANDOVER_DEBOUNCE_MS در NetworkMonitor کلاینت‌های NG */
        private const val HANDOVER_DEBOUNCE_MS = 1000L
        /** اگر از دور تست قبلی این حجم ترافیک واقعی عبور کرده باشد، تونل قطعاً زنده است */
        private const val TRAFFIC_ALIVE_BYTES = 300_000L

        // ---- V5.9 — سیاست بوت لنترن (ضد «process is bad») ----
        // خطای «Unable to launch app ... process is bad» یعنی پروسه‌ی :lantern چند بار پشت‌سرهم
        // کرش کرده و اندروید اجرای دوباره‌اش را ممنوع کرده. ریشه: کرش‌های بومی Go (log.Fatalf/
        // panic در flashlight) که با try/catch قابل‌گرفتن نیستند + ساخت پروسه‌های تازه‌ی پشت‌سرهم.
        // پادزهر: حداکثر ۲ پروسه در هر جلسه + بوت مجدد درون-پروسه‌ای + فاصله‌ی امن بین spawnها.
        /** حداکثر پروسه‌ی تازه‌ی :lantern در هر جلسه‌ی اتصال */
        private const val LANTERN_MAX_SPAWNS = 2
        /** حداکثر بوت مجدد درون-پروسه‌ای flashlight (بدون ساخت پروسه‌ی جدید — ریسک صفر) */
        private const val LANTERN_MAX_REBOOTS = 2
        /** انتظار برای پورت HTTP در هر بوت لنترن */
        private const val LANTERN_BOOT_WAIT_MS = 55_000L
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: CoreEngine? = null
    private var timer: Timer? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private val stateLock = Any()
    private var generation = 0

    // ---- آزمون اینترنت پس‌زمینه (مانند v2rayNG: اتصال را قطع نمی‌کنیم) ----
    private var testThread: Thread? = null

    // ---- فال‌بک خودکار موتور اتصال (رفع «وصل شد ولی دیتا رد و بدل نشد» روی برخی اپراتورها) ----
    private var failStreak = 0
    private var fallbackDone = false

    /** پنجره‌ی تثبیت: تا این لحظه، شکست تست‌ها شمرده نمی‌شود (الگوی v2rayNG — مزاحم تونل نشو) */
    @Volatile private var stabilizeUntilMs = 0L
    /** دور تست فعلی — برای چرخش ترتیب URLهای تست تا یک هاست همیشه بازنده نشود */
    private var testRound = 0

    // ---- WakeLock جزئی حین اتصال (الگوی SagerNet/NekoBox) — مانع خواب CPU و کندشدن تونل ----
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    /** آخرین متن اعلان — اعلان فقط وقتی عوض شود، نه هر ثانیه (الگوی v2rayNG) */
    private val lastNotifKey = AtomicReference<String>("")

    // ---- نردبان خود-شفای اتصال (رفع «هی وصل می‌شود، هی قطع می‌شود») ----
    /** مرحله‌ی نوسازی: ۱ و ۳ = ری‌استارت بی‌صدای هسته (جریان تازه) · ۲ = تغییر موتور · ۴ = فقط پایش */
    private var healStage = 0
    /** جمع بایت‌ها در آخرین دور تست — لایوِبودن تونل از روی ترافیک واقعی کاربر تشخیص داده می‌شود */
    private var lastProbeBytes = 0L
    /** شمارش دورهای پشت‌سرهمِ «سرور در لیست نبود» — قطع فقط بعد از دو تأیید متوالی (رفع قطع کاذب) */
    @Volatile private var srvMissingStreak = 0

    // ---- پایش شبکه‌ی زیرین + نوسازی هنگام هنداور (الگوی NetworkMonitor/reloadCore در v2rayNG) ----
    private var handoverCb: ConnectivityManager.NetworkCallback? = null
    private var upstreamNet: Network? = null
    private val reloadBusy = AtomicBoolean(false)
    @Volatile private var lastReloadAt = 0L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var handoverRunnable: Runnable? = null

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

    /**
     * quiet = نوسازی بی‌صدای هسته (الگوی reloadCore در v2rayNG): تونل و اعلان و وضعیت «متصل»
     * دست‌نخورده می‌ماند؛ فقط هسته با کانفیگ تازه و جریان‌های TCP نو بالا می‌آید.
     * isRetry = تلاش دومِ خودکار در حالت quiet (فقط یک‌بار — تا حلقه‌ی بی‌پایان ساخته نشود).
     */
    private fun startVpn(config: String, quiet: Boolean = false, isRetry: Boolean = false) {
        // باید سریع فورگراند شویم، وگرنه اندروید سرویس را می‌کشد
        // موتور اتصال: «lantern» = فقط لنترن (هسته‌ی رسمی) · «hybrid» = ترکیبی (سرور Vex از داخل لنترن)
        val engMode = try { JSONObject(config).optString("engine") } catch (_: Throwable) { "" }
        // شروع تازه (از سمت کاربر/سیستم — نه فال‌بک داخلی): وضعیت فال‌بک از نو
        if (uiState == "stopped") {
            failStreak = 0
            fallbackDone = false
            healStage = 0
            lastProbeBytes = 0
        }
        val useLanternProc = engMode == "lantern" || engMode == "hybrid"
        lastEngineLantern = useLanternProc
        if (!quiet) {
            startForeground(NOTIF_ID, notif(
                when (engMode) {
                    "hybrid" -> "در حال اتصال... (ترکیبی Vex + لنترن)"
                    "lantern" -> "در حال اتصال... (لنترن)"
                    else -> "در حال اتصال..."
                },
                if (useLanternProc) "راه‌اندازی موتور لنترن..."
                else if (engMode == "auto") "انتخاب خودکار بهترین سرور..."
                else "راه‌اندازی هسته..."))
            uiState = "connecting"
            tileListener?.invoke("connecting")
            netState = 0; netMsg = ""; netMs = -1
        }
        // پنجره‌ی تثبیت: در این بازه شکست تست‌ها شمرده نمی‌شود — شروع تونل روی شبکه‌ی
        // موبایل پرلرزش است. برای موتور لنترن بلندتر (۱۲۰ ثانیه): بوتِ flashlight و
        // اولین handshake با شبکه‌ی ابری لنترن (فرانتینگ) روی اپراتورهای ایران کُند است
        // و نباید باعث «نوسازی/قطع کاذب» در همان دقیقه‌های اول شود
        stabilizeUntilMs = System.currentTimeMillis() + (if (useLanternProc) 120_000L else 60_000L)
        acquireWakelock()
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
                val coreConfig = if (useLanternProc) {
                    // موتور لنترن در پروسه‌ی جدا «:lantern» بالا می‌آید و پورت پروکسی HTTP محلی‌اش
                    // را در فایل مشترک می‌نویسد؛ هسته‌ی sing-box ترافیک را به آن می‌فرستد
                    // (tun → sing-box → لنترن).
                    //
                    // V5.9 — قانون کاربر:
                    //  • لنترن فقط لنترن — هر بوت = کانفیگ تازه = انتخاب تازه‌ی بهترین سرور
                    //    از شبکه‌ی ابری لنترن؛ هیچ سرور خودمان در حالت عادی؛
                    //  • سرور لنترن قطع شد → بوت مجدد درون-پروسه‌ای = سرور جایگزین، بی‌صدا؛
                    //  • حداکثر ۲ پروسه + ۲ بوت درون-پروسه‌ای — هرگز «process is bad» نمی‌شویم؛
                    //  • فقط وقتی لنترن «کلاً» نشد → فال‌بک نهایی و بی‌صدا به بهترین سرور خودمان.
                    val ctx = applicationContext
                    val port = bootLantern(ctx, gen)
                    if (port > 0) {
                        lastEngineLantern = true // اطمینان: قطعِ بعدی، پروسه‌ی لنترن را هم جمع می‌کند
                        if (engMode == "hybrid") ConfigBuilder.buildHybrid(port, config)
                        else ConfigBuilder.buildLanternProxy(port, config)
                    } else {
                        synchronized(stateLock) { if (gen != generation) return@thread }
                        // لنترن کلاً نشد — فال‌بک نهایی به بهترین سرور خودمان (خواسته‌ی صریح کاربر)
                        lastEngineLantern = false
                        fallbackDone = true
                        pushCoreLog("لنترن: بوت موتور لنترن بعد از نردبان کامل ناموفق بود — فال‌بک نهایی به بهترین سرور Vex")
                        pushCoreLog(LanternEngine.diagReport(applicationContext))
                        stopLanternQuiet()
                        if (!quiet) startForeground(NOTIF_ID, notif(
                            "در حال اتصال... (بهترین سرور Vex)",
                            "لنترن برقرار نشد — سوییچ خودکار به بهترین سرور"))
                        if (engMode == "hybrid") ConfigBuilder.build(config) // همان سرور انتخابی، بدون لنترن
                        else {
                            // payload لنترن استخر سرور ندارد — استخر از store ساخته می‌شود
                            val fb = bestServerPayload(config)
                                ?: throw Exception("لنترن برقرار نشد و هیچ سروری برای فال‌بک نهایی پیدا نشد")
                            ConfigBuilder.buildAutoPool(fb) // بهترین سرور (urltest)
                        }
                    }
                } else if (engMode == "auto") {
                    // حالت خودکار: استخر سرورها داخل همین هسته بالا می‌آید (پروسه‌ی اضافی ندارد)
                    // و گروه urltest هسته، بهترین سرور را خودکار انتخاب/سوییچ می‌کند
                    ConfigBuilder.buildAutoPool(config)
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

                lastRx = -1L; lastTx = -1L; statSource = 0
                // در نوسازی بی‌صدا آمار جلسه حفظ می‌شود (قطعی برای کاربر دیده نمی‌شود)
                if (!quiet) { totalRx = 0; totalTx = 0 }
                verified = false
                startStatsTimer()
                startTrafficApi(gen)
                startFreeWatch(gen) // flush ترافیک + حذف سرور رایگان → قطع (مستقل از UI)

                // ---- اتصال اعلام می‌شود (مانند v2rayNG)؛ تست اینترنت در پس‌زمینه ----
                synchronized(stateLock) { if (gen != generation) return@thread }
                verified = false
                if (!quiet) {
                    uiState = "connected"
                    connectedAtMs = System.currentTimeMillis()
                    tileListener?.invoke("connected")
                }
                startForeground(NOTIF_ID, notif("متصل است", "در حال تست اینترنت..."))
                stateListener?.invoke("connected", "")
                if (autoNetTest) startNetTestLoop(gen)
                else startForeground(NOTIF_ID, notif("متصل است", "تست خودکار اینترنت خاموش است"))
                // پایش شبکه‌ی زیرین: هنداور WiFi↔موبایل → نوسازی بی‌صدا (الگوی v2rayNG)
                startHandoverWatcher(gen)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                if (gen == generation) {
                    if (quiet && !isRetry) {
                        // یک تلاش دوباره برای نوسازی بی‌صدا — قطع نشدن کاربر به‌خاطر لرزش شروع
                        try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                        try { tun?.close() } catch (_: Exception) {}
                        tun = null
                        try { engine?.stop() } catch (_: Exception) {}
                        engine = null
                        startVpn(config, quiet = true, isRetry = true)
                    } else {
                        stopVpn(error = e.message ?: "خطا در اتصال به هسته", includeLogs = true)
                    }
                }
            }
        }
    }

    /**
     * حلقه‌ی پایش پس‌زمینه — الگوی ترکیبی v2rayNG/PattNG:
     *  ۱) لایوِبودن از روی ترافیک واقعی: اگر از دور قبل، ترافیک واقعی از تونل گذشته باشد،
     *     تونل قطعاً کار می‌کند و هیچ پروب HTTP زده نمی‌شود (کلاینت‌های NG اصلاً حلقه‌ی
     *     خودکار ندارند — تستِ کاذب نباید تونل سالم را بکشد و «قطع‌ووصل» بسازد).
     *  ۲) وقتی تونل بی‌ترافیک است، پروب واقعی HTTP زده می‌شود.
     *  ۳) روی شکست پشت‌سرهم، نردبان نوسازی: جریان تازه با همان موتور → لنترن: بوت تازه‌ی
     *     لنترن (سرور جایگزین لنترن) / بقیه: تغییر موتور → جریان تازه دوباره؛ بعد از آن
     *     فقط پایش (تا حلقه‌ی بی‌پایان ساخته نشود). موتور لنترن هرگز به سرورهای خودمان
     *     سوییچ نمی‌کند — همه‌ی مراحل نوسازی داخل خود لنترن می‌مانند (V5.8).
     */
    private fun startNetTestLoop(gen: Int) {
        testThread?.interrupt()
        val th = thread(name = "vex-nettest") {
            while (true) {
                synchronized(stateLock) { if (gen != generation) return@thread }

                // ---- ۱) تشخیص لایوِبودن از روی ترافیک واقعی ----
                val nowBytes: Long = try { totalRx + totalTx } catch (_: Throwable) { 0L }
                val aliveByTraffic = nowBytes - lastProbeBytes > TRAFFIC_ALIVE_BYTES
                lastProbeBytes = nowBytes
                if (aliveByTraffic) {
                    synchronized(stateLock) {
                        if (gen != generation) return@thread
                        verified = true
                        failStreak = 0
                        healStage = 0
                        fallbackDone = false
                        if (netState != 1) {
                            netState = 1; netMsg = ""; netMs = -1
                            netListener?.invoke(true, -1, "")
                            startForeground(NOTIF_ID, notif("متصل است", "اتصال برقرار و پایدار است"))
                        }
                    }
                    waitNextProbe(gen, 30_000L)
                    continue
                }

                // ---- ۲) تونل بی‌ترافیک: پروب واقعی HTTP از مسیر سرور ----
                val t0 = System.nanoTime()
                val err = testRealInternet(gen)
                val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                var failed = false
                synchronized(stateLock) {
                    if (gen != generation) return@thread
                    if (err == null) {
                        verified = true
                        failStreak = 0
                        healStage = 0
                        fallbackDone = false
                        netState = 1; netMsg = ""; netMs = ms
                        netListener?.invoke(true, ms, "")
                        startForeground(NOTIF_ID, notif("متصل است", "اینترنت از مسیر سرور تأیید شد · ${ms}ms"))
                    } else {
                        failed = true
                        verified = false
                        // در پنجره‌ی تثبیت شکست شمرده نمی‌شود (لرزش طبیعی اول اتصال)
                        if (System.currentTimeMillis() >= stabilizeUntilMs) failStreak++
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
                        // ---- ۳) نردبان نوسازی — مثل reloadCore کلاینت‌های NG ولی خودآگاه ----
                        // V5.8: در موتور لنترن هیچ مرحله‌ای موتور را عوض نمی‌کند —
                        // لنترن با بوت تازه، «سرور جایگزین لنترن» برمی‌دارد (بی‌سروصدا)
                        if (System.currentTimeMillis() >= stabilizeUntilMs && failStreak >= 4) {
                            failStreak = 0
                            if (healStage < 5) healStage++
                            when (healStage) {
                                // جریان تازه با همان موتور (سوکت‌های مرده دور ریخته می‌شود)
                                // برای لنترن: هسته‌ی sing-box نو، پروسه/نمونه‌ی لنترن دست‌نخورده
                                1, 2 -> lastPayload?.let { performQuietReload(it, "heal$healStage") }
                                // لنترن → بوت مجدد درون-پروسه‌ای (سرور جایگزین لنترن، بی‌صدا)
                                // بقیه → فال‌بک موتور
                                3 -> healSwitchEngine()
                                // مرحله‌ی نهایی: لنترن چند بار سرور جایگزین گرفت و هنوز نشد →
                                // فال‌بک نهایی به بهترین سرور خودمان (خواسته‌ی صریح کاربر، بی‌صدا)
                                4 -> if (engineOf(lastPayload) == "lantern") fallbackToBestServerQuiet()
                                // مرحله‌ی ۵ به بعد: خودکار نمی‌چرخیم؛ فقط پایش —
                                // با برگشتن ترافیک واقعی یا اتصال مجدد دستی، لدر ریست می‌شود
                                else -> {}
                            }
                            stabilizeUntilMs = System.currentTimeMillis() + 120_000L
                        }
                    }
                }
                // اگر آخرین تست رد شده، زودتر دوباره چک کن (۲۰ ثانیه) — ریکاوری سریع‌تر دیده می‌شود
                waitNextProbe(gen, if (failed) 20_000L else 45_000L + kotlin.random.Random.nextLong(-5_000L, 5_000L))
            }
        }
        testThread = th
    }

    /** انتظار تا دور بعدی پروب — در قطعه‌های ۲۵۰ms تا قطع فوری در stopVpn یا تست فوری کاربر */
    private fun waitNextProbe(gen: Int, intervalMs: Long) {
        var waited = 0L
        while (waited < intervalMs) {
            synchronized(stateLock) { if (gen != generation) return }
            if (testNow) { testNow = false; return }
            try { Thread.sleep(250) } catch (_: InterruptedException) { return }
            waited += 250
        }
    }

    /**
     * یک دور تست واقعی end-to-end: درخواست HTTP از مسیر inbound محلی → هسته → سرور → اینترنت.
     * خروجی null = موفق؛ رشته = پیام خطای قابل نمایش به کاربر.
     */
    private fun testRealInternet(gen: Int): String? {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.LOCAL_TEST_PORT))
        // ترتیب URL در هر دور می‌چرخد تا یک هاست همیشه اول بیاید و شانس تست کج نشود
        val urls = arrayOf(
            "http://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "http://www.msftconnecttest.com/connecttest.txt"
        )
        var lastMsg = ""
        val startIdx = if (testRound > 0) (testRound % urls.size) else 0
        testRound++
        for (k in 0 until urls.size) {
            val u = urls[(startIdx + k) % urls.size]
            synchronized(stateLock) { if (gen != generation) return null }
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection(proxy) as HttpURLConnection).apply {
                    // ۱۰ ثانیه: روی شبکه‌ی موبایل/هنداور سلولی، ۶ ثانیه لرزش طبیعی را «خطا» قلمداد می‌کند
                    connectTimeout = 10_000
                    readTimeout = 10_000
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
     * مرحله‌ی ۳ نردبان نوسازی (V5.9):
     *  • موتور لنترن → بوت مجدد درون-پروسه‌ای flashlight (سرور جایگزین از شبکه‌ی ابری لنترن)
     *    — بدون ساخت پروسه‌ی جدید، کاملاً بی‌سروصدا.
     *  • بقیه‌ی موتورها → فال‌بک موتور (مستقیم ↔ ترکیبی) مثل قبل.
     */
    private fun healSwitchEngine() {
        if (engineOf(lastPayload) == "lantern") restartLanternQuiet() else maybeFallbackEngine()
    }

    /** خواندن موتور از payload — "lantern" | "hybrid" | "auto" | "" (مستقیم) */
    private fun engineOf(payload: String?): String = try {
        JSONObject(payload ?: "{}").optString("engine")
    } catch (_: Throwable) { "" }

    /**
     * بازسازی بی‌صدای موتور لنترن — V5.9: بوت مجدد flashlight «درون همان پروسه».
     * وقتی مسیر فعلی لنترن از کار می‌افتد (سرور لنترن قطع شده)، نمونه‌ی فعلی متوقف و نمونه‌ای
     * نو با کانفیگ تازه در همان پروسه بوت می‌شود — flashlight از شبکه‌ی ابری لنترن «سرور
     * جایگزین» برمی‌دارد و به بهترین مسیر نو وصل می‌شود.
     *  • بدون ساخت پروسه‌ی جدید ⇒ ریسک «process is bad» = صفر (ریشه‌ی باگ V5.8)؛
     *  • وضعیت «متصل»، آمار جلسه و اعلان دست‌نخورده می‌ماند و هیچ پیامی به کاربر نشان داده
     *    نمی‌شود — کاربر فقط اینترنت بی‌وقفه می‌بیند (خواسته‌ی صریح کاربر).
     */
    private fun restartLanternQuiet() {
        val payload = lastPayload ?: return
        if (!reloadBusy.compareAndSet(false, true)) return
        Log.w(TAG, "lantern path dead — silent in-process flashlight reboot (replacement lantern server, no spawn, no user notice)")
        thread(name = "vex-lnt-heal") {
            try {
                val g = synchronized(stateLock) { generation }
                if (uiState != "connected") return@thread
                // بوت مجدد درون-پروسه‌ای: نمونه‌ی فعلی stop و نمونه‌ی نو با کانفیگ تازه boot می‌شود
                safeStartLanternService(applicationContext, LanternService.ACTION_RESTART)
                val port = awaitLanternBoot(applicationContext, g, LANTERN_BOOT_WAIT_MS)
                if (port <= 0) {
                    // بوت مجدد درون-پروسه‌ای هم نشد — نردبان نوسازی مرحله‌ی بعدی را تصمیم می‌گیرد
                    Log.w(TAG, "lantern in-process reboot failed — heal ladder will escalate")
                    return@thread
                }
                // هسته‌ی sing-box با پورت نو بالا می‌آید — quiet یعنی کاربر هیچ قطعی نمی‌بیند
                stopCoreQuiet(keepLantern = true)
                startVpn(payload, quiet = true)
                failStreak = 0
                stabilizeUntilMs = System.currentTimeMillis() + 180_000L
            } finally {
                reloadBusy.set(false)
            }
        }
    }

    /**
     * payload «بهترین سرور» برای فال‌بک نهایی از جلسه‌ی لنترن —
     * استخر: سرور انتخابی کاربر (اگر واقعی باشد) + کل سرورهای رایگان؛ گروه urltest هسته
     * بهترین را خودکار انتخاب/سوییچ می‌کند. (payload لنترن هرگز استخر ندارد.)
     * خروجی null = هیچ سروری در دسترس نیست.
     */
    private fun bestServerPayload(base: String): String? {
        return try {
            val o = JSONObject(base)
            o.put("engine", "auto")
            val arr = org.json.JSONArray()
            val seen = HashSet<String>()
            val st = ServersStore(applicationContext)
            val selRaw = try { st.selected()?.raw?.trim().orEmpty() } catch (_: Throwable) { "" }
            if (selRaw.isNotEmpty() && !selRaw.startsWith("lantern://") && !selRaw.startsWith("psiphon://") && seen.add(selRaw)) arr.put(selRaw)
            try {
                for (s in st.freeServers) {
                    val r = try { s.raw.trim() } catch (_: Throwable) { "" }
                    if (r.isNotEmpty() && seen.add(r)) arr.put(r)
                }
            } catch (_: Throwable) {}
            if (arr.length() == 0) return null
            o.put("lanternServers", arr)
            o.toString()
        } catch (_: Throwable) { null }
    }

    /**
     * فال‌بک نهایی (V5.9 — خواسته‌ی صریح کاربر): فقط وقتی لنترن «کلاً» نتوانست کار کند —
     * بعد از نردبان بوت کامل (۲ پروسه + ۲ بوت درون-پروسه‌ای) و ۲ بوت مجدد جایگزین در
     * نردبان نوسازی — سوییچ به بهترین سرور خودمان (استخر خودکار urltest).
     * بی‌صدا: وضعیت «متصل» و آمار جلسه حفظ می‌شود، هیچ Toast/پیامی نشان داده نمی‌شود.
     * در هر جلسه فقط یک‌بار — تا حلقه‌ی بی‌پایان ساخته نشود.
     */
    private fun fallbackToBestServerQuiet() {
        if (fallbackDone) return
        val payload = lastPayload ?: return
        if (!reloadBusy.compareAndSet(false, true)) return
        fallbackDone = true
        Log.w(TAG, "lantern totally failed — final silent fallback to best own server (user policy)")
        thread(name = "vex-final-fb") {
            try {
                val np = bestServerPayload(payload) ?: return@thread
                lastEngineLantern = false
                stopLanternQuiet()
                stopCoreQuiet()
                startVpn(np, quiet = true)
                failStreak = 0
                stabilizeUntilMs = System.currentTimeMillis() + 120_000L
            } finally {
                reloadBusy.set(false)
            }
        }
    }

    /**
     * فال‌بک خودکار موتور اتصال — ریشه‌ی «وصل می‌شود ولی هیچ داده‌ای رد و بدل نمی‌شود»
     * روی برخی اپراتورها (ایرانسل، فیبر نوری و...): اگر مسیر فعلی دو تست پشت‌سرهم را رد کرد،
     * بی‌سروصدا هسته را با موتور دیگر (مستقیم ↔ ترکیبی) دوباره بالا می‌آوریم تا کاربر
     * واقعاً دیتا داشته باشد. در هر جلسه فقط یک‌بار — تا حلقه‌ی بی‌پایان ساخته نشود.
     *
     * V5.8 — قانون لنترن (درخواست صریح کاربر): موتور لنترن هرگز به سرورهای خودمان
     * سوییچ نمی‌کند؛ اگر این تابع برای لنترن صدا زده شود، به‌جایش بازسازی بی‌صدای
     * خودِ لنترن انجام می‌شود (restartLanternQuiet).
     *
     * v5.2 — تعویض موتور کاملاً «بی‌صدا» شد (رفع «هی صفر می‌شه، هی قطع می‌شه»):
     *  • وضعیت «متصل» دست‌نخورده می‌ماند (پیشتر «در حال اتصال» می‌شد و بعد دوباره وصل — کاربر قطع می‌دید)؛
     *  • مجموع جلسه (دانلود/آپلود تجمعی) صفر نمی‌شود — آمار با quiet=true حفظ می‌شود؛
     *  • تونل فقط برای لحظه‌ی تعویض هسته بسته می‌شود و اعلان هم «متصل» می‌ماند.
     */
    private fun maybeFallbackEngine() {
        val payload = lastPayload ?: return
        if (fallbackDone) return // در هر جلسه فقط یک‌بار — تا حلقه‌ی بی‌پایان ساخته نشود
        val cur = engineOf(payload)
        // لنترن → بازسازی لنترن (سرور جایگزین از شبکه‌ی ابری لنترن) — نه سرورهای خودمان
        if (cur == "lantern") { restartLanternQuiet(); return }
        // ترکیبی → مستقیم · خودکار → ترکیبی · مستقیم → ترکیبی
        val target = when (cur) {
            "hybrid" -> ""
            else -> "hybrid"
        }
        fallbackDone = true
        if (target == cur) return
        val np = try { JSONObject(payload).put("engine", target).toString() } catch (_: Throwable) { return }
        val targetName = when (target) {
            "" -> "مستقیم (Vex)"
            "auto" -> "اتصال خودکار (استخر سرورها)"
            else -> "ترکیبی (Vex + لنترن)"
        }
        Log.w(TAG, "auto engine fallback (quiet): ${cur.ifEmpty { "direct" }} -> ${target.ifEmpty { "direct" }}")
        try {
            android.widget.Toast.makeText(
                applicationContext,
                "دیتا از مسیر فعلی رد نشد — مسیر اتصال بی‌صدا روی «$targetName» عوض شد",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {}
        thread(name = "vex-fallback") {
            stopCoreQuiet()
            startVpn(np, quiet = true) // آمار جلسه حفظ، وضعیت «متصل» بدون تغییر
            // موتور تازه ۲ دقیقه فرصت تثبیت دارد و شمارش خطا از صفر شروع می‌شود
            failStreak = 0
            stabilizeUntilMs = System.currentTimeMillis() + 120_000L
            try {
                startForeground(NOTIF_ID, notif("متصل است", "مسیر اتصال برای پایداری بیشتر عوض شد"))
            } catch (_: Throwable) {}
        }
    }

    /**
     * نوسازی بی‌صدای هسته — پیاده‌سازی همان reloadCore در v2rayNG/PattNG/ArasClient:
     * هسته با کانفیگ تازه (resolve تازه‌ی DNS سرور) و جریان‌های TCP نو بالا می‌آید؛
     * اینترفیس VPN، اعلان و وضعیت «متصل» دست‌نخورده می‌ماند و کاربر هیچ قطعی نمی‌بیند.
     * آمار جلسه هم حفظ می‌شود. برای سوکت‌های مرده‌ی پس از هنداور/فشار DPI دقیقاً همین است.
     */
    private fun performQuietReload(payload: String, reason: String) {
        if (!reloadBusy.compareAndSet(false, true)) return
        try {
            synchronized(stateLock) { if (uiState != "connected") return }
            if (System.currentTimeMillis() - lastReloadAt < 5_000L) return
            lastReloadAt = System.currentTimeMillis()
            Log.w(TAG, "quiet core reload ($reason) — fresh flows, VPN stays up (v2rayNG reloadCore pattern)")
            // V5.9: در جلسه‌ی لنترن، پروسه/نمونه‌ی لنترن زنده می‌ماند (پورت حفظ می‌شود) —
            // فقط هسته‌ی sing-box با جریان‌های نو بالا می‌آید؛ bootLantern پورت موجود را reuse می‌کند
            stopCoreQuiet(keepLantern = engineOf(payload) == "lantern")
            startVpn(payload, quiet = true)
        } finally {
            reloadBusy.set(false)
        }
    }

    // ---------------------------------------------- پایش شبکه‌ی زیرین (الگوی v2rayNG NetworkMonitor)

    /**
     * ریشه‌ی واقعی «هی وصل می‌شود، هی قطع می‌شود» روی گوشی: هنداور WiFi↔دیتای موبایل
     * (و جابه‌جایی آنتن سلولی) به‌صورت make-before-break است — شبکه‌ی نو اعلام می‌شود در
     * حالی‌که سوکت‌های قدیمی هنوز به شبکه‌ی ازدست‌رفته چسبیده‌اند؛ هسته روی سوکت مرده
     * می‌ماند و دیتا ظاهراً «قطع» می‌شود تا سوکت‌ها تایم‌اوت شوند.
     * v2rayNG/PattNG/ArasClient دقیقاً برای همین NetworkMonitor دارند:
     *  ۱) setUnderlyingNetworks با شبکه‌ی واقعی زیرین (نه اینترفیس VPN)،
     *  ۲) هنگام هنداور (با ۱ ثانیه ضدلرزش): بازسازی کامل هسته با جریان‌های نو.
     * برای موتور لنترن/ترکیبی کاری نمی‌کنیم — هسته‌ی لنترن خودش شبکه را مدیریت می‌کند.
     */
    private fun startHandoverWatcher(gen: Int) {
        stopHandoverWatcher()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        // requestNetwork به‌جای registerDefaultNetworkCallback: کال‌بک فقط برای «شبکه‌ی
        // پیش‌فرض واقعی» می‌آید نه اینترفیس VPN خودمان (توضیح داخل NetworkMonitor کلاینت‌های NG)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = upstreamNet
                upstreamNet = network
                applyUnderlyingNetworks(network)
                // هنداور واقعی: شبکه‌ی زیرین عوض شده (در onLost عمداً upstream را خالی نمی‌کنیم
                // تا «قطعی مطلق → شبکه‌ی نو» هم هنداور حساب شود — مثل کلاینت‌های NG)
                if (prev != null && prev != network) scheduleHandover(gen)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (upstreamNet == network) applyUnderlyingNetworks(network)
            }

            override fun onLost(network: Network) {
                if (upstreamNet == network) applyUnderlyingNetworks(null)
            }
        }
        handoverCb = cb
        try {
            cm.requestNetwork(request, cb)
            cm.activeNetwork?.let {
                upstreamNet = it
                applyUnderlyingNetworks(it)
            }
        } catch (_: Throwable) {
            handoverCb = null
        }
    }

    private fun stopHandoverWatcher() {
        handoverRunnable?.let { mainHandler.removeCallbacks(it) }
        handoverRunnable = null
        handoverCb?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Throwable) {}
        }
        handoverCb = null
        upstreamNet = null
    }

    /** شبکه‌ی زیرین واقعی را به سیستم معرفی می‌کند (الگوی serviceControl.setUnderlyingNetworks در v2rayNG) */
    private fun applyUnderlyingNetworks(network: Network?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            if (network != null) setUnderlyingNetworks(arrayOf(network))
            else setUnderlyingNetworks(null)
        } catch (_: Throwable) {}
    }

    private fun scheduleHandover(gen: Int) {
        handoverRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { performHandoverReload(gen) }
        handoverRunnable = r
        mainHandler.postDelayed(r, HANDOVER_DEBOUNCE_MS)
    }

    private fun performHandoverReload(gen: Int) {
        synchronized(stateLock) {
            if (gen != generation || uiState != "connected") return
        }
        val payload = lastPayload ?: return
        val engMode = try { JSONObject(payload).optString("engine") } catch (_: Throwable) { "" }
        if (engMode == "lantern" || engMode == "hybrid") return
        // تنظیم کاربر: اگر خاموش باشد فقط شبکه‌ی زیرین معرفی می‌شود و هسته دست نمی‌خورد
        if (!handoverReload) return
        Log.w(TAG, "underlying network handover — quiet core reload (v2rayNG reloadCore pattern)")
        performQuietReload(payload, "handover")
    }

    /** توقف بی‌صدای هسته/تونل — بدون تغییر وضعیت UI و بدون stopSelf (فقط برای فال‌بک/نوسازی)
     *  keepLantern = پروسه/نمونه‌ی لنترن زنده بماند (quiet-reload جلسه‌ی لنترن — پورت حفظ می‌شود) */
    private fun stopCoreQuiet(keepLantern: Boolean = false) {
        synchronized(stateLock) { generation++ }
        timer?.cancel(); timer = null
        testThread?.interrupt(); testThread = null
        trafficThread?.interrupt(); trafficThread = null
        freeWatchThread?.interrupt(); freeWatchThread = null
        stopHandoverWatcher()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        if (lastEngineLantern && !keepLantern) {
            lastEngineLantern = false
            try {
                applicationContext.startService(
                    Intent(applicationContext, LanternService::class.java).setAction(LanternService.ACTION_STOP)
                )
            } catch (_: Throwable) {}
            LanternEngine.clearPort(applicationContext)
        }
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
        err.contains("process is bad") ->
            "پروسه‌ی لنترن چند بار پشت‌سرهم کرش کرده و اندروید موقتاً اجرای دوباره‌اش را ممنوع کرده — حدود یک دقیقه صبر کن و دوباره وصل شو"
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

    // ================================================================ موتور لنترن (V5.9 — ضد «process is bad»)

    /**
     * شروع امن سرویس لنترن — اگر سیستم درخواست را رد کند (مثلاً «process is bad» یا
     * محدودیت startForegroundService) به‌جای کرش/خطای خام، false برمی‌گرداند تا سیاست
     * بوت تصمیم بگیرد (تلاش دوباره با فاصله / فال‌بک نهایی).
     */
    private fun safeStartLanternService(ctx: android.content.Context, action: String): Boolean = try {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, LanternService::class.java).setAction(action)
        )
        true
    } catch (t: Throwable) {
        Log.e(TAG, "start lantern service ($action) rejected: ${t.message}")
        false
    }

    /** پورت سالم از یک نمونه‌ی زنده‌ی لنترن — برای استفاده‌ی مجدد (quiet-reload) بدون هیچ spawn */
    private fun reuseLiveLantern(ctx: android.content.Context): Int {
        val p = LanternEngine.readPort(ctx)
        return if (p > 0 && LanternEngine.readState(ctx) == "ready" && LanternEngine.isProcessAlive(ctx)) p else 0
    }

    /**
     * سیاست بوت لنترن V5.9 — ریشه‌ی «Unable to launch app ... process is bad»:
     *  کرش‌های بومی Go (log.Fatalf/panic در flashlight) پروسه‌ی :lantern را می‌کشند و با
     *  try/catch قابل‌گرفتن نیستند. V5.8 بعد از هر شکست فوراً پروسه‌ی تازه می‌ساخت؛ دو کرش
     *  سریع پشت‌سرهم یعنی black-list شدن پروسه توسط اندروید. سیاست نو:
     *  ۱) reuse: اگر نمونه‌ی زنده با پورت سالم هست → همان پورت، بدون هیچ spawn؛
     *  ۲) بوت مجدد درون-پروسه‌ای (حداکثر ۲ بار) — پروسه زنده است، پس ساخت پروسه لازم نیست؛
     *  ۳) حداکثر ۲ پروسه‌ی تازه در هر جلسه، با فاصله‌ی ۶ ثانیه + انتظار برای مرگ واقعی
     *     پروسه‌ی قبلی (چک /proc/<pid>/cmdline)؛
     *  ۴) اگر startForegroundService رد شد → یک تلاش دوباره بعد از ۱۲ ثانیه (پنجره‌ی
     *     black-list اندروید حدود یک دقیقه است)؛ رد دوباره = خروج با 0 (فال‌بک نهایی).
     * خروجی: پورت (>0) یا 0 = لنترن کلاً نشد.
     */
    private fun bootLantern(ctx: android.content.Context, gen: Int): Int {
        val reused = reuseLiveLantern(ctx)
        if (reused > 0) {
            Log.i(TAG, "lantern already live on 127.0.0.1:$reused — reusing (no spawn)")
            return reused
        }
        var spawns = 0
        var reboots = 0
        while (true) {
            synchronized(stateLock) { if (gen != generation) return 0 }
            val alive = LanternEngine.isProcessAlive(ctx)
            if (alive && spawns == 0) {
                // پروسه زنده است (مثلاً بعد از قطع قبلی هنوز kill نشده) → فقط بوت درون-پروسه‌ای
                if (reboots >= LANTERN_MAX_REBOOTS) return 0
                reboots++
                Log.i(TAG, "lantern process alive — in-process boot #$reboots (no spawn)")
                safeStartLanternService(ctx, LanternService.ACTION_START)
            } else {
                if (spawns >= LANTERN_MAX_SPAWNS) return 0
                // قبل از هر spawn: مطمئن شو پروسه‌ی قبلی واقعاً مرده (ضد تداخل بوت/کانفیگ)
                if (!alive) LanternEngine.waitProcessGone(ctx, 5_000L)
                if (spawns > 0) {
                    // فاصله‌ی امن بین spawnها — ضد black-list شدن پروسه توسط اندروید
                    try { Thread.sleep(6_000) } catch (_: InterruptedException) { return 0 }
                    synchronized(stateLock) { if (gen != generation) return 0 }
                }
                spawns++
                Log.w(TAG, "lantern fresh process spawn #$spawns/$LANTERN_MAX_SPAWNS (fresh config dir = fresh best-server pick)")
                if (!safeStartLanternService(ctx, LanternService.ACTION_START)) {
                    // معمولاً «process is bad» یا محدودیت FGS — یک فرصت دوباره بعد از ۱۲ ثانیه
                    try { Thread.sleep(12_000) } catch (_: InterruptedException) { return 0 }
                    synchronized(stateLock) { if (gen != generation) return 0 }
                    if (!safeStartLanternService(ctx, LanternService.ACTION_START)) return 0
                }
            }
            val r = awaitLanternBoot(ctx, gen, LANTERN_BOOT_WAIT_MS)
            if (r > 0) return r
            if (r == -1) continue // پروسه در حین بوت مرد (کرش بومی) — spawn بعدی با فاصله‌ی امن
            // r == 0: پروسه زنده است ولی بوت شکست خورد → بوت مجدد درون-پروسه‌ای
            if (reboots < LANTERN_MAX_REBOOTS) {
                reboots++
                Log.w(TAG, "lantern boot failed while process alive — in-process reboot #$reboots (fresh config dir)")
                safeStartLanternService(ctx, LanternService.ACTION_START)
                val r2 = awaitLanternBoot(ctx, gen, LANTERN_BOOT_WAIT_MS)
                if (r2 > 0) return r2
                if (r2 == -1) continue
            }
        }
    }

    /**
     * انتظار برای پورت HTTP لنترن (فایل مشترک بین پروسه‌ها).
     * خروجی: >0 پورت · 0 = شکست قابل‌گرفتن (state=failed) یا تایم‌اوت یا قطع کاربر ·
     * -1 = پروسه‌ی :lantern در حین بوت مرد (کرش بومی Go — قابل‌گرفتن نبود).
     */
    private fun awaitLanternBoot(ctx: android.content.Context, gen: Int, waitMs: Long): Int {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(stateLock) { if (gen != generation) return 0 }
            val p = LanternEngine.readPort(ctx)
            if (p > 0) return p
            // شکست صریح و قابل‌گرفتن بوت → تصمیم بعدی با bootLantern (بوت مجدد درون-پروسه‌ای)
            if (LanternEngine.readState(ctx) == "failed") return 0
            // مرگ پروسه (کرش بومی/بستن توسط سیستم) — جدا از شکست قابل‌گرفتن گزارش می‌شود
            if (!LanternEngine.isProcessAlive(ctx)) {
                Log.w(TAG, "lantern process DIED during boot (native/Go fatal) — will respawn with safe spacing")
                return -1
            }
            try { Thread.sleep(500) } catch (_: InterruptedException) { return 0 }
        }
        Log.w(TAG, "lantern boot wait timeout (${waitMs}ms)")
        return 0
    }

    /** توقف بی‌صدای فقط پروسه‌ی لنترن (قبل از فال‌بک نهایی / بوت تازه) */
    private fun stopLanternQuiet() {
        // نکته: lastEngineLantern عمداً دست نمی‌خورد — اگر کاربر وسط بوت قطع کند،
        // stopVpn باید بتواند پروسه‌ی لنترن را هم جمع کند (بدون زامبی پیش‌زمینه)
        try {
            applicationContext.startService(
                Intent(applicationContext, LanternService::class.java).setAction(LanternService.ACTION_STOP)
            )
        } catch (_: Throwable) {}
        LanternEngine.clearPort(applicationContext)
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
        stopHandoverWatcher()
        // آخرین دلتای جلسه به بک‌اند ربات می‌رود — حتی اگر UI اصلاً باز نبوده باشد
        try { TrafficStore.init(applicationContext) } catch (_: Throwable) {}
        try { TrafficStore.flushAsync(applicationContext) } catch (_: Throwable) {}
        // خداحافظیِ حضور — شمارنده‌ی «آنلاین» فوراً یکی کم می‌شود (بدون انتظار پنجره‌ی ۹۰ ثانیه)
        try { TelegramGate.pingPresence(applicationContext, 0L) } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        releaseWakelock()
        // اگر این جلسه با موتور لنترن بود، پروسه‌ی لنترن هم خاموش می‌شود
        if (lastEngineLantern) {
            lastEngineLantern = false
            try {
                applicationContext.startService(
                    Intent(applicationContext, LanternService::class.java).setAction(LanternService.ACTION_STOP)
                )
            } catch (_: Throwable) {}
            LanternEngine.clearPort(applicationContext)
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
                // ۱.۵) تپش قلب حضور (V5) — «الان چند نفر متصل‌اند» به تفکیک سرور؛
                //   سرویس VPN پیش‌زمینه است و حتی با اپ بسته تپش ادامه دارد (اپ همیشه آنلاین)
                try {
                    val st = ServersStore(applicationContext)
                    val sel = st.selected()
                    val srv = when {
                        sel == null -> 0L
                        st.isFree(sel) -> sel.id.toLong()
                        else -> -1L
                    }
                    TelegramGate.pingPresence(applicationContext, srv)
                } catch (_: Throwable) {}
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
                // ۴) حذف سرور رایگان → قطع خودکار — فقط بعد از «دو تأیید متوالی»
                //    (پاسخ موقتِ ناقص/خالی از سرور دیگر وسط کار اتصال را قطع نمی‌کند)
                try {
                    val raw = lastPayload?.let {
                        try { org.json.JSONObject(it).optString("raw", "") } catch (_: Throwable) { "" }
                    } ?: ""
                    if (raw.isNotEmpty()) {
                        val st = ServersStore(applicationContext)
                        val sel = st.selected()
                        if (sel != null && st.isFree(sel)) {
                            val dtos = TelegramGate.fetchFreeServers() // null = شبکه نبود → کاری نکن
                            if (dtos == null || dtos.any { it.raw == raw }) {
                                srvMissingStreak = 0 // سرور سر جاش است / شبکه نبود — شمارش صفر
                            } else {
                                srvMissingStreak++
                                if (srvMissingStreak >= 2) {
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
                                notifyStable("متصل است", body)
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
                        notifyStable("متصل است", body)
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

    /** اعلان فقط وقتی متن واقعاً عوض شد — نه هر ثانیه (ضرب‌آهنگ v2rayNG) */
    private fun notifyStable(title: String, body: String) {
        val key = title + "\u0000" + body
        if (lastNotifKey.getAndSet(key) == key) return
        try { nm.notify(NOTIF_ID, notif(title, body)) } catch (_: Exception) {}
    }

    /** WakeLock جزئی حین اتصال — مثل SagerNet/NekoBox؛ مانع خواب CPU و کندشدن تونل */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakelock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(android.os.PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vex:vpn").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWakelock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
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
