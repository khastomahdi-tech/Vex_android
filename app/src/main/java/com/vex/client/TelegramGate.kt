package com.vex.client

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * دروازه‌ی Vex — نسخه‌ی کلادفلر:
 *
 *  بک‌اند ربات تلگرام روی Cloudflare Pages (vex-gate.pages.dev) + دیتابیس D1 اجرا می‌شود:
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

    /** بک‌اند کلادفلر (Pages) + کلید احراز برنامه */
    private const val GATE_URL = "https://vex-gate.pages.dev"
    private const val APP_KEY = "vexk_8c3b1e664023f4df081d8dbb"

    /** وضعیت دروازه */
    const val OPEN = "open"
    const val CLOSED = "closed"

    /** وضعیت دروازه + دلیل بسته‌بودن (متن مدیر در ربات) */
    data class GateInfo(val gate: String, val reason: String?)

    /** سرور رایگانِ مدیریت‌شده از ربات */
    data class FreeServerDto(val id: Long, val name: String, val proto: String, val raw: String)

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
                    setRequestProperty("User-Agent", "VEX-Client/3.14 (connectivity)")
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
            if (o.optBoolean("ok") && o.optString("gate") == CLOSED)
                GateInfo(CLOSED, o.optString("reason", null as String?))
            else GateInfo(OPEN, null)
        } catch (_: Throwable) {
            GateInfo(OPEN, null)
        }
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
                    GateInfo(o.optString("gate", OPEN), o.optString("reason", null as String?))
                else null
            } catch (_: Throwable) { null }
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * گزارش دلتای مصرف ترافیک به بک‌اند ربات (D1) — تفکیک سرور رایگان / شخصی.
     * ربات مدیر مجموع مصرف همه‌ی کاربران را همین‌جا می‌بیند. بهترین تلاش؛ بی‌صدا.
     */
    fun reportTraffic(did: String, freeUp: Long, freeDn: Long, privUp: Long, privDn: Long) {
        try {
            if (freeUp > 0 || freeDn > 0) {
                post("/v1/traffic", JSONObject().apply {
                    put("did", did); put("type", "free")
                    put("up", freeUp); put("down", freeDn)
                })
            }
            if (privUp > 0 || privDn > 0) {
                post("/v1/traffic", JSONObject().apply {
                    put("did", did); put("type", "private")
                    put("up", privUp); put("down", privDn)
                })
            }
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------ سرورهای رایگان ربات

    /**
     * دریافت سرورهای رایگان از بک‌اند ربات (D1) — فقط مدیر از طریق ربات
     * می‌تواند سرور اضافه یا حذف کند؛ اینجا فقط فهرستِ فقط‌خواندنی می‌آید.
     */
    fun fetchFreeServers(): List<FreeServerDto> {
        val body = get("/v1/freeservers") ?: return emptyList()
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
                    raw = raw
                ))
            }
            out
        } catch (_: Throwable) {
            emptyList()
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

    // ------------------------------------------------ ابزار شبکه

    private fun http(c: HttpURLConnection) {
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.useCaches = false
        c.setRequestProperty("X-Vex-Key", APP_KEY)
        c.setRequestProperty("User-Agent", "VEX-Client/3.14 (gate)")
    }

    private fun get(path: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$GATE_URL$path").openConnection() as HttpURLConnection).apply {
                http(this)
            }
            if (c.responseCode in 200..299) c.inputStream?.bufferedReader()?.readText() else null
        } catch (_: Throwable) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun post(path: String, body: JSONObject): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$GATE_URL$path").openConnection() as HttpURLConnection).apply {
                http(this)
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
}
