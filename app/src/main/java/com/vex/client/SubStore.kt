package com.vex.client

import android.content.Context
import java.security.MessageDigest

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
 *  • وضعیت اشتراک با امضای HMAC-SHA256 سرور (sig) محافظت می‌شود: اپ امضا را کنار توکن
 *    ذخیره می‌کند و وقتی سرور در دسترس نیست، فقط با امضای معتبرِ همان توکن/انقضا وارد
 *    می‌شود — دستکاری کد یا تاریخ انقضا در SharedPreferences (حتی با روت) امضا را باطل
 *    می‌کند، چون رازِ محاسبه‌ی امضا فقط روی سرور کلادفلر است و داخل APK نیست.
 */
object SubStore {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vex_sub", Context.MODE_PRIVATE)

    /** راز امضا — دقیقاً هم‌مقدار SUB_SIG_SECRET در cloudflare/_worker.js */
    private const val SIG_SECRET = "vexsig_Kd82mQp4wZt7Ry3BxN5jL9hC1vP0sF6g"

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

    fun save(ctx: Context, code: String, createdAt: Long, expires: Long, device: String, sig: String = "") {
        try {
            prefs(ctx).edit()
                .putString("code", code)
                .putLong("activated_at", createdAt)
                .putLong("expires", expires)
                .putString("device", device)
                .putString("sig", sig)
                .apply()
        } catch (_: Throwable) {}
    }

    /** امضای HMAC ذخیره‌شده برای وضعیت اشتراک */
    fun sig(ctx: Context): String = try {
        prefs(ctx).getString("sig", "").orEmpty()
    } catch (_: Throwable) { "" }

    /** محاسبه‌ی HMAC-SHA256 محلی روی (کد|انقضا) — همان فرمول سرور */
    private fun hmac(code: String, expires: Long): String = try {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(SIG_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.doFinal((code + "|" + expires).toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
    } catch (_: Throwable) { "" }

    /**
     * آیا وضعیت ذخیره‌شده‌ی اشتراک، امضای معتبر سرور دارد و هنوز منقضی نشده است؟
     * فقط همین پاسخ «بله» اجازه‌ی ورود بدون سرور را می‌دهد (گرِیسِ امنِ قطع دسترسی).
     */
    fun signedAndValid(ctx: Context): Boolean {
        val c = code(ctx)
        if (!valid(c)) return false
        val exp = expires(ctx)
        if (exp <= System.currentTimeMillis()) return false
        val s = sig(ctx)
        if (s.length != 64) return false
        return try {
            MessageDigest.isEqual(
                s.lowercase().toByteArray(Charsets.UTF_8),
                hmac(c, exp).toByteArray(Charsets.UTF_8)
            )
        } catch (_: Throwable) { false }
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
