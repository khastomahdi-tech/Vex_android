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
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator as BoxNetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
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

        // ---- وضعیت فعلی برای بازگردانی UI (وقتی اکتیویتی دوباره باز می‌شود) ----
        @Volatile var uiState: String = "stopped"   // stopped | connecting | connected
        @Volatile var netState: Int = 0             // 0 ناشناخته، 1 تأیید، 2 رد شد
        @Volatile var netMsg: String = ""
        @Volatile var netMs: Int = -1
        @Volatile var snapDn: Long = 0
        @Volatile var snapUp: Long = 0
        @Volatile var snapTDn: Long = 0
        @Volatile var snapTUp: Long = 0
        /** زمان اتصال موفق برای محاسبه‌ی مدت جلسه در صفحه‌ی آمار */
        @Volatile var connectedAtMs: Long = 0

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
        when (intent?.action) {
            ACTION_START -> startVpn(intent?.getStringExtra(EXTRA_CONFIG).orEmpty())
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(config: String) {
        // باید سریع فورگراند شویم، وگرنه اندروید سرویس را می‌کشد
        startForeground(NOTIF_ID, notif("در حال اتصال...", "راه‌اندازی هسته..."))
        uiState = "connecting"
        netState = 0; netMsg = ""; netMs = -1
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
                val coreConfig = ConfigBuilder.build(config)
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

                // ---- اتصال اعلام می‌شود (مانند v2rayNG)؛ تست اینترنت در پس‌زمینه ----
                synchronized(stateLock) { if (gen != generation) return@thread }
                verified = false
                uiState = "connected"
                connectedAtMs = System.currentTimeMillis()
                startForeground(NOTIF_ID, notif("متصل است", "در حال تست اینترنت..."))
                stateListener?.invoke("connected", "")
                startNetTestLoop(gen)
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
                        netMsg = "ترافیک از سرور رد نشد ($err)" +
                                (coreErr?.let { "\nهسته: $it" } ?: "")
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

    private fun stopVpn(error: String? = null, includeLogs: Boolean = false) {
        synchronized(stateLock) { generation++ }
        uiState = "stopped"
        connectedAtMs = 0
        timer?.cancel(); timer = null
        testThread?.interrupt(); testThread = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
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

    // ------------------------------------------------------------------ آمار

    private fun startStatsTimer() {
        timer = Timer().also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
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
                        val body = "↓ ${fmt(dRx)}/s    ↑ ${fmt(dTx)}/s\nکل: ↓ ${fmt(totalRx)}   ↑ ${fmt(totalTx)}"
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
