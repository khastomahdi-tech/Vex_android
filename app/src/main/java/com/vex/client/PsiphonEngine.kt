package com.vex.client

import android.content.Context
import android.util.Log
import ca.psiphon.PsiphonTunnel
import org.json.JSONObject
import java.io.File

/**
 * موتور «اتصال با سایفون» — کتابخانه‌ی رسمی Psiphon (tunnel-core) داخل خود برنامه.
 *
 * معماری:
 *  • سایفون در حالت «پروکسی محلی» اجرا می‌شود (بدون ساختن VPN مستقل) و یک SOCKS5
 *    روی 127.0.0.1 باز می‌کند — پورت واقعی از کالبک onListeningSocksProxyPort می‌آید.
 *  • بعد از آن هسته‌ی sing-box برنامه (همان تونل VPN همیشگی) با outbound نوع socks
 *    به همین پورت وصل می‌شود؛ یعنی کل ترافیک گوشی: tun → sing-box → سایفون → اینترنت.
 *  • مزیت: هیچ تداخلی با VpnService فعلی نیست و همه‌ی امکانات برنامه (تست اینترنت،
 *    آمار، اعلان، kill-switch) بدون تغییر کار می‌کند.
 *
 *  مقادیر کانفیگ، همان مقادیر استاندارد استفاده‌شده در پروژه‌های متن‌باز معتبر
 *  (Oblivion/Aether) است + لیست سرورهای رسمی سایفون (Remote Server List امضاشده).
 */
object PsiphonEngine : PsiphonTunnel.HostService {

    private const val TAG = "VexPsiphon"

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
    private var appCtx: Context? = null

    /**
     * راه‌اندازی (یا استفاده‌ی مجدد از) تونل سایفون و منتظر ماندن برای پورت SOCKS.
     * روی Thread فراخوانده می‌شود (بلاک می‌کند) — خروجی ۰ یعنی موفق نشد.
     */
    fun ensureStarted(ctx: Context, timeoutMs: Long = 90_000L): Int {
        val app = ctx.applicationContext
        synchronized(this) {
            if (tunnel == null) {
                socksPort = 0
                state = "connecting"
                appCtx = app
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
                    return 0
                }
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (socksPort > 0) return socksPort
            if (state == "failed" || state == "stopped") return 0
            try { Thread.sleep(400) } catch (_: InterruptedException) { return 0 }
        }
        Log.w(TAG, "psiphon socks port timeout")
        return 0
    }

    /** آیا تونل سایفون به خروجی واقعی وصل شده؟ */
    fun isConnected(): Boolean = state == "connected"

    /** چند ثانیه فرصت می‌دهد تا سایفون به خروجی برسد (خروجی الزامی نیست — اتصال دیرتر هم خودش زنده می‌شود) */
    fun awaitConnected(extraMs: Long) {
        val deadline = System.currentTimeMillis() + extraMs
        while (System.currentTimeMillis() < deadline) {
            if (state == "connected") return
            if (state == "failed" || state == "stopped") return
            try { Thread.sleep(400) } catch (_: InterruptedException) { return }
        }
    }

    fun stop() {
        val t = tunnel ?: return
        tunnel = null
        socksPort = 0
        state = "stopped"
        Thread {
            try { t.stop() } catch (_: Throwable) {}
        }.start()
    }

    // ------------------------------------------------ کانفیگ سایفون

    override fun getPsiphonConfig(): String {
        val ctx = appCtx ?: return ""
        val dataStore = File(ctx.filesDir, "psiphon_data")
        val tempStore = File(ctx.cacheDir, "psiphon_tmp")
        try {
            dataStore.mkdirs()
            tempStore.mkdirs()
        } catch (_: Throwable) {}
        val o = JSONObject()
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

    override fun getContext(): Context = appCtx ?: throw IllegalStateException("PsiphonEngine not started")

    override fun onListeningSocksProxyPort(port: Int) {
        Log.i(TAG, "psiphon socks listening on $port")
        socksPort = port
        if (state != "connected") state = "connecting"
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
        if (state != "idle" && state != "stopped") state = "stopped"
    }

    override fun onDiagnosticMessage(message: String) {
        Log.d(TAG, message)
    }
}
