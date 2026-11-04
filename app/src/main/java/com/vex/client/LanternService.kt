package com.vex.client

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

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
        // V5.8: کل بدنه در برابر هر استثنایی مقاوم شده — مرگ پروسه‌ی «:lantern» در لحظه‌ی
        // بوت یعنی «هرچقدر می‌چرخد اتصال برقرار نمی‌شود»؛ هیچ استثنایی نباید اینجا کرش بدهد
        try {
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
                    goForeground()
                    if (!LanternEngine.isRunning()) LanternEngine.start(this)
                }
            }
        } catch (t: Throwable) {
            // حتی اگر پیش‌زمینه‌شدن شکست بخورد، بوت هسته‌ی لنترن باید ادامه پیدا کند
            Log.w("VexLantern", "onStartCommand suppressed: ${t.message}")
        }
        return START_NOT_STICKY
    }

    /**
     * V5.8 — ریشه‌ی واقعی «اتصال لنترن برقرار نمیشه»:
     * نوع سرویس «specialUse» فقط از API 34 شناخته می‌شود. روی اندروید ۱۰ تا ۱۳ (API 29..33)
     * پاس‌دادن FOREGROUND_SERVICE_TYPE_SPECIAL_USE به startForeground می‌تواند
     * IllegalArgumentException بدهد — و چون این کد در همان لحظه‌ی بوتِ پروسه‌ی «:lantern»
     * اجرا می‌شود، کل پروسه می‌میرد، فایل پورت هرگز نوشته نمی‌شود و موتور لنترن هرگز بالا
     * نمی‌آید (سرویس VPN اصلی از startForeground دوپارامتری استفاده می‌کند و برای همین
     * موتور Vex روی همان گوشی‌ها سالم کار می‌کرد — فقط لنترن می‌سوخت).
     * راه‌حل: 34+ = specialUse · پایین‌تر = بدون نوع · هر خطا = تلاش دوباره بدون نوع.
     */
    private fun goForeground() {
        val n = LanternEngine.buildForegroundNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Throwable) {
            // آخرین لایه‌ی مقاومت: پیش‌زمینه‌شدن بدون نوع — همیشه مجاز است
            try { startForeground(NOTIF_ID, n) } catch (_: Throwable) {}
        }
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
