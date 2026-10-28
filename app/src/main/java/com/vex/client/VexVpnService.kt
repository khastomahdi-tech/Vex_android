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
import java.net.Inet6Address
import java.net.NetworkInterface
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
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: CoreEngine? = null
    private var timer: Timer? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private val stateLock = Any()
    private var generation = 0

    // ---- آمار واقعی از شمارنده‌ی اینترفیس tun ----
    @Volatile private var tunIfaceName: String? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var totalRx = 0L
    private var totalTx = 0L
    private var statSource = 0 // 1 = شمارنده tun ، 2 = TrafficStats

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "وضعیت VEX", NotificationManager.IMPORTANCE_LOW)
        )
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
                startStatsTimer()
                startForeground(NOTIF_ID, notif("متصل است", "آمار زنده فعال است"))
                stateListener?.invoke("connected", "")
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                if (gen == generation) stopVpn(error = e.message ?: "خطا در اتصال به هسته")
            }
        }
    }

    private fun stopVpn(error: String? = null) {
        synchronized(stateLock) { generation++ }
        timer?.cancel(); timer = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        tunIfaceName = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (error != null) stateListener?.invoke("error", error)
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
                    statsListener?.invoke(dRx, dTx, totalRx, totalTx)
                    val body = "↓ ${fmt(dRx)}/s    ↑ ${fmt(dTx)}/s\nکل: ↓ ${fmt(totalRx)}   ↑ ${fmt(totalTx)}"
                    try { nm.notify(NOTIF_ID, notif("متصل است", body)) } catch (_: Exception) {}
                }
            }, 1000, 1000)
        }
    }

    /** خواندن بایت‌های واقعی اینترفیس tun از /sys؛ در نبود آن از TrafficStats */
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
        // جایگزین: آمار کل دستگاه (واقعی ولی شامل ترافیک خود اپ)
        val rx = android.net.TrafficStats.getTotalRxBytes()
        val tx = android.net.TrafficStats.getTotalTxBytes()
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
        Log.i("VexCore", message ?: "")
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int
    ): Int = throw Exception("process search is not supported")

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
