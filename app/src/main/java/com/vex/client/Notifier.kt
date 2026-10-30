package com.vex.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * اعلان‌های همگانی مدیر — از ربات با «📢 ارسال اعلان» نوشته می‌شود، در D1 ذخیره می‌شود،
 * برنامه هر ۶۰ ثانیه (در حلقه‌ی به‌روزرسانی سرورهای رایگان) اعلان‌های تازه را می‌گیرد و
 * به‌صورت نوتیفیکیشن سیستم بالای صفحه نشان می‌دهد.
 *
 * دسترسی اعلان (POST_NOTIFICATIONS) بار اولِ ورود کاربر گرفته می‌شود (اندروید ۱۳+).
 */
object Notifier {

    const val CHANNEL_ID = "vex_broadcast"

    /** شناسه‌ی آخرین اعلان نمایش‌داده‌شده — برای اینکه هر اعلان فقط یک‌بار بیاید */
    private const val PREF = "vex_notify"
    private const val KEY_LAST = "last_id"

    fun lastSeenId(ctx: Context): Long =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)

    private fun setLastSeenId(ctx: Context, id: Long) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST, id).apply()
    }

    /** گرفتن اعلان‌های تازه از بک‌اند و نمایش‌شان — خروجی: تعداد نمایش‌داده‌شده */
    fun fetchAndShow(ctx: Context): Int {
        var shown = 0
        try {
            val since = lastSeenId(ctx)
            val list = TelegramGate.fetchNotifications(since) ?: return 0
            for (n in list) {
                if (n.id > since) {
                    show(ctx, n.title, n.body)
                    shown++
                }
            }
            val max = list.maxOfOrNull { it.id } ?: 0L
            if (max > since) setLastSeenId(ctx, max)
        } catch (_: Throwable) {}
        return shown
    }

    /** نمایش یک نوتیفیکیشن سیستم (عنوان + متن مدیر) */
    fun show(ctx: Context, title: String, body: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "اعلان‌های Vex", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "اعلان‌هایی که مدیر Vex از ربات می‌فرستد" }
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 42,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body.ifEmpty { "اعلان از مدیر Vex" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifEmpty { "اعلان از مدیر Vex" }))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setAutoCancel(true)
            if (Build.VERSION.SDK_INT < 26) b.priority = NotificationCompat.PRIORITY_HIGH
            nm.notify(10000 + (title.hashCode() and 0xFFF), b.build())
        } catch (_: Throwable) {}
    }
}
