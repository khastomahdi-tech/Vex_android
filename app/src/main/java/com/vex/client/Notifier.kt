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
    const val STATUS_CHANNEL_ID = "vex_status"

    /** شناسه‌ی آخرین اعلان نمایش‌داده‌شده — برای اینکه هر اعلان فقط یک‌بار بیاید */
    private const val PREF = "vex_notify"
    private const val KEY_LAST = "last_id"
    private const val KEY_CLOSED_TS = "last_closed_notif_ts"

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
            // اگر کاربر دسترسی اعلان نداده، شناسه‌ی آخرین اعلان جلو نمی‌رود تا
            // اعلان‌های ارسال‌شده در این فاصله بعد از دادن دسترسی گم نشوند.
            if (Build.VERSION.SDK_INT >= 24) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && !nm.areNotificationsEnabled()) return 0
            }
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

    /**
     * اعلان «برنامه بسته شد» — وقتی کاربر Vex را می‌بندد (Back/سوایپ از Recents)
     * یک اعلان دوستانه در شید می‌ماند تا یادش نرود برنامه وجود دارد.
     * لمسش → برنامه باز می‌شود و آماده‌ی اتصال است (auto_connect).
     * با فاصله‌ی زمانی ۲۰ ثانیه‌ای ضدِ ارسال دوباره (onDestroy + onTaskRemoved).
     */
    fun showAppClosed(ctx: Context, vpnActive: Boolean) {
        try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_CLOSED_TS, 0L)
            if (now - last < 20_000L) return
            prefs.edit().putLong(KEY_CLOSED_TS, now).apply()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(STATUS_CHANNEL_ID, "وضعیت برنامه Vex", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "اعلان خودکار وقتی برنامه بسته می‌شود" }
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 43,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("auto_connect", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val body = if (vpnActive)
                "اتصال VPN فعال است و در پس‌زمینه کار می‌کند.\nبرای مدیریت اتصال، برنامه را دوباره باز کن."
            else
                "برای اتصال سریع به VPN، برنامه را دوباره باز کن."
            val b = NotificationCompat.Builder(ctx, STATUS_CHANNEL_ID)
                .setContentTitle("Vex بسته شد")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setAutoCancel(true)
            if (Build.VERSION.SDK_INT < 26) b.priority = NotificationCompat.PRIORITY_DEFAULT
            nm.notify(10900, b.build())
        } catch (_: Throwable) {}
    }
}
