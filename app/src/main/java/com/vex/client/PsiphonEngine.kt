package com.vex.client

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * اجراکننده‌ی موتور سایفون — فقط داخل پروسه‌ی جدا «:psiphon».
 *
 * چرا پروسه‌ی جدا؟
 *  هم libbox (هسته‌ی sing-box) و هم کتابخانه‌ی Psiphon با gomobile ساخته شده‌اند و هر دو
 *  ران‌تایم بومی go.Seq را دارند. اگر در یک پروسه بارگذاری شوند، متدهای بومیِ go.Seq
 *  بین دو کتابخانه‌ی بومی تداخل می‌کند. با جداکردن پروسه:
 *   • پروسه‌ی اصلی → فقط libbox (VPN و هسته‌ی اصلی)
 *   • پروسه‌ی :psiphon → فقط libgojni (تونل سایفون)
 *  ارتباط بین دو پروسه از طریق فایل پورت SOCKS (filesDir/psiphon_port.txt) انجام می‌شود —
 *  چون پروکسی سایفون روی 127.0.0.1 گوش می‌دهد، هسته‌ی sing-box پروسه‌ی اصلی می‌تواند
 *  بدون هیچ واسطه‌ای به آن وصل شود (loopback بین پروسه‌های یک اپ مشترک است).
 */
object PsiphonEngine : PsiphonTunnel.HostService {

    private const val TAG = "VexPsiphon"

    /** فایل پورت — بین پروسه‌ها مشترک است */
    fun portFile(ctx: android.content.Context): File = File(ctx.filesDir, "psiphon_port.txt")

    fun readPort(ctx: android.content.Context): Int {
        return try {
            val t = portFile(ctx).readText().trim()
            val p = t.toIntOrNull()?.coerceIn(0, 65535) ?: 0
            if (p > 0 && t.toLong() in 1..65535) p else 0
        } catch (_: Throwable) { 0 }
    }

    fun clearPort(ctx: android.content.Context) {
        try { portFile(ctx).delete() } catch (_: Throwable) {}
    }

    /** پورت SOCKS محلی سایفون — ۰ یعنی هنوز آماده نشده */
    @Volatile
    var socksPort: Int = 0
        private set

    /** idle | connecting | connected | failed | stopped */
    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    private var tunnel: PsiphonTunnel? = null

    @Volatile
    private var appCtx: android.content.Context? = null

    /**
     * راه‌اندازی تونل سایفون (در پروسه‌ی :psiphon) — بلاک نمی‌کند؛
     * پورت به‌محض آماده‌شدن در فایل و socksPort نوشته می‌شود.
     */
    fun start(ctx: android.content.Context) {
        synchronized(this) {
            if (tunnel != null) return
            socksPort = 0
            state = "connecting"
            appCtx = ctx.applicationContext
            clearPort(ctx)
            try {
                val t = PsiphonTunnel.newPsiphonTunnel(this)
                tunnel = t
                Thread {
                    try {
                        // فقط حالت پروکسی محلی — نه VPN مستقل (setVpnMode=false پیش‌فرض)
                        t.startTunneling("")
                    } catch (e: Throwable) {
                        Log.e(TAG, "startTunneling failed: ${e.message}")
                        state = "failed"
                    }
                }.start()
            } catch (e: Throwable) {
                Log.e(TAG, "newPsiphonTunnel failed: ${e.message}")
                state = "failed"
            }
        }
    }

    fun stop(ctx: android.content.Context?) {
        val t = tunnel ?: return
        tunnel = null
        socksPort = 0
        state = "stopped"
        if (ctx != null) clearPort(ctx)
        Thread {
            try { t.stop() } catch (_: Throwable) {}
        }.start()
    }

    fun isRunning(): Boolean = tunnel != null

    // ------------------------------------------------ کانفیگ سایفون

    override fun getPsiphonConfig(): String {
        val ctx = appCtx ?: return ""
        val dataStore = File(ctx.filesDir, "psiphon_data")
        val tempStore = File(ctx.cacheDir, "psiphon_tmp")
        try {
            dataStore.mkdirs()
            tempStore.mkdirs()
        } catch (_: Throwable) {}
        val o = org.json.JSONObject()
            .put("ClientVersion", "1")
            // مقادیر استاندارد متن‌باز (Oblivion/Aether) — برای شمارش نصب‌ها نزد Psiphon
            .put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
            .put("SponsorId", "FFFFFFFFFFFFFFFF")
            // لیست رسمی سرورهای سایفون (امضاشده) — پورت و پروتکل‌ها خودکار چرخش می‌شوند
            .put("RemoteServerListUrl", "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed")
            .put("RemoteServerListDownloadFilename", "remote_server_list")
            .put("RemoteServerListSignaturePublicKey", REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY)
            .put("DataStoreDirectory", dataStore.absolutePath)
            .put("DataStoreTempDirectory", tempStore.absolutePath)
            .put("LocalSocksProxyPort", 0)   // پورت آزاد خودکار — از کالبک خوانده می‌شود
            .put("LocalHttpProxyPort", 0)
            .put("UseIndistinguishableTLS", true)
            .put("TunnelProtocol", "")
            .put("EgressRegion", "")
            .put("UpstreamProxyUrl", "")
        return o.toString()
    }

    /** کلید عمومی امضای لیست سرورهای سایفون (ثابت و رسمی — مثل همه‌ی کلاینت‌های متن‌باز) */
    private const val REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    // ------------------------------------------------ کالبک‌های HostService

    override fun getContext(): android.content.Context = appCtx ?: throw IllegalStateException("PsiphonEngine not started")

    override fun onListeningSocksProxyPort(port: Int) {
        Log.i(TAG, "psiphon socks listening on $port")
        socksPort = port
        if (state != "connected") state = "connecting"
        // پورت برای پروسه‌ی اصلی نوشته می‌شود
        val ctx = appCtx
        if (ctx != null && port > 0) {
            try { portFile(ctx).writeText(port.toString()) } catch (_: Throwable) {}
        }
    }

    override fun onConnecting() {
        if (state != "connected") state = "connecting"
    }

    override fun onConnected() {
        Log.i(TAG, "psiphon tunnel connected")
        state = "connected"
    }

    override fun onExiting() {
        Log.i(TAG, "psiphon exiting")
        socksPort = 0
        val ctx = appCtx
        if (ctx != null) clearPort(ctx)
        if (state != "idle" && state != "stopped") state = "stopped"
    }

    override fun onDiagnosticMessage(message: String) {
        Log.d(TAG, message)
    }

    /** اعلان ساوت پیش‌زمینه برای پروسه‌ی سایفون (تا سیستم وسط کار نکشدش) */
    fun buildForegroundNotification(ctx: android.content.Context): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(ctx, Notifier.STATUS_CHANNEL_ID)
            .setContentTitle("VEX CLIENT — سایفون")
            .setContentText("موتور سایفون فعال است")
            .setSmallIcon(R.drawable.ic_stat_vex)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }
}
