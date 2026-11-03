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
 * پس از توقف، پروسه‌ی :lantern عمداً kill می‌شود تا:
 *  • هیچ نمونه‌ی زامبی flashlight در پس‌زمینه نماند (باتری/ترافیک)،
 *  • دفعه‌ی بعد همیشه بوتِ تمیز با state تازه شروع شود.
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
                // پروسه‌ی اختصاصی لنترن — پس از توقف، kill تمیز (بدون زامبی flashlight)
                mainHandler.postDelayed({
                    try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
                }, 400)
                return START_NOT_STICKY
            }
            // رستارت توسط سیستم بدون Intent — یعنی کاربر قطع کرده و نباید دوباره بوت شود
            intent == null -> {
                stopSelf()
                return START_NOT_STICKY
            }
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // اگر پروسه کشته شد، پورت کهنه نباید گول پروسه‌ی اصلی را بزند
        if (LanternEngine.isRunning()) LanternEngine.stop(this)
        LanternEngine.clearPort(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    companion object {
        const val ACTION_START = "com.vex.client.LANTERN_START"
        const val ACTION_STOP = "com.vex.client.LANTERN_STOP"
        private const val NOTIF_ID = 2
    }
}
