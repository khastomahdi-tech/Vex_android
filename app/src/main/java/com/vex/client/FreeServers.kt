package com.vex.client

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * موتور «سرورهای رایگان» VEX — فقط از طریق ربات:
 *
 *  ۱) مدیر در ربات تلگرام کانفیگ (vless/vmess/trojan/ss) می‌فرستد و در D1 کلادفلر ذخیره می‌شود؛
 *  ۲) برنامه از بک‌اند ربات (vex-gate.pages.dev) فهرست فقط‌خواندنی را می‌گیرد؛
 *  ۳) هر کانفیگ با پینگ TCP واقعی سنجیده می‌شود (استخر Thread)؛
 *  ۴) فقط سرورهای سالم (پینگ موفق + پاسخ‌گو) نمایش داده می‌شوند، مرتب بر اساس پینگ؛
 *  ۵) کاربر نمی‌تواند ویرایش یا حذف کند — فقط اتصال و پینگ.
 */
object FreeServers {

    /** تایم‌اوت پینگ سرورهای رایگان (ثانیه) */
    private const val PING_TIMEOUT_SEC = 3

    /** حداکثر پینگ قابل قبول (ms) — بیشتر از این یعنی عملاً بی‌فایده */
    private const val MAX_PING_MS = 2500

    /** آیا در حال دریافت/سنجش هستیم؟ */
    @Volatile var fetching = false
        private set

    /**
     * فهرست دریافتی از ربات را پینگ کرده و فقط سالم‌ها را (مرتب بر اساس پینگ) برمی‌گرداند.
     * نام و ترتیب سرورها از مدیریتِ ربات حفظ می‌شود؛ فقط سالم‌ها نگه داشته می‌شوند.
     * onProgress برای پیام‌های مرحله‌ای (caller به Thread اصلی پست می‌کند).
     */
    fun aliveFromBot(
        dtos: List<TelegramGate.FreeServerDto>,
        onProgress: (String) -> Unit
    ): List<Server> {
        if (dtos.isEmpty()) return emptyList()
        fetching = true
        try {
            onProgress("پینگ ${dtos.size} سرور رایگان…")
            val alive = pingAll(dtos.map { it.raw })
            if (alive.isEmpty()) {
                onProgress("هیچ‌کدام از سرورهای رایگان پاسخ ندادند — بعداً دوباره تلاش کن")
                return emptyList()
            }
            val pingByRaw = HashMap<String, Int>(alive.size * 2)
            for (s in alive) pingByRaw[s.raw] = s.ping
            val out = ArrayList<Server>()
            for ((i, d) in dtos.withIndex()) {
                val ping = pingByRaw[d.raw] ?: continue // فقط سالم‌ها
                out.add(Server(
                    id = ServersStore.FREE_ID_BASE + i + 1,
                    name = d.name,
                    protocol = d.proto,
                    raw = d.raw,
                    ping = ping
                ))
            }
            out.sortBy { it.ping }
            onProgress("${out.size} سرور رایگان سالم پیدا شد")
            return out
        } finally {
            fetching = false
        }
    }

    /** پینگ موازی لیست raw — خروجی: فقط سرورهای سالم */
    fun pingAll(raws: List<String>): List<Server> {
        if (raws.isEmpty()) return emptyList()
        val pool: ExecutorService = Executors.newFixedThreadPool(14)
        val results = java.util.Collections.synchronizedList(ArrayList<Server>())
        val futures = ArrayList<Future<*>>(raws.size)
        for (raw in raws) {
            futures.add(pool.submit {
                try {
                    val inf = LinkParser.info(raw)
                    val host = inf.host ?: return@submit
                    val port = inf.port ?: 443
                    val ms = NetUtil.tcpPing(host, port, PING_TIMEOUT_SEC)
                    // فقط سرورهایی که واقعاً پاسخ دادند و پینگ معقول دارند
                    if (ms > 0 && ms <= MAX_PING_MS) {
                        synchronized(results) {
                            results.add(Server(id = 0, name = "", protocol = inf.protocol, raw = raw, ping = ms))
                        }
                    }
                } catch (_: Throwable) {}
            })
        }
        for (f in futures) try { f.get(12, TimeUnit.SECONDS) } catch (_: Throwable) {}
        pool.shutdownNow()
        return results
    }

    /** پینگ تکی یک سرور رایگان (برای دکمه‌ی پینگ هر ردیف) */
    fun pingOne(s: Server, timeoutSec: Int): Int {
        val inf = try { LinkParser.info(s.raw) } catch (_: Throwable) { return -2 }
        val host = inf.host ?: return -2
        return NetUtil.tcpPing(host, inf.port ?: 443, timeoutSec.coerceIn(2, 8))
    }
}
