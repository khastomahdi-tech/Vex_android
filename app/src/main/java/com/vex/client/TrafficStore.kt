package com.vex.client

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * شمارنده‌ی «مصرف کل برنامه» — مجموع همه‌ی جلسات از روز نصب،
 * تفکیک‌شده بر اساس «سرور رایگان» یا «سرور شخصی کاربر».
 *
 *  • هر ثانیه با آمار سرویس تغذیه می‌شود (دلتای جلسه محاسبه می‌شود، نه شمارش دوباره)؛
 *  • روی SharedPreferences می‌ماند و بعد از بستن برنامه هم ادامه پیدا می‌کند؛
 *  • دوره‌ای به بک‌اند ربات گزارش می‌شود تا مدیر از تلگرام مصرف کل کاربران
 *    (رایگان / شخصی) را ببیند.
 *
 *  v3.15 — ارسال ضدخطا (واترمارک): به‌جای صف حافظه، «مصرف کل» و «مصرف گزارش‌شده»
 *  هر دو روی دیسک می‌مانند؛ هر flush فقط تفاوت این دو را می‌فرستد و واترمارک فقط
 *  بعد از پاسخ موفق جلو می‌رود. بنابراین قطع شبکه، بستن اپ یا کشته‌شدن پروسه
 *  هیچ دلتایی را از دست نمی‌دهد — دفعه‌ی بعد همان مبلغ دوباره می‌رود.
 */
object TrafficStore {

    private lateinit var prefs: SharedPreferences

    /** خط‌کش جلسه: آخرین مجموع دانلود/آپلود دیده‌شده از سرویس */
    @Volatile private var lastDn = 0L
    @Volatile private var lastUp = 0L

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext
            .getSharedPreferences("vex_traffic", Context.MODE_PRIVATE)
    }

    fun totalFree(): Long = if (::prefs.isInitialized) prefs.getLong("free", 0L) else 0L
    fun totalPrivate(): Long = if (::prefs.isInitialized) prefs.getLong("priv", 0L) else 0L
    fun totalAll(): Long = totalFree() + totalPrivate()

    /**
     * افزودن آمار زنده‌ی جلسه — خودش دلتا را حساب می‌کند؛
     * اگر سرویس ریست شده باشد (مقادیر کوچک‌تر از قبل) از همان مقدار جدید شروع می‌کند.
     *
     * srv = شناسه‌ی سرور رایگان در D1 ربات (>۰) — مصرف آن سرور جداگانه هم انباشته می‌شود
     * تا در پنل مدیریت، مصرفِ تفکیکیِ هر سرور دیده شود.
     */
    fun addSession(tdn: Long, tup: Long, isFree: Boolean, srv: Long = 0L) {
        if (!::prefs.isInitialized) return
        synchronized(this) {
            var dDn = tdn - lastDn
            var dUp = tup - lastUp
            if (dDn < 0) dDn = tdn
            if (dUp < 0) dUp = tup
            lastDn = tdn
            lastUp = tup
            if (dDn <= 0 && dUp <= 0) return
            val d = dDn + dUp
            val key = if (isFree) "free" else "priv"
            prefs.edit().putLong(key, prefs.getLong(key, 0L) + d).apply()
            if (isFree && srv > 0) {
                try {
                    val m = JSONObject(prefs.getString("srvmap", "{}") ?: "{}")
                    val skey = srv.toString()
                    m.put(skey, m.optLong(skey, 0L) + d)
                    prefs.edit().putString("srvmap", m.toString()).apply()
                } catch (_: Throwable) {}
            }
        }
    }

    /** شروع نشست اتصال جدید — خط‌کش جلسه از صفر */
    fun resetSession() {
        synchronized(this) { lastDn = 0; lastUp = 0 }
    }

    /** پاک کردن کل آمار مصرف (دکمه‌ی تنظیمات) */
    fun resetAll() {
        synchronized(this) {
            if (::prefs.isInitialized) prefs.edit().clear().apply()
            lastDn = 0; lastUp = 0
        }
    }

    /**
     * ارسال مصرف جمع‌نشده به بک‌اند ربات — در Thread جدا، بهترین تلاش.
     * دلتا = مصرف کل − مصرف گزارش‌شده؛ واترمارک فقط با پاسخ موفق جلو می‌رود،
     * پس هیچ مصرفی دوباره شمرده نمی‌شود و هیچ مصرفی گم نمی‌شود.
     */
    fun flushAsync(ctx: Context) {
        if (!::prefs.isInitialized) return
        Thread {
            try {
                val f = prefs.getLong("free", 0L)
                val p = prefs.getLong("priv", 0L)
                val rf = prefs.getLong("rep_free", 0L)
                val rp = prefs.getLong("rep_priv", 0L)
                val fDelta = (f - rf).coerceAtLeast(0)
                val pDelta = (p - rp).coerceAtLeast(0)
                val did = TelegramGate.deviceId(ctx)
                if (fDelta > 0 || pDelta > 0) {
                    val okF = if (fDelta > 0) TelegramGate.reportTrafficType(did, "free", fDelta) else true
                    val okP = if (pDelta > 0) TelegramGate.reportTrafficType(did, "private", pDelta) else true
                    if (okF || okP) {
                        prefs.edit()
                            .putLong("rep_free", if (okF) f else rf)
                            .putLong("rep_priv", if (okP) p else rp)
                            .apply()
                    }
                }
                // گزارش تفکیکی هر سرور رایگان — واترمارک جدا برای هر سرور؛ فقط پاسخ موفق جلو می‌رود
                try {
                    val m = JSONObject(prefs.getString("srvmap", "{}") ?: "{}")
                    val r = JSONObject(prefs.getString("srvrep", "{}") ?: "{}")
                    if (m.length() > 0) {
                        var changed = false
                        for (id in m.keys()) {
                            val tot = m.optLong(id, 0L)
                            val d = (tot - r.optLong(id, 0L)).coerceAtLeast(0)
                            if (d <= 0) continue
                            val sid = id.toLongOrNull() ?: continue
                            if (TelegramGate.reportTrafficType(did, "free", d, sid)) {
                                r.put(id, tot)
                                changed = true
                            }
                        }
                        if (changed) prefs.edit().putString("srvrep", r.toString()).apply()
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }.start()
    }
}
