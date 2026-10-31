package com.vex.client

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * دروازه‌ی Vex — نسخه‌ی کلادفلر:
 *
 *  بک‌اند ربات تلگرام روی Cloudflare Pages (دامنه‌ی شخصی demonvpn.shop + دامنه‌ی پیش‌فرض
 *  vex-gate.pages.dev) + دیتابیس D1 اجرا می‌شود؛ اول دامنه‌ی شخصی امتحان می‌شود —
 *  چون pages.dev در ایران SNI-فیلتر است و دامنه‌ی شخصی در دسترس کاربر بدون فیلترشکن هست.
 *
 *  ۱) hasInternet()   → بررسی واقعی دسترسی اینترنت (پیش از ورود به برنامه).
 *  ۲) gateStatus()    → وضعیت قفل/باز از سرور کلادفلر خوانده می‌شود:
 *          مدیر در ربات «/close» بدهد  → برنامه روی همه‌ی دستگاه‌ها قفل می‌شود.
 *          «/open»                     → دوباره باز می‌شود.
 *          (اولین کسی که به ربات /start بدهد = مدیر شناخته می‌شود)
 *  ۳) notifyInstall() → نصب/ورود هر دستگاه در D1 ثبت و به چت مدیر پیام می‌رود
 *          (فقط یک‌بار برای هر نسخه؛ پاسخ همان درخواست، وضعیت دروازه را هم برمی‌گرداند).
 *  ۴) ackIfChanged()  → وقتی قفل/باز شدن واقعاً روی دستگاه اجرا شد، به مدیر خبر می‌دهد.
 *
 *  نکته: اگر سرور در دسترس نباشد، دروازه «باز» در نظر گرفته می‌شود تا برنامه
 *  برای کاربر قفل نشود — قفل فقط وقتی اجرا می‌شود که پاسخ واقعی سرور خوانده شود.
 */
object TelegramGate {

    /** بک‌اند کلادفلر (Pages): دامنه‌ی شخصی اول، pages.dev پشتیبان + کلید احراز برنامه */
    private val GATE_HOSTS = arrayOf(
        "https://demonvpn.shop",        // دامنه‌ی شخصی — بدون فیلترینگ
        "https://vex-gate.pages.dev"    // دامنه‌ی پیش‌فرض Pages (پشتیبان)
    )
    private const val APP_KEY = "vexk_8c3b1e664023f4df081d8dbb"

    /**
     * کانال پشتیبان گیت‌هاب — چون pages.dev در ایران فیلتر است، کاربرِ بدون فیلترشکن
     * به دروازه‌ی مستقیم نمی‌رسد و سرورهای رایگان/اعلان‌ها/وضعیت قفل هیچ‌کدام نمی‌آیند.
     * سرور با هر تغییر در ربات، اسنپ‌شات JSON را در پوشه‌ی gate ریپو می‌نویسد
     * (state، freeservers، notifications) و اینجا وقتی مستقیم نشد از
     * raw.githubusercontent.com (و بعد api.github.com) می‌خوانیم —
     * گیت‌هاب از ایران در دسترس است.
     */
    private const val GH_RAW = "https://raw.githubusercontent.com/khastomahdi-tech/Vex_android/main/gate"
    private const val GH_API = "https://api.github.com/repos/khastomahdi-tech/Vex_android/contents/gate"

    /** وضعیت دروازه */
    const val OPEN = "open"
    const val CLOSED = "closed"

    /** وضعیت دروازه + دلیل بسته‌بودن (متن مدیر در ربات) + حداقل نسخه‌ی مجاز + نسخه‌های بسته‌شده */
    data class GateInfo(
        val gate: String,
        val reason: String?,
        val minVer: Int = 0,
        /** نسخه‌های بسته‌شده توسط مدیر — اگر نسخه‌ی خود برنامه در این لیست باشد، ورود قفل می‌شود */
        val blockedVersions: List<String> = emptyList()
    )

    /** سرور رایگانِ مدیریت‌شده از ربات */
    data class FreeServerDto(val id: Long, val name: String, val proto: String, val raw: String, val label: String = "")

    /** اعلان همگانی مدیر (از ربات /notify) */
    data class NotifDto(val id: Long, val title: String, val body: String)

    // ------------------------------------------------ بررسی اینترنت

    /**
     * آیا اینترنت واقعاً در دسترس است؟
     * چند مقصد بی‌طرف امتحان می‌شود (برخی در برخی کشورها فیلترند؛
     * به همین دلیل سه مقصد مستقل پشت سر هم تست می‌شود).
     */
    fun hasInternet(): Boolean {
        val probes = arrayOf(
            "https://cp.cloudflare.com/generate_204",
            "https://www.msftconnecttest.com/connecttest.txt",
            "https://www.wikipedia.org"
        )
        for (u in probes) {
            var c: HttpURLConnection? = null
            try {
                c = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", "VEX-Client/3.18 (connectivity)")
                }
                if (c.responseCode in 200..399) return true
            } catch (_: Throwable) {
            } finally {
                try { c?.disconnect() } catch (_: Throwable) {}
            }
        }
        return false
    }

    // ------------------------------------------------ کلید خاموش/روشن

    /**
     * وضعیت فعلی دروازه + دلیل بسته‌بودن از سرور کلادفلر خوانده می‌شود.
     * خروجی: CLOSED فقط وقتی مدیر در ربات /close داده باشد؛
     * در هر خطا/عدم‌دسترسی → OPEN (شکست باز).
     */
    fun gateInfo(): GateInfo {
        val body = get("/v1/state") ?: return GateInfo(OPEN, null)
        return try {
            val o = JSONObject(body)
            rememberBotUrl(o)
            val mv = o.optInt("min_ver", 0)
            val blocked = parseBlocked(o)
            if (o.optBoolean("ok") && o.optString("gate") == CLOSED) {
                val ts = o.optLong("ts", 0L)
                // پاسخ مستقیم سرور ts ندارد (صفر) و همیشه معتبر است؛ فایل پشتیبان گیت‌هاب
                // اگر بیش از ۱۵ دقیقه کهنه باشد قابل‌اعتماد نیست — دروازه باز فرض می‌شود
                // تا هیچ کاربری با اسنپ‌شات قدیمی بی‌دلیل قفل نشود.
                if (ts in 1..(System.currentTimeMillis() - 15 * 60_000L)) return GateInfo(OPEN, null, mv, blocked)
                GateInfo(CLOSED, o.optString("reason", null as String?), mv, blocked)
            } else GateInfo(OPEN, null, mv, blocked)
        } catch (_: Throwable) {
            GateInfo(OPEN, null)
        }
    }

    /** فهرست نسخه‌های بسته از پاسخ سرور/اسنپ‌شات — رشته‌های تمیز و کوتاه */
    private fun parseBlocked(o: JSONObject): List<String> {
        val out = ArrayList<String>(2)
        try {
            val arr = o.optJSONArray("blocked") ?: return out
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim().removePrefix("v").removePrefix("V").take(24)
                if (v.isNotEmpty() && !out.contains(v)) out.add(v)
            }
        } catch (_: Throwable) {}
        return out
    }

    /** آیا نسخه‌ی فعلی برنامه توسط مدیر بسته شده است؟ */
    fun selfVersionIsBlocked(info: GateInfo, ctx: Context): Boolean {
        if (info.blockedVersions.isEmpty()) return false
        val v = selfVersionName(ctx)
        return v.isNotEmpty() && info.blockedVersions.contains(v)
    }

    /** فقط وضعیت (سازگاری با کدهای قبلی) */
    fun gateStatus(): String = gateInfo().gate

    // ------------------------------------------------ اطلاع‌رسانی نصب/ورود

    /**
     * ثبت ورود/نصب روی سرور (D1) — هر بار اجرای برنامه گزارش می‌شود؛
     * مدیر فقط برای «کاربر جدید» یا «آپدیت نسخه» پیام می‌گیرد (تصمیم سمت سرور).
     * سرور IP، کشور، مدل گوشی و نسخه‌ی اندروید را خودش از درخواست درمی‌آورد.
     * پاسخ همان درخواست، وضعیت دروازه + دلیل را هم برمی‌گرداند (اگر خطا باشد null).
     */
    fun notifyInstall(ctx: Context): GateInfo? {
        try {
            val body = JSONObject().apply {
                put("did", deviceId(ctx))
                put("model", deviceName())
                put("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("ver", pkgVersionName(ctx))
                put("lang", Locale.getDefault().toString())
                put("event", "open")
            }
            val res = post("/v1/report", body) ?: return null
            return try {
                val o = JSONObject(res)
                if (o.optBoolean("ok"))
                    GateInfo(o.optString("gate", OPEN), o.optString("reason", null as String?), o.optInt("min_ver", 0), parseBlocked(o))
                else null
            } catch (_: Throwable) { null }
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * گزارش مصرف به بک‌اند ربات (D1) — یک دسته در هر درخواست.
     * خروجی true فقط وقتی پاسخ موفق بود (برای واترمارک TrafficStore).
     */
    fun reportTrafficType(did: String, type: String, bytes: Long): Boolean {
        return try {
            if (bytes <= 0) return true
            val res = post("/v1/traffic", JSONObject().apply {
                put("did", did); put("type", type)
                put("up", 0); put("down", bytes)
            })
            res != null && try { JSONObject(res).optBoolean("ok") } catch (_: Throwable) { false }
        } catch (_: Throwable) { false }
    }

    // ------------------------------------------------ سرورهای رایگان ربات

    /**
     * دریافت سرورهای رایگان از بک‌اند ربات (D1) — فقط مدیر از طریق ربات
     * می‌تواند سرور اضافه یا حذف کند؛ اینجا فقط فهرستِ فقط‌خواندنی می‌آید.
     *
     *  خروجی null → شبکه/سرور در دسترس نبود (لیست فعلی نباید پاک شود!)
     *  خروجی خالی → سرور در دسترس بود ولی واقعاً هیچ سروری ثبت نشده
     */
    fun fetchFreeServers(): List<FreeServerDto>? {
        val body = get("/v1/freeservers") ?: return null
        return try {
            val o = JSONObject(body)
            if (!o.optBoolean("ok")) return emptyList()
            val arr = o.optJSONArray("servers") ?: return emptyList()
            val out = ArrayList<FreeServerDto>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val raw = s.optString("raw", "")
                if (raw.isBlank()) continue
                out.add(FreeServerDto(
                    id = s.optLong("id", 0L),
                    name = s.optString("name", "سرور رایگان"),
                    proto = s.optString("proto", raw.substringBefore("://")),
                    raw = raw,
                    label = s.optString("label", "")
                ))
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * اعلان‌های همگانی جدید بعد از since (شناسه‌ی آخرین اعلان دیده‌شده).
     * خروجی null → شبکه/سرور نبود (شناسه‌ی آخرین نباید جلو برود)
     */
    fun fetchNotifications(since: Long): List<NotifDto>? {
        // مسیر مستقیم since را سرور فیلتر می‌کند؛ مسیر پشتیبان همه را می‌دهد و محلی فیلتر می‌شود
        val body = get("/v1/notifications?since=$since") ?: return null
        return try {
            val o = JSONObject(body)
            if (!o.optBoolean("ok")) return emptyList()
            val arr = o.optJSONArray("list") ?: return emptyList()
            val out = ArrayList<NotifDto>(arr.length())
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                val nid = n.optLong("id", 0L)
                if (nid <= since) continue // مسیر پشتیبان همه‌ی اعلان‌ها را می‌دهد — محلی فیلتر می‌کنیم
                val t = n.optString("title", "").trim()
                if (t.isEmpty()) continue
                out.add(NotifDto(nid, t, n.optString("body", "")))
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * اگر وضعیت دروازه نسبت به دفعه‌ی قبل عوض شده باشد، به مدیر خبر می‌دهد.
     * مثلاً وقتی /close صادر شده و دستگاه واقعاً قفل شد.
     */
    fun ackIfChanged(state: String, ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("vex_gate", Context.MODE_PRIVATE)
            val prev = prefs.getString("gate_state", "") ?: ""
            if (prev == state) return
            prefs.edit().putString("gate_state", state).apply()
            val body = JSONObject().apply {
                put("device", deviceName())
                put("state", state)
            }
            post("/v1/ack", body)
        } catch (_: Throwable) {}
    }

    /** شناسه‌ی یکتای دستگاه — تصادفی و محلی (بدون دسترسی به شناسه‌های سیستمی) */
    fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("vex_gate", Context.MODE_PRIVATE)
        var did = prefs.getString("did", null)
        if (did == null) {
            did = UUID.randomUUID().toString()
            prefs.edit().putString("did", did).apply()
        }
        return did
    }

    // ------------------------------------------------ اشتراک (کد ۲۷ حرفیِ انگلیسی از ربات)

    /**
     * لینک رباتی که دکمه‌ی «ورود به ربات» باز می‌کند — از سرور (bot_url در پاسخ دروازه)
     * تازه می‌شود؛ اگر سرور چیزی نفرستاد، همین مقدار پیش‌فرض استفاده می‌شود.
     */
    var lastBotUrl: String = "https://t.me/Appmasegbot"
        private set

    private fun rememberBotUrl(o: JSONObject) {
        try {
            val u = o.optString("bot_url", "").trim()
            if (u.startsWith("https://t.me/") || u.startsWith("http://t.me/") || u.startsWith("tg://")) {
                lastBotUrl = u
            }
        } catch (_: Throwable) {}
    }

    /**
     * نتیجه‌ی فعال‌سازی/بررسی اشتراک.
     * ok=false با یک دلیل مشخص می‌آید (kicked / expired / not_found / bad_code)؛
     * اگر پاسخی از سرور نیامد (خطای شبکه) error=null برمی‌گردد — یعنی fail-open:
     * نبودِ سرور نباید کاربر را قفل کند، فقط ردِ صریحِ سرور قفل می‌کند.
     */
    data class SubInfo(
        val ok: Boolean,
        val error: String? = null,
        val code: String = "",
        val createdAt: Long = 0L,
        val expires: Long = 0L,
        val serverTime: Long = 0L,
        val device: String = "",
        val sessions: Int = 0
    ) {
        val kicked: Boolean get() = error == "kicked"
        val expired: Boolean get() = error == "expired"
        val notFound: Boolean get() = error == "not_found" || error == "revoked"
        val badCode: Boolean get() = error == "bad_code" || error == "bad_request"
        /** این کد قبلاً روی دستگاه دیگری فعال شده است (هر کد فقط یک دستگاه) */
        val deviceTaken: Boolean get() = error == "device_taken"
        /** آیا این یک ردِ صریح سرور است (نه خطای شبکه)؟ */
        val rejected: Boolean get() = error != null
    }

    private fun parseSub(res: String?): SubInfo {
        if (res == null) return SubInfo(false, null)
        return try {
            val o = JSONObject(res)
            rememberBotUrl(o)
            SubInfo(
                ok = o.optBoolean("ok"),
                error = if (o.has("error")) o.optString("error") else null,
                code = o.optString("code", ""),
                createdAt = o.optLong("created", 0L),
                expires = o.optLong("expires", 0L),
                serverTime = o.optLong("server_time", 0L),
                device = o.optString("device", ""),
                sessions = o.optInt("sessions", 0)
            )
        } catch (_: Throwable) {
            SubInfo(false, null)
        }
    }

    /** فعال‌سازی کد اشتراک برای همین دستگاه (نشست ثبت می‌شود) */
    fun subActivate(code: String, device: String, did: String): SubInfo {
        val res = post("/v1/sub/activate", JSONObject().apply {
            put("did", did); put("code", code); put("device", device)
        })
        return parseSub(res)
    }

    /** بررسی وضعیت اشتراک — kicked یعنی مدیر «حذف تمام نشست‌ها» را زده است */
    fun subStatus(code: String, did: String): SubInfo {
        val res = post("/v1/sub/status", JSONObject().apply {
            put("did", did); put("code", code)
        })
        return parseSub(res)
    }

    // ------------------------------------------------ ابزار شبکه

    /** فایل پشتیبانِ هر مسیر — null یعنی فقط مستقیم (POSTها پشتیبان ندارند) */
    private fun ghFileFor(path: String): String? = when {
        path.startsWith("/v1/freeservers") -> "freeservers.json"
        path.startsWith("/v1/state") -> "state.json"
        path.startsWith("/v1/notifications") -> "notifications.json"
        else -> null
    }

    /**
     * GET یک مسیر: اول دروازه‌ی مستقیم؛ اگر نشد فایل اسنپ‌شات گیت‌هاب
     * (raw، بعد Contents API که base64 برمی‌گرداند).
     */
    private fun get(path: String): String? {
        for (host in GATE_HOSTS) fetchFrom("$host$path", withKey = true)?.let { return it }
        val f = ghFileFor(path) ?: return null
        fetchFrom("$GH_RAW/$f", withKey = false)?.let { return it }
        fetchFrom("$GH_API/$f", withKey = false)?.let { resp ->
            return try {
                val o = JSONObject(resp)
                if (o.optString("encoding") == "base64")
                    String(Base64.decode(o.optString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                else null
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun http(c: HttpURLConnection, withKey: Boolean) {
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.useCaches = false
        if (withKey) c.setRequestProperty("X-Vex-Key", APP_KEY)
        c.setRequestProperty("User-Agent", "VEX-Client/3.18 (gate)")
    }

    private fun fetchFrom(url: String, withKey: Boolean): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                http(this, withKey)
            }
            if (c.responseCode in 200..299) c.inputStream?.bufferedReader()?.readText() else null
        } catch (_: Throwable) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** POST به دروازه: اول دامنه‌ی شخصی، اگر نشد pages.dev */
    private fun post(path: String, body: JSONObject): String? {
        for (host in GATE_HOSTS) {
            postOnce("$host$path", body)?.let { return it }
        }
        return null
    }

    private fun postOnce(url: String, body: JSONObject): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                http(this, true)
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val w = OutputStreamWriter(c.outputStream, Charsets.UTF_8)
            w.write(body.toString()); w.flush(); w.close()
            if (c.responseCode in 200..299) c.inputStream?.bufferedReader()?.readText() else null
        } catch (_: Throwable) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun pkgVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    private fun pkgVersionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Throwable) { 0L }

    /** versionCode خودِ برنامه — برای مقایسه با min_ver مدیر (به‌روزرسانی اجباری) */
    fun selfVersionCode(ctx: Context): Long = pkgVersionCode(ctx)

    /** نام نسخه‌ی خود برنامه (مثل «1») — برای چک نسخه‌های بسته‌شده توسط مدیر */
    fun selfVersionName(ctx: Context): String = pkgVersionName(ctx)
}
