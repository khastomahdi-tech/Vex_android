package com.vex.client

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * دروازه‌ی تلگرام Vex — سه کار:
 *
 *  ۱) hasInternet()          → بررسی واقعی دسترسی اینترنت (پیش از ورود به برنامه).
 *  ۲) gateStatus()           → کلید خاموش/روشن از راه دور با ربات تلگرام:
 *          مدیر به ربات دستور «/close» بدهد  → برنامه روی همه‌ی دستگاه‌ها قفل می‌شود.
 *          دستور «/open»                     → دوباره باز می‌شود.
 *          (اولین کسی که به ربات /start بدهد = مدیر شناخته می‌شود)
 *  ۳) notifyInstall()        → وقتی کسی برنامه را نصب/باز کند، مشخصات دستگاه
 *          به چت مدیر ربات ارسال می‌شود (فقط یک‌بار برای هر نسخه).
 *
 *  نکته: اگر تلگرام در دسترس نباشد (مثلاً فیلترینگ بدون پروکسی)، دروازه
 *  «باز» در نظر گرفته می‌شود تا برنامه برای کاربر قفل نشود — کلید فقط وقتی
 *  اجرا می‌شود که پاسخ واقعی ربات خوانده شود.
 */
object TelegramGate {

    private const val BOT_TOKEN = "9127495800:AAFzev2Q6NqyiXnG-jY4tuOgeyc4AO9dng0"

    /** وضعیت دروازه */
    const val OPEN = "open"
    const val CLOSED = "closed"

    /** شناسه‌ی چت مدیر — از getUpdates خوانده و کش می‌شود */
    @Volatile private var adminChatId: Long = 0L

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
                    setRequestProperty("User-Agent", "VEX-Client/3.10 (connectivity)")
                }
                if (c.responseCode in 200..399) return true
            } catch (_: Throwable) {
            } finally {
                try { c?.disconnect() } catch (_: Throwable) {}
            }
        }
        return false
    }

    // ------------------------------------------------ کلید خاموش/روشن ربات

    /**
     * وضعیت فعلی دروازه از ربات خوانده می‌شود.
     * خروجی: CLOSED فقط وقتی آخرین دستور مدیر «/close» باشد؛
     * در هر خطا/عدم‌دسترسی → OPEN (شکست باز).
     */
    fun gateStatus(): String {
        val body = apiCall("getUpdates?timeout=0&limit=100") ?: return OPEN
        return try {
            val arr = JSONObject(body).optJSONArray("result") ?: return OPEN

            // گذر اول: پیدا کردن مدیر (اولین چتی که /start فرستاده؛ در نبودش، اولین چت موجود)
            var startChat = 0L
            var firstChat = 0L
            for (i in 0 until arr.length()) {
                val up = arr.optJSONObject(i) ?: continue
                val m = up.optJSONObject("message") ?: continue
                val chat = m.optJSONObject("chat")?.optLong("id", 0L) ?: 0L
                if (chat == 0L) continue
                if (firstChat == 0L) firstChat = chat
                if (startChat == 0L && m.optString("text").trim() == "/start") startChat = chat
            }
            val admin = if (startChat != 0L) startChat else firstChat
            if (admin == 0L) return OPEN
            adminChatId = admin

            // گذر دوم: آخرین دستور /close یا /open از سمت مدیر
            var lastClose = Long.MIN_VALUE
            var lastOpen = Long.MIN_VALUE
            for (i in 0 until arr.length()) {
                val up = arr.optJSONObject(i) ?: continue
                val uid = up.optLong("update_id", Long.MIN_VALUE)
                val m = up.optJSONObject("message") ?: continue
                val chat = m.optJSONObject("chat")?.optLong("id", 0L) ?: 0L
                if (chat != admin) continue
                val text = m.optString("text").trim().lowercase(Locale.US)
                if (text.startsWith("/close")) lastClose = uid
                else if (text.startsWith("/open")) lastOpen = uid
            }
            if (lastClose != Long.MIN_VALUE && lastClose >= lastOpen) CLOSED else OPEN
        } catch (_: Throwable) {
            OPEN
        }
    }

    // ------------------------------------------------ اطلاع‌رسانی نصب/ورود

    /**
     * اطلاع‌رسانی «نصب/اولین ورود» به مدیر ربات — فقط یک‌بار برای هر نسخه‌ی برنامه.
     * اگر ارسال ناموفق باشد (اینترنت/تلگرام قطع)، دفعه‌ی بعد دوباره تلاش می‌شود.
     */
    fun notifyInstall(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("vex_gate", Context.MODE_PRIVATE)
            val ver = pkgVersionCode(ctx)
            val key = "notified_v$ver"
            if (prefs.getBoolean(key, false)) return
            val chat = adminChatId
            if (chat == 0L) return
            val text = "🔔 ورود/نصب جدید Vex Client\n" +
                    "📱 گوشی: ${deviceName()}\n" +
                    "🤖 سیستم: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "🏷 نسخه: ${pkgVersionName(ctx)}\n" +
                    "🌐 زبان: ${Locale.getDefault().toString()}\n" +
                    "🕐 زمان: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"
            if (sendMessage(chat, text)) {
                prefs.edit().putBoolean(key, true).apply()
            }
        } catch (_: Throwable) {}
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
            val chat = adminChatId
            if (chat == 0L) return
            if (state == CLOSED) {
                sendMessage(chat, "🔒 Vex روی دستگاه «${deviceName()}» قفل شد (دستور /close اجرا شد)")
            } else if (prev == CLOSED && state == OPEN) {
                sendMessage(chat, "🔓 Vex روی دستگاه «${deviceName()}» باز شد (دستور /open اجرا شد)")
            }
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------ ابزار داخلی

    private fun sendMessage(chat: Long, text: String): Boolean {
        val enc = URLEncoder.encode(text, "UTF-8")
        val body = apiCall("sendMessage?chat_id=$chat&text=$enc&disable_web_page_preview=true")
            ?: return false
        return try { JSONObject(body).optBoolean("ok", false) } catch (_: Throwable) { false }
    }

    /** یک GET ساده به Bot API — در هر خطا null (شکست باز در سطح بالاتر) */
    private fun apiCall(method: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://api.telegram.org/bot$BOT_TOKEN/$method").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                useCaches = false
                setRequestProperty("User-Agent", "VEX-Client/3.10 (gate)")
            }
            if (c.responseCode in 200..299) {
                c.inputStream?.bufferedReader()?.readText()
            } else null
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
