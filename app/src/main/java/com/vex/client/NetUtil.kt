package com.vex.client

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/** ابزارهای شبکه‌ی واقعی: پینگ TCP و دریافت لینک اشتراک */
object NetUtil {

    /**
     * پینگ واقعی TCP (مانند v2rayNG): زمان کامل handshake به میلی‌ثانیه.
     * خروجی: -1 = ناموفق
     */
    fun tcpPing(host: String, port: Int, timeoutSec: Int = 4): Int {
        val h = host.trim().removeSurrounding("[", "]")
        if (h.isEmpty()) return -1
        val p = if (port in 1..65535) port else 443
        return try {
            val t0 = System.nanoTime()
            Socket().use { s ->
                s.connect(InetSocketAddress(h, p), timeoutSec.coerceIn(2, 8) * 1000)
            }
            ((System.nanoTime() - t0) / 1_000_000L).toInt()
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * دریافت محتوای لینک اشتراک (http/https).
     * خروجی: متن بدنه یا استثنا با پیام قابل نمایش.
     */
    fun fetchSubscription(url: String): String {
        val u = url.trim()
        require(u.startsWith("http://", true) || u.startsWith("https://", true)) {
            "آدرس اشتراک باید با http/https شروع شود"
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "v2rayNG/1.8.23")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("کد پاسخ سرور: $code")
            return conn.inputStream?.bufferedReader()?.readText() ?: ""
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** تست HTTP از مسیر پروکسی محلی (برای دکمه‌ی تست اینترنت وقتی سرویس روشن است) */
    fun testViaProxy(port: Int): Pair<Int, String?> {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        val urls = arrayOf(
            "https://www.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
            "http://www.msftconnecttest.com/connecttest.txt"
        )
        var lastMsg = ""
        for (u in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "VEX-Client/2.0 (connection-test)")
                }
                val code = conn.responseCode
                try { conn.inputStream?.close() } catch (_: Throwable) {}
                try { conn.errorStream?.close() } catch (_: Throwable) {}
                if (code in 200..399) return code to null
                lastMsg = "کد پاسخ: $code"
            } catch (e: Exception) {
                lastMsg = e.message ?: e.javaClass.simpleName
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
        return -1 to lastMsg
    }
}
