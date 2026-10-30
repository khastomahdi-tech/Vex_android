package com.vex.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * دزدهای امنیتی — بررسی اصالت برنامه هنگام هر ورود:
 *
 *  اگر کسی APK را دستکاری کند (تغییر اسم/آیکون/کدها یا تزریق کد) و دوباره امضا کند،
 *  امضای جدید با امضای رسمی VEX فرق می‌کند و برنامه به‌جای ورود، صفحه‌ی
 *  «این نسخه دستکاری‌شده است» نشان می‌دهد و بسته می‌شود.
 *
 *  اثر انگشت امضای رسمی = SHA-256 گواهی vex.keystore (همان که CI با آن امضا می‌کند):
 *  133D572BD072DDBA2B23D62D03D882F478FFF01C021BB252A4305C11FC515E40
 */
object IntegrityGate {

    /** SHA-256 گواهی امضای رسمی (کوچک‌نویسی حروف مهم نیست) */
    private const val OFFICIAL_SHA256 =
        "133d572bd072ddba2b23d62d03d882f478fff01c021bb252a4305c11fc515e40"

    /** خروجی true فقط وقتی امضای برنامه دقیقاً همان امضای رسمی است */
    fun isOfficial(ctx: Context): Boolean = try {
        sigSha256(ctx).equals(OFFICIAL_SHA256, ignoreCase = true)
    } catch (_: Throwable) {
        false
    }

    /** SHA-256 گواهی امضای نصب‌شده — اندروید ۹+ با API جدید، قدیمی‌تر با API قدیمی */
    private fun sigSha256(ctx: Context): String {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val p = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            p.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val p = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            p.signatures ?: emptyArray()
        }
        val first = sigs.firstOrNull() ?: return ""
        val d = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        return d.joinToString("") { String.format("%02x", it) }
    }
}
