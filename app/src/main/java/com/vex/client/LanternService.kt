package com.vex.client

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * سرویس موتور لنترن — در پروسه‌ی جدا «:lantern» اجرا می‌شود.
 *
 * وظایف:
 *  ۱) بالا آوردن هسته‌ی رسمی Lantern (پروکسی محلی HTTP روی 127.0.0.1)
 *  ۲) نوشتن پورت در فایل مشترک برای پروسه‌ی اصلی
 *  ۳) نگه‌داشتن هسته در حین اتصال (سرویس پیش‌زمینه با اعلان بی‌صدا)
 *
 * خاموش‌کردن: Intent با ACTION_STOP از پروسه‌ی اصلی (VexVpnService هنگام قطع).
 */
class LanternService : Service() {

    override fun onCreate() {
        super.onCreate()
        try { Notifier.ensureChannels(this) } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> {
                LanternEngine.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // راه‌اندازی مجدد توسط سیستم (بدون Intent) — اگر هسته زنده است کاری نکن
            intent == null && LanternEngine.isRunning() -> return START_STICKY
            else -> {
                // سرویس پیش‌زمینه — تا سیستم پروسه‌ی لنترن را وسط کار نکشد
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        NOTIF_ID, LanternEngine.buildForegroundNotification(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIF_ID, LanternEngine.buildForegroundNotification(this))
                }
                if (!LanternEngine.isRunning()) LanternEngine.start(this)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // اگر پروسه کشته شد، پورت کهنه نباید گول پروسه‌ی اصلی را بزند
        if (LanternEngine.isRunning()) LanternEngine.stop(this)
        LanternEngine.clearPort(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.vex.client.LANTERN_START"
        const val ACTION_STOP = "com.vex.client.LANTERN_STOP"
        private const val NOTIF_ID = 2
    }
}
