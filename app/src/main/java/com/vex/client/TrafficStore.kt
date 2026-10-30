package com.vex.client

import android.content.Context
import android.content.SharedPreferences

/**
 * شمارنده‌ی «مصرف کل برنامه» — مجموع همه‌ی جلسات از روز نصب،
 * تفکیک‌شده بر اساس «سرور رایگان» یا «سرور شخصی کاربر».
 *
 *  • هر ثانیه با آمار سرویس تغذیه می‌شود (دلتای جلسه محاسبه می‌شود، نه شمارش دوباره)؛
 *  • روی SharedPreferences می‌ماند و بعد از بستن برنامه هم ادامه پیدا می‌کند؛
 *  • دلتاها به‌صورت دوره‌ای به بک‌اند ربات هم گزارش می‌شوند تا مدیر از تلگرام
 *    مصرف کل کاربران (رایگان / شخصی) را ببیند.
 */
object TrafficStore {

    private lateinit var prefs: SharedPreferences

    /** خط‌کش جلسه: آخرین مجموع دانلود/آپلود دیده‌شده از سرویس */
    @Volatile private var lastDn = 0L
    @Volatile private var lastUp = 0L

    /** دلتای هنوز گزارش‌نشده به بک‌اند */
    @Volatile private var pendFreeUp = 0L
    @Volatile private var pendFreeDn = 0L
    @Volatile private var pendPrivUp = 0L
    @Volatile private var pendPrivDn = 0L

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
     */
    fun addSession(tdn: Long, tup: Long, isFree: Boolean) {
        if (!::prefs.isInitialized) return
        synchronized(this) {
            var dDn = tdn - lastDn
            var dUp = tup - lastUp
            if (dDn < 0) dDn = tdn
            if (dUp < 0) dUp = tup
            lastDn = tdn
            lastUp = tup
            if (dDn <= 0 && dUp <= 0) return
            val key = if (isFree) "free" else "priv"
            prefs.edit().putLong(key, prefs.getLong(key, 0L) + dDn + dUp).apply()
            if (isFree) { pendFreeUp += dUp; pendFreeDn += dDn }
            else { pendPrivUp += dUp; pendPrivDn += dDn }
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
            pendFreeUp = 0; pendFreeDn = 0; pendPrivUp = 0; pendPrivDn = 0
            lastDn = 0; lastUp = 0
        }
    }

    /**
     * ارسال دلتاهای جمع‌شده به بک‌اند ربات — در Thread جدا، بهترین تلاش.
     * اگر شبکه نبود، دلتاها برای دفعه‌ی بعد می‌مانند (از دست نمی‌روند).
     */
    fun flushAsync(ctx: Context) {
        Thread {
            val fUp: Long; val fDn: Long; val pUp: Long; val pDn: Long
            synchronized(this) {
                fUp = pendFreeUp; fDn = pendFreeDn; pUp = pendPrivUp; pDn = pendPrivDn
                pendFreeUp = 0; pendFreeDn = 0; pendPrivUp = 0; pendPrivDn = 0
            }
            if (fUp == 0L && fDn == 0L && pUp == 0L && pDn == 0L) return@Thread
            try {
                TelegramGate.reportTraffic(TelegramGate.deviceId(ctx), fUp, fDn, pUp, pDn)
            } catch (_: Throwable) {}
        }.start()
    }
}
