package com.vex.client

import android.content.Context

/**
 * اشتراک کاربر — کد ۱۶ رقمی که از ربات تلگرام گرفته می‌شود (اعتبار ۱۰ روز).
 *
 *  • کد فقط بعد از تأیید سرور (demonvpn.shop) ذخیره می‌شود.
 *  • هر دستگاهی که با یک کد فعال شود یک «نشست» روی سرور می‌گیرد.
 *  • اگر مدیر از ربات «حذف تمام نشست‌ها» را بزند، همه‌ی نشست‌ها پاک می‌شوند؛
 *    در باز کردن بعدی برنامه (یا وسط کار در چک دوره‌ای) از کاربر دوباره کد خواسته می‌شود.
 *  • اگر سرور در دسترس نباشد، وضعیت «باز» فرض می‌شود تا کاربر قفل نشود (مثل دروازه‌ی اصلی).
 */
object SubStore {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vex_sub", Context.MODE_PRIVATE)

    /** آیا این دستگاه با یک کد معتبر فعال شده است؟ */
    fun activated(ctx: Context): Boolean = try {
        prefs(ctx).getString("code", "").orEmpty().length == 16
    } catch (_: Throwable) { false }

    fun code(ctx: Context): String = try {
        prefs(ctx).getString("code", "").orEmpty()
    } catch (_: Throwable) { "" }

    /** زمان انقضای اشتراک (ms) — از سرور */
    fun expires(ctx: Context): Long = try { prefs(ctx).getLong("expires", 0L) } catch (_: Throwable) { 0L }

    /** زمان ساخت کد (ms) — از سرور */
    fun activatedAt(ctx: Context): Long = try { prefs(ctx).getLong("activated_at", 0L) } catch (_: Throwable) { 0L }

    /** نام دستگاه ثبت‌شده روی سرور */
    fun device(ctx: Context): String = try {
        prefs(ctx).getString("device", "").orEmpty()
    } catch (_: Throwable) { "" }

    fun save(ctx: Context, code: String, createdAt: Long, expires: Long, device: String) {
        try {
            prefs(ctx).edit()
                .putString("code", code)
                .putLong("activated_at", createdAt)
                .putLong("expires", expires)
                .putString("device", device)
                .apply()
        } catch (_: Throwable) {}
    }

    /** خروج از اشتراک — بعد از کیک/انقضا یا ورود کد نامعتبر */
    fun clear(ctx: Context) {
        try { prefs(ctx).edit().clear().apply() } catch (_: Throwable) {}
    }

    /**
     * پاک‌سازی ورودی کاربر: ارقام فارسی/عربی → لاتین و حذف خط تیره و فاصله‌ها؛
     * «۱۲۳۴-۵۶۷۸...» و «12345678...» هر دو به ۱۶ رقمِ خالص تبدیل می‌شوند.
     */
    fun normalize(input: String): String = buildString {
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> append('0' + (ch - '۰'))
                in '٠'..'٩' -> append('0' + (ch - '٠'))
                in '0'..'9' -> append(ch)
            }
        }
    }
}
