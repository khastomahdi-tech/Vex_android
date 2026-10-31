package com.vex.client

import android.content.Context

/**
 * اشتراک کاربر — کد ۲۷ حرفیِ انگلیسی که از ربات تلگرام گرفته می‌شود (اعتبار ۱۰ روز).
 * کدهای ۱۶ رقمیِ نسخه‌های قبلی هم همچنان پذیرفته می‌شوند تا اشتراک‌های جاری قطع نشوند.
 *
 *  • کد فقط بعد از تأیید سرور (demonvpn.shop) ذخیره می‌شود.
 *  • هر کد فقط روی «یک دستگاه» فعال می‌شود؛ دستگاه دوم با همان کد رد می‌شود (device_taken).
 *  • اگر مدیر از ربات «حذف تمام نشست‌ها» را بزند، نشست‌ها پاک می‌شوند؛
 *    حداکثر تا یک دقیقه بعد (چک دوره‌ای) دستگاه بیرون انداخته می‌شود.
 *  • بعد از انقضا می‌شود از همان ربات کد اشتراک تازه گرفت.
 *  • اگر سرور در دسترس نباشد، وضعیت «باز» فرض می‌شود تا کاربر قفل نشود (مثل دروازه‌ی اصلی).
 */
object SubStore {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vex_sub", Context.MODE_PRIVATE)

    /** آیا این دستگاه با یک کد معتبر فعال شده است؟ (۲۷ حرف انگلیسی یا ۱۶ رقمِ قدیمی) */
    fun activated(ctx: Context): Boolean = try {
        valid(code(ctx))
    } catch (_: Throwable) { false }

    /** شکل معتبر کد: ۲۷ حرف انگلیسی (جدید) یا ۱۶ رقم (قدیمی) */
    fun valid(code: String): Boolean = code.length == 27 || code.length == 16

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
     * پاک‌سازی ورودی کاربر: حروف انگلیسی (بزرگ‌شده) + ارقام؛ ارقام فارسی/عربی → لاتین،
     * خط‌تیره و فاصله و هر کاراکتر دیگری حذف می‌شود؛
     * «XXXXXXXXX-XXXXXXXXX-XXXXXXXXX» و نسخه‌ی پیوسته‌اش هر دو به کدِ خالص تبدیل می‌شوند.
     */
    fun normalize(input: String): String = buildString {
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> append('0' + (ch - '۰'))
                in '٠'..'٩' -> append('0' + (ch - '٠'))
                in '0'..'9' -> append(ch)
                in 'a'..'z' -> append(ch.uppercaseChar())
                in 'A'..'Z' -> append(ch)
            }
        }
    }
}
