package com.vex.client

import android.webkit.JavascriptInterface
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Socket

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
    fun version(): String = "1.2.2"

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
