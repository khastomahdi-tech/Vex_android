package com.vex.client

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تست سرعت واقعی تونل — v5.3:
 *  وقتی VPN متصل است، از inbound محلی (mixed روی ۱۲۷.۰.۰.۱:۱۹۰۸۵) یک فایل آزمایشی
 *  دانلود می‌شود؛ یعنی دقیقاً همان مسیری که همه‌ی اپ‌های گوشی رد می‌شوند:
 *
 *      دانلود ← هسته ← تونل ← سرور VEX ← اینترنت (Cloudflare)
 *
 *  خروجی: سرعت لحظه‌ای/نهایی به Mbps — برای اینکه کاربر «قدرت اتصال» را ببیند.
 *  اگر VPN وصل نباشد، مستقیم (بدون پروکسی) تست می‌شود و نتیجه به‌عنوان
 *  «سرعت بدون تونل» برمی‌گردد تا مقایسه ممکن باشد.
 */
object SpeedTest {

    /** ۸ مگابایت داده — برای شبکه‌های ایران به‌اندازه‌ی کافی و سریع */
    private const val TEST_BYTES = 8L * 1024 * 1024
    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=$TEST_BYTES"
    private const val TIMEOUT = 15_000

    /** لغو تست در حال اجرا */
    val cancelled = AtomicBoolean(false)

    /**
     * اجرای تست — روی Thread پس‌زمینه صدا زده می‌شود.
     * onProgress(مگابایت‌های دانلود‌شده, سرعت لحظه‌ای Mbps) — هر ~۳۰۰ms
     * خروجی: Pair(سرعت نهایی Mbps, پیام خطا) — پیام خطا فقط وقتی شکست غیر-null است.
     */
    fun run(
        throughVpn: Boolean,
        onProgress: (downloadedMb: Double, instantMbps: Double) -> Unit
    ): Pair<Double, String?> {
        cancelled.set(false)
        val proxy = if (throughVpn)
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.LOCAL_TEST_PORT))
        else Proxy.NO_PROXY
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(TEST_URL).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                useCaches = false
                setRequestProperty("User-Agent", "VEX-Client/1.3 (speed-test)")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) return 0.0 to "پاسخ سرور: $code"
            val input: InputStream = conn.inputStream ?: return 0.0 to "دیتایی دریافت نشد"

            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastReport = System.currentTimeMillis()
            val t0 = System.currentTimeMillis()
            var lastBytes = 0L

            while (!cancelled.get()) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                val now = System.currentTimeMillis()
                if (now - lastReport >= 300) {
                    val inst = (total - lastBytes) * 8.0 / 1_000_000.0 /
                            ((now - lastReport) / 1000.0)
                    lastBytes = total
                    lastReport = now
                    onProgress(total / 1_000_000.0, inst)
                }
                if (total >= TEST_BYTES) break
            }
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            if (secs < 0.4) return 0.0 to "اتصال بیش از حد سریع قطع شد"
            val mbps = total * 8.0 / 1_000_000.0 / secs
            return mbps to null
        } catch (e: Exception) {
            return 0.0 to (e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** قالب‌بندی سرعت برای نمایش */
    fun fmt(mbps: Double): String =
        String.format(Locale.US, "%.1f Mbps", mbps)
}
