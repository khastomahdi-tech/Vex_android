package com.vex.client

import android.content.Context

/**
 * اشتراک کاربر — توکن ۳۰ حرفیِ انگلیسی که از ربات تلگرام گرفته می‌شود (اعتبار ۱۰ روز).
 * کدهای ۲۷ حرفیِ قبلی هم پذیرفته می‌شوند (سازگاری با اشتراک‌های جاری)؛
 * سیستم قدیمی‌تر (کدهای ۱۶ رقمی و اشتراک‌گذاری) کاملاً باطل است.
 *
 *  • توکن فقط بعد از تأیید سرور (demonvpn.shop) ذخیره می‌شود.
 *  • هر توکن فقط روی «یک دستگاه» فعال می‌شود؛ دستگاه دوم با همان توکن رد می‌شود (device_taken).
 *  • اگر مدیر از ربات «حذف تمام نشست‌ها» را بزند، نشست‌ها پاک می‌شوند؛
 *    حداکثر تا یک دقیقه بعد (چک دوره‌ای) دستگاه بیرون انداخته می‌شود.
 *  • بعد از انقضا می‌شود از همان ربات توکن تازه گرفت.
 *  • اگر سرور در دسترس نباشد، وضعیت «باز» فرض می‌شود تا کاربر قفل نشود (مثل دروازه‌ی اصلی).
 */
object SubStore {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vex_sub", Context.MODE_PRIVATE)

    /** آیا این دستگاه با یک توکن معتبر فعال شده است؟ (توکن ۳۰ حرفی / کد ۲۷ حرفیِ قدیمی) */
    fun activated(ctx: Context): Boolean = try {
        valid(code(ctx))
    } catch (_: Throwable) { false }

    /** شکل معتبر: توکن ۳۰ حرفیِ انگلیسی (جدید) یا کد ۲۷ حرفیِ قبلی — کدهای ۱۶ رقمیِ قدیمی باطل شده‌اند */
    fun valid(code: String): Boolean {
        if (code.length != 30 && code.length != 27) return false
        for (ch in code) if (ch !in 'A'..'Z') return false
        return true
    }

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
