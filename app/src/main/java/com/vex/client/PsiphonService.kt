package com.vex.client

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * سرویس موتور سایفون — در پروسه‌ی جدا «:psiphon» اجرا می‌شود.
 *
 * وظایف:
 *  ۱) بالا آوردن تونل رسمی Psiphon (پروکسی محلی SOCKS روی 127.0.0.1)
 *  ۲) نوشتن پورت در فایل مشترک برای پروسه‌ی اصلی
 *  ۳) نگه‌داشتن تونل در حین اتصال (سرویس پیش‌زمینه با اعلان بی‌صدا)
 *
 * خاموش‌کردن: Intent با ACTION_STOP از پروسه‌ی اصلی (VexVpnService هنگام قطع).
 */
class PsiphonService : Service() {

    override fun onCreate() {
        super.onCreate()
        try { Notifier.ensureChannels(this) } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> {
                PsiphonEngine.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // راه‌اندازی مجدد توسط سیستم (بدون Intent) — اگر تونل زنده است کاری نکن
            intent == null && PsiphonEngine.isRunning() -> return START_STICKY
            else -> {
                // سرویس پیش‌زمینه — تا سیستم پروسه‌ی سایفون را وسط کار نکشد
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        NOTIF_ID, PsiphonEngine.buildForegroundNotification(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIF_ID, PsiphonEngine.buildForegroundNotification(this))
                }
                if (!PsiphonEngine.isRunning()) PsiphonEngine.start(this)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // اگر پروسه کشته شد، پورت کهنه نباید گول پروسه‌ی اصلی را بزند
        if (PsiphonEngine.isRunning()) PsiphonEngine.stop(this)
        PsiphonEngine.clearPort(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.vex.client.PSIPHON_START"
        const val ACTION_STOP = "com.vex.client.PSIPHON_STOP"
        private const val NOTIF_ID = 2
    }
}
