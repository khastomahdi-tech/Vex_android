package com.vex.client

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/** اطلاعات آی‌پی خروجی: آدرس عمومی + کشور + کد دوحرفی کشور (برای پرچم) */
data class ExitInfo(val ip: String, val country: String, val code: String)

/** ابزارهای شبکه‌ی واقعی: پینگ TCP و دریافت لینک اشتراک */
object NetUtil {

    /**
     * گرفتن «آی‌پی خروجی واقعی سرور».
     *
     * نکته‌ی مهم: خود برنامه از تونل مستثناست (addDisallowedApplication)؛ پس یک
     * درخواست مستقیم، آی‌پی واقعی کاربر را می‌دهد — نه سرور! برای همین درخواست
     * عمداً از inbound محلی هسته (127.0.0.1:19085) رد می‌شود تا از داخل تونل
     * خارج شود و آی‌پی برگشتی دقیقاً همان خروجی سروری باشد که به آن وصل شده‌ایم.
     *
     * چند منبع + دو دور تلاش (فاصله‌دار) تا تازه‌سازی اتصال هم پوشش داده شود.
     * اگر تونل جواب نداد null برمی‌گردد — هیچ‌وقت آی‌پی اشتباه نشان داده نمی‌شود.
     */
    fun fetchExitInfo(throughTunnel: Boolean = true): ExitInfo? {
        val proxy = if (throughTunnel)
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.LOCAL_TEST_PORT))
        else null
        for (attempt in 1..2) {
            // ۱) ipwho.is — رایگان، بدون کلید، HTTPS
            try {
                val body = fetchJson("https://ipwho.is/", proxy)
                if (body.isNotBlank()) {
                    val j = org.json.JSONObject(body)
                    if (j.optBoolean("success", true)) {
                        val ip = j.optString("ip").trim()
                        if (ip.isNotEmpty()) return ExitInfo(
                            ip,
                            j.optString("country").trim().ifEmpty { "—" },
                            j.optString("country_code").trim().uppercase()
                        )
                    }
                }
            } catch (_: Throwable) {}
            // ۲) ip-api.com — رایگان (HTTP)، بدون کلید
            try {
                val body = fetchJson("http://ip-api.com/json/?fields=status,query,country,countryCode", proxy)
                if (body.isNotBlank()) {
                    val j = org.json.JSONObject(body)
                    if (j.optString("status") == "success") {
                        val ip = j.optString("query").trim()
                        if (ip.isNotEmpty()) return ExitInfo(
                            ip,
                            j.optString("country").trim().ifEmpty { "—" },
                            j.optString("countryCode").trim().uppercase()
                        )
                    }
                }
            } catch (_: Throwable) {}
            // ۳) ipinfo.io — بدون توکن هم جواب می‌دهد (محدود)
            try {
                val body = fetchJson("https://ipinfo.io/json", proxy)
                if (body.isNotBlank()) {
                    val j = org.json.JSONObject(body)
                    val ip = j.optString("ip").trim()
                    if (ip.isNotEmpty()) return ExitInfo(
                        ip,
                        j.optString("country").trim().ifEmpty { "—" },
                        j.optString("country").trim().uppercase()
                    )
                }
            } catch (_: Throwable) {}
            if (attempt == 1) {
                // تازه وصل شده‌ایم؛ کمی صبر تا inbound هسته آماده شود
                try { Thread.sleep(2500) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    /** درخواست GET برای سرویس‌های جئو-آی‌پی — با پروکسی اختیاری (مسیر تونل) */
    private fun fetchJson(url: String, proxy: Proxy? = null): String {
        var conn: HttpURLConnection? = null
        return try {
            val raw = URL(url).openConnection(
                proxy ?: Proxy.NO_PROXY) as HttpURLConnection
            conn = raw.apply {
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "VEX-Client/3.10 (exit-ip)")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) "" else conn.inputStream?.bufferedReader()?.readText() ?: ""
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** پرچم اموجی از کد دوحرفی کشور (مثل IR → 🇮🇷) */
    fun flagEmoji(code: String): String {
        val c = code.trim().uppercase()
        if (c.length != 2 || c.any { it !in 'A'..'Z' }) return "🌐"
        return buildString {
            for (ch in c) append(Character.toChars(0x1F1E6 + (ch - 'A')))
        }
    }

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
