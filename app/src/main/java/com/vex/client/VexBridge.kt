package com.vex.client

import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** پلی بین صفحه‌ی HTML و کد اندروید: window.VexNative */
class VexBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun start(configJson: String) {
        activity.runOnUiThread { activity.requestStart(configJson) }
    }

    @JavascriptInterface
    fun stop() {
        activity.runOnUiThread { activity.requestStop() }
    }

    /** درخواست تست فوری اینترنت از مسیر سرور */
    @JavascriptInterface
    fun retest() {
        VexVpnService.requestNetTest()
    }

    /**
     * خواندن کلیپ‌بورد به‌صورت نیتیو.
     * (navigator.clipboard روی file:// کار نمی‌کند — origin امن نیست؛ پس از این پل استفاده می‌کنیم)
     */
    @JavascriptInterface
    fun getClipboard(): String {
        val latch = CountDownLatch(1)
        var text = ""
        activity.runOnUiThread {
            try {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val item = cm.primaryClip
                if (item != null && item.itemCount > 0) text = item.getItemAt(0).coerceToText(activity)?.toString() ?: ""
            } catch (_: Throwable) {}
            latch.countDown()
        }
        try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return text
    }

    /**
     * دریافت لینک اشتراک (subscription) به‌صورت نیتیو و ارسال نتیجه به
     * window.vexOnSub(text) یا خطا با window.vexOnToast(msg)
     */
    @JavascriptInterface
    fun fetchSub(url: String) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val u = URL(url.trim())
                require(u.protocol in setOf("http", "https")) { "آدرس اشتراک باید http/https باشد" }
                conn = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "v2rayNG/1.8.23")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw Exception("کد پاسخ سرور اشتراک: $code")
                val body = conn.inputStream?.bufferedReader()?.readText() ?: ""
                activity.pushSubText(body.trim())
            } catch (e: Exception) {
                activity.pushToast("دریافت اشتراک ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }

    /** اسکن QR با دوربین واقعی (دسترسی دوربین در اکتیویتی نیتیو گرفته می‌شود) */
    @JavascriptInterface
    fun scanQr() {
        activity.runOnUiThread { activity.startQrScan() }
    }

    /** انتخاب عکس QR از گالری و دیکود با ML Kit */
    @JavascriptInterface
    fun pickQr() {
        activity.runOnUiThread { activity.startQrGallery() }
    }

    /**
     * پینگ واقعی سرورها: زمان کامل TCP handshake ( مانند v2rayNG ).
     * ورودی: JSON آرایه‌ای از {id, host, port}
     * خروجی: برای هر سرور window.vexOnPing(id, ms) فراخوانی می‌شود (-1 = ناموفق)
     */
    @JavascriptInterface
    fun ping(payload: String) {
        Thread {
            try {
                val arr = JSONArray(payload)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optInt("id")
                    val host = o.optString("host", "").trim()
                    val port = o.optInt("port", 443).let { if (it in 1..65535) it else 443 }
                    val ms = if (host.isEmpty()) -1 else tcpPing(host, port)
                    activity.pushPing(id, ms)
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    @JavascriptInterface
    fun version(): String = "1.3.0"

    /** لاگ کامل هسته (برای نمایش در صفحه‌ی «جزئیات خطا») */
    @JavascriptInterface
    fun getLogs(): String = try {
        VexVpnService.coreLogAll()
    } catch (_: Throwable) {
        ""
    }

    /** زمان واقعی برقراری اتصال TCP به سرور به میلی‌ثانیه */
    private fun tcpPing(host: String, port: Int): Int = try {
        val t0 = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 4000)
        }
        ((System.nanoTime() - t0) / 1_000_000L).toInt()
    } catch (_: Throwable) {
        -1
    }
}
