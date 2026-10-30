package com.vex.client

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * موتور «سرورهای رایگان» VEX — مثل ساب‌های عمومی:
 *  ۱) از چند منبع اشتراک عمومی معروف (GitHub raw) به‌صورت موازی دریافت می‌کند؛
 *  ۲) بدنه (base64 یا متنی) را باز کرده و همه‌ی کانفیگ‌های معتبر را درمی‌آورد؛
 *  ۳) از بین آن‌ها نمونه‌ای تصادفی برمی‌دارد تا هر بار تنوع داشته باشد؛
 *  ۴) هر کانفیگ را با پینگ TCP واقعی می‌سنجد (استخر Thread)؛
 *  ۵) فقط سرورهای سالم (پینگ موفق + پاسخ‌گو) برمی‌گردند، مرتب بر اساس پینگ؛
 *  ۶) نام همه یکسان و ساده است: «Vex Free Server 1..N» — بدون هیچ مشخصه‌ی لو‌رونده.
 *
 * کاربر نمی‌تواند این سرورها را ویرایش/حذف کند یا مشخصاتشان را ببیند؛
 * فقط لمس = انتخاب و اتصال، یا گرفتن پینگ تکی/گروهی.
 */
object FreeServers {

    /**
     * منابع اشتراک عمومی — چند منبع موازی تا اگر یکی در دسترس نبود، بقیه جواب بدهند.
     * برای هر مخزن هم نسخه‌ی raw گیت‌هاب و هم آینه‌های CDN (jsdelivr / fastly / gitmirror)
     * گذاشته‌شده تا حتی وقتی raw.githubusercontent فیلتر یا کند است، دریافت کار کند.
     */
    private val SOURCES = listOf(
        // آینه‌های jsdelivr (سریع و معمولاً بدون فیلتر)
        "https://cdn.jsdelivr.net/gh/Epodonios/v2ray-configs@main/All_Configs_Sub.txt",
        "https://fastly.jsdelivr.net/gh/Epodonios/v2ray-configs@main/All_Configs_Sub.txt",
        "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Configs@main/All_Configs_Sub.txt",
        "https://fastly.jsdelivr.net/gh/barry-far/V2ray-Configs@main/All_Configs_Sub.txt",
        "https://cdn.jsdelivr.net/gh/mahdibland/V2RayAggregator@master/sub/sub_merge.txt",
        // آینه‌ی gitmirror
        "https://raw.gitmirror.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
        "https://raw.gitmirror.com/barry-far/V2ray-Configs/main/All_Configs_Sub.txt",
        // منابع اصلی raw
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
        // منابع تجمیع‌کننده‌ی دیگر (تنوع بیشتر — اگر همه‌ی موارد بالا قطع بودند)
        "https://cdn.jsdelivr.net/gh/freefq/free@master/v2",
        "https://raw.githubusercontent.com/freefq/free/master/v2",
        "https://cdn.jsdelivr.net/gh/Pawdroid/Free-servers@main/sub",
        "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
        "https://cdn.jsdelivr.net/gh/aiboboxx/v2rayfree@main/vv",
        "https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/vv"
    )

    /** حداکثر کاندیدهایی که پینگ می‌گیریم (کنترل مصرف دیتا و باتری) */
    private const val MAX_CANDIDATES = 90

    /** حداکثر سرور سالمی که به کاربر نشان می‌دهیم */
    private const val MAX_ALIVE = 40

    /** تایم‌اوت پینگ سرورهای رایگان (ثانیه) — سرورهای فری معمولاً کندند */
    private const val PING_TIMEOUT_SEC = 3

    /** حداکثر پینگ قابل قبول (ms) — بیشتر از این یعنی عملاً بی‌فایده */
    private const val MAX_PING_MS = 2500

    /** آیا در حال دریافت از اینترنت هستیم؟ */
    @Volatile var fetching = false
        private set

    /**
     * دریافت + پارس + پینگ کامل.
     * onProgress برای پیام‌های مرحله‌ای (به Thread اصلی پست می‌شود توسط caller).
     * خروجی: لیست سرورهای سالم مرتب بر اساس پینگ (کم به زیاد).
     */
    fun fetchAliveServers(onProgress: (String) -> Unit): List<Server> {
        if (fetching) return emptyList()
        fetching = true
        try {
            onProgress("در حال دریافت از منابع رایگان…")

            // ---- ۱) دریافت موازی منابع ----
            val bodies = mutableListOf<String>()
            val threads = SOURCES.map { url ->
                Thread {
                    try {
                        val b = NetUtil.fetchSubscription(url)
                        if (b.isNotBlank()) synchronized(bodies) { bodies.add(b) }
                    } catch (_: Throwable) {}
                }.apply { start() }
            }
            // حداکثر ۴۰ ثانیه‌ی کل — هر منبعی که تا آن موقع رسید، همان می‌آید
            val deadline = System.currentTimeMillis() + 40_000
            for (t in threads) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                try { t.join(remain) } catch (_: InterruptedException) { break }
            }

            if (bodies.isEmpty()) {
                onProgress("هیچ منبعی در دسترس نبود — اینترنت را بررسی کن")
                return emptyList()
            }

            // ---- ۲) پارس کانفیگ‌ها + حذف تکراری ----
            onProgress("در حال تحلیل کانفیگ‌ها…")
            val seen = HashSet<String>()
            val candidates = ArrayList<String>()
            for (b in bodies) {
                for (raw in LinkParser.parseAll(b)) {
                    val key = raw.trim()
                    if (key in seen) continue
                    // فقط پروتکل‌های رایج و سبک — وایرگارد/JSON های فری منابع را رد کن
                    val proto = key.substringBefore("://").lowercase()
                    if (proto !in setOf("vless", "vmess", "trojan", "ss")) continue
                    seen.add(key)
                    candidates.add(key)
                }
            }
            if (candidates.isEmpty()) {
                onProgress("کانفیگ معتبری در منابع پیدا نشد")
                return emptyList()
            }

            // ---- ۳) نمونه‌گیری تصادفی (هر بار سرورهای تازه‌ای تست می‌شوند) ----
            candidates.shuffle()
            val sample = candidates.take(MAX_CANDIDATES)

            // ---- ۴) پینگ موازی با استخر Thread ----
            onProgress("پینگ ${sample.size} سرور رایگان…")
            val alive = pingAll(sample)

            if (alive.isEmpty()) {
                onProgress("هیچ سرور سالمی پیدا نشد — دوباره تلاش کن")
                return emptyList()
            }

            // ---- ۵) مرتب‌سازی + نام‌گذاری یکدست ----
            val sorted = alive.sortedBy { it.ping }.take(MAX_ALIVE)
            val out = ArrayList<Server>(sorted.size)
            for ((i, s) in sorted.withIndex()) {
                out.add(Server(
                    id = ServersStore.FREE_ID_BASE + i + 1,
                    name = "Vex Free Server ${i + 1}",
                    protocol = s.protocol,
                    raw = s.raw,
                    ping = s.ping
                ))
            }
            onProgress("${out.size} سرور رایگان سالم پیدا شد")
            return out
        } finally {
            fetching = false
        }
    }

    /** پینگ موازی لیست raw — خروجی: فقط سرورهای سالم */
    fun pingAll(raws: List<String>): List<Server> {
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
