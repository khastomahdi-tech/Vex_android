package com.vex.client

import android.os.ParcelFileDescriptor

/**
 * اتصال به هسته‌ی پروکسی (sing-box / libbox).
 *
 * وضعیت فعلی: هسته هنوز وصل نشده. start() عمداً خطا می‌دهد تا:
 *  - برنامه وانمود نکند متصل شده،
 *  - تونل بدون هسته، اینترنت گوشی را قطع نکند.
 *
 * مرحله‌ی بعد: اینجا libbox صدا زده می‌شود و fd تونل (tunFd) به آن داده می‌شود.
 */
class CoreEngine(private val config: String, private val tunFd: ParcelFileDescriptor) {

    fun start() {
        throw IllegalStateException("هسته‌ی VPN هنوز به برنامه متصل نشده است")
    }

    fun stop() {
        // TODO: توقف هسته
    }

    /** [سرعت دانلود، سرعت آپلود، کل دانلود، کل آپلود] به بایت */
    fun stats(): LongArray = longArrayOf(0L, 0L, 0L, 0L)
}
