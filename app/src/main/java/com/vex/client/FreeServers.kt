package com.vex.client

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * موتور «سرورهای رایگان» VEX — فقط از طریق ربات:
 *
 *  ۱) مدیر در ربات تلگرام کانفیگ (vless/vmess/trojan/ss) می‌فرستد و در D1 کلادفلر ذخیره می‌شود؛
 *  ۲) برنامه هر دقیقه از بک‌اند ربات (vex-gate.pages.dev) فهرست فقط‌خواندنی را می‌گیرد؛
 *  ۳) برای مرتب‌سازی، پینگ TCP واقعی گرفته می‌شود (استخر Thread)؛
 *  ۴) هر کانفیگِ قابل‌پارس در لیست می‌ماند — حتی اگر لحظه‌ی پینگ پاسخ نداد
 *     (سرور ممکن است موقتاً شلوغ باشد)؛ پاسخ‌نداده‌ها ته لیست می‌نشینند؛
 *  ۵) کاربر نمی‌تواند ویرایش یا حذف کند — فقط اتصال و پینگ.
 *
 *  v3.15: شناسه‌ی هر سرور از هشِ raw ساخته می‌شود، پس با هر به‌روزرسانی لیست
 *  ثابت می‌ماند و اتصال/انتخابِ فعلی کاربر به‌هم نمی‌ریزد.
 */
object FreeServers {

    /** تایم‌اوت پینگ سرورهای رایگان (ثانیه) */
    private const val PING_TIMEOUT_SEC = 3

    /** حداکثر پینگ «سالم» (ms) — فقط برای مرتب‌سازی؛ سرور حذف نمی‌شود */
    private const val MAX_PING_MS = 2500

    /** آیا در حال دریافت/سنجش هستیم؟ */
    @Volatile var fetching = false
        private set

    /** شناسه‌ی پایدار بر اساس متن کانفیگ — با به‌روزرسانی/جابه‌جایی لیست عوض نمی‌شود */
    private fun stableId(raw: String): Int {
        val h = (raw.hashCode().toLong() and 0x7FFFFFFFL) % 1_000_000L
        return ServersStore.FREE_ID_BASE + h.toInt() + 1
    }

    /**
     * فهرست دریافتی از ربات را به لیست Server تبدیل می‌کند:
     *  • کانفیگ خراب (غیرقابل‌پارس) حذف می‌شود؛
     *  • برای بقیه پینگ گرفته می‌شود — فقط برای مرتب‌سازی و نشان «کیفیت»؛
     *  • پاسخ‌داده‌ها (پینگ ≤ MAX_PING_MS) بالای لیست بر اساس پینگ؛
     *  • بقیه ته لیست (پینگ = -۲ یعنی فعلاً پاسخ نداد).
     * onProgress برای پیام‌های مرحله‌ای (caller به Thread اصلی پست می‌کند).
     */
    fun fromBot(
        dtos: List<TelegramGate.FreeServerDto>,
        onProgress: (String) -> Unit
    ): List<Server> {
        if (dtos.isEmpty()) return emptyList()
        fetching = true
        try {
            onProgress("پینگ ${dtos.size} سرور رایگان…")
            val alive = pingAll(dtos.map { it.raw })
            val pingByRaw = HashMap<String, Int>(alive.size * 2)
            for (s in alive) pingByRaw[s.raw] = s.ping
            val out = ArrayList<Server>()
            for (d in dtos) {
                val inf = try { LinkParser.info(d.raw) } catch (_: Throwable) { continue } // کانفیگ خراب
                val ping = pingByRaw[d.raw] ?: -2 // فعلاً پاسخ نداد → ته لیست، نه حذف!
                out.add(Server(
                    id = stableId(d.raw),
                    name = d.name,
                    protocol = d.proto.ifBlank { inf.protocol },
                    raw = d.raw,
                    ping = ping,
                    label = d.label
                ))
            }
            out.sortWith(compareBy(
                { if (it.ping >= 0) 0 else 1 },          // پاسخ‌گوها اول
                { if (it.ping >= 0) it.ping else 0 },     // سریع‌ترها جلوتر
                { it.name }
            ))
            onProgress("${out.size} سرور رایگان آماده شد (پاسخ‌گو: ${alive.size})")
            return out
        } finally {
            fetching = false
        }
    }

    /** پینگ موازی لیست raw — خروجی: فقط سرورهای پاسخ‌گو */
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
