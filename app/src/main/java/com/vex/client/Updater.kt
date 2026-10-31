package com.vex.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * بروزرسانی خودکار درون‌برنامه‌ای Vex:
 *
 *  ۱) check()     → نسخه‌ی فعلی آینه از /v1/app/latest خوانده می‌شود {code, ver, name, size}
 *  ۲) download()  → APK مستقیم از آینه‌ی خود گیت (/apk) دانلود و درصد پیشرفت برگردانده می‌شود
 *  ۳) install()   → نصب از طریق FileProvider + مجوز «نصب برنامه‌های ناشناس»
 *
 * چون ترافیک خود برنامه از تونل VPN خارج می‌شود (addDisallowedApplication در openTun)،
 * دانلود بروزرسانی حتی وقتی VPN درست کار نمی‌کند مستقیم و مطمئن انجام می‌شود.
 * دانلود کامل روی کش ذخیره می‌ماند؛ اگر کاربر اجازه‌ی نصب نداشته باشد، بعد از دادن
 * اجازه در تنظیمات، روی برگشتن به برنامه نصب به‌صورت خودکار ادامه پیدا می‌کند.
 */
object Updater {

    /** اطلاعات آخرین نسخه‌ی منتشرشده */
    data class Latest(val code: Long, val ver: String, val name: String, val size: Long)

    private const val PREF = "vex_update"
    private const val APK_FILE = "vex_update.apk"
    private val HOSTS = arrayOf("https://demonvpn.shop", "https://vex-gate.pages.dev")

    /** آیا فایل دانلودشده آماده‌ی نصب است؟ */
    fun pendingInstall(ctx: Context): Boolean {
        val f = File(ctx.cacheDir, APK_FILE)
        return f.length() > 1_000_000
    }

    /** آیا دانلود دیگری در جریان است؟ (جلوگیری از دو دانلود هم‌زمان) */
    @Volatile private var busy = false
    fun downloadingFile(): Boolean = busy

    /**
     * بررسی نسخه‌ی جدید — کش ۳۰ دقیقه‌ای تا هر بار ورود به برنامه یک درخواست سبک باشد.
     * خروجی null یعنی سرور در دسترس نبود (در این حالت بنر بر اساس min_ver مدیر کار می‌کند).
     */
    fun check(ctx: Context): Latest? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("chk_ts", 0L) < 30 * 60_000L) {
            val c = p.getLong("chk_code", 0L)
            if (c > 0) return Latest(c, p.getString("chk_ver", "") ?: "", p.getString("chk_name", "") ?: "", p.getLong("chk_size", 0L))
        }
        val raw = TelegramGate.appLatestRaw() ?: return cached(p)
        return try {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return cached(p)
            val l = Latest(
                code = o.optLong("code", 0L),
                ver = o.optString("ver", ""),
                name = o.optString("name", ""),
                size = o.optLong("size", 0L)
            )
            if (l.code > 0) {
                p.edit()
                    .putLong("chk_code", l.code).putString("chk_ver", l.ver)
                    .putString("chk_name", l.name).putLong("chk_size", l.size)
                    .putLong("chk_ts", now).apply()
                l
            } else cached(p)
        } catch (_: Throwable) {
            cached(p)
        }
    }

    private fun cached(p: android.content.SharedPreferences): Latest? {
        val c = p.getLong("chk_code", 0L)
        if (c <= 0) return null
        return Latest(c, p.getString("chk_ver", "") ?: "", p.getString("chk_name", "") ?: "", p.getLong("chk_size", 0L))
    }

    /**
     * دانلود APK از آینه — رویProgress (درصد ۰ تا ۱۰۰) هر تغییر برگردانده می‌شود.
     * خروجی null یعنی موفق؛ رشته = پیام خطا.
     */
    fun download(ctx: Context, expectedSize: Long, onProgress: (Int) -> Unit): String? {
        if (busy) return "یک دانلود در جریان است"
        busy = true
        try {
            return downloadInner(ctx, expectedSize, onProgress)
        } finally {
            busy = false
        }
    }

    private fun downloadInner(ctx: Context, expectedSize: Long, onProgress: (Int) -> Unit): String? {
        val out = File(ctx.cacheDir, APK_FILE)
        val tmp = File(ctx.cacheDir, "$APK_FILE.tmp")
        var lastPct = -1
        for (host in HOSTS) {
            var c: HttpURLConnection? = null
            try {
                tmp.parentFile?.mkdirs()
                tmp.delete()
                c = (URL("$host/apk").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", "VEX-Client/${Build.VERSION.SDK_INT} (self-update)")
                }
                val total = c.contentLengthLong.takeIf { it > 0 }
                    ?: (expectedSize.takeIf { it > 0 } ?: -1L)
                if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
                c.inputStream.use { ins ->
                    tmp.outputStream().use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    try { onProgress(pct) } catch (_: Throwable) {}
                                }
                            }
                        }
                        fos.flush()
                    }
                }
                val got = tmp.length()
                if (got < 1_000_000) throw Exception("فایل ناقص دانلود شد (${got} بایت)")
                if (total > 0 && kotlin.math.abs(got - total) > 1024 * 1024) throw Exception("حجم فایل با نسخه‌ی منتشرشده نمی‌خواند")
                if (!tmp.renameTo(out)) {
                    out.delete()
                    if (!tmp.renameTo(out)) throw Exception("ذخیره‌ی فایل ناموفق بود")
                }
                return null // موفق
            } catch (e: Throwable) {
                // هاست بعدی را امتحان کن
            } finally {
                try { c?.disconnect() } catch (_: Throwable) {}
            }
        }
        tmp.delete()
        return "دانلود از هر دو مسیر ناموفق بود"
    }

    /** نصب فقط اگر اجازه از قبل داده شده (ادامه‌ی خودکار پس از برگشتن از تنظیمات) — بدون بازکردن تنظیمات */
    fun installIfAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) return false
        return install(ctx)
    }

    /**
     * اجرای نصب — اگر مجوز «نصب برنامه‌های ناشناس» نباشد، صفحه‌ی اجازه باز می‌شود و
     * false برمی‌گردد؛ بعد از دادن اجازه و برگشتن به برنامه، دوباره صدا زدنش نصب را کامل می‌کند.
     */
    fun install(ctx: Context): Boolean {
        val f = File(ctx.cacheDir, APK_FILE)
        if (f.length() < 1_000_000) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {}
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** پاک کردن فایل بروزرسانی پس از نصب موفق (در ورود بعدی) */
    fun cleanup(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (p.getLong("chk_code", 0L) <= TelegramGate.selfVersionCode(ctx)) {
                File(ctx.cacheDir, APK_FILE).delete()
                File(ctx.cacheDir, "$APK_FILE.tmp").delete()
            }
        } catch (_: Throwable) {}
    }
}
