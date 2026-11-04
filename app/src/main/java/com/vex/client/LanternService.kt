package com.vex.client

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * سرویس موتور لنترن — در پروسه‌ی جدا «:lantern» اجرا می‌شود.
 *
 * وظایف:
 *  ۱) بالا آوردن هسته‌ی رسمی Lantern (پروکسی محلی HTTP روی 127.0.0.1)
 *  ۲) نوشتن پورت در فایل مشترک برای پروسه‌ی اصلی
 *  ۳) نگه‌داشتن هسته در حین اتصال (سرویس پیش‌زمینه با اعلان بی‌صدا)
 *
 * V5.9 — سپر کرش پروسه (رفع ریشه‌ای «process is bad»):
 *  هر استثنای گرفته‌نشده در هر تردی از این پروسه، توسط هندلر پیش‌فرض اندروید پروسه را
 *  می‌کشد؛ اگر این اتفاق دو بار پشت‌سرهم بیفتد، اندروید پروسه را «bad» علامت می‌زند و
 *  دیگر اجازه‌ی اجرا نمی‌دهد («Unable to launch app ... process is bad» — خطایی که کاربر
 *  دید). سپر کرش اینجا استثناها را می‌گیرد، در فایل می‌نویسد (برای «جزئیات خطا») و
 *  عمداً هندلر پیش‌فرض را صدا نمی‌زند ⇒ پروسه زنده می‌ماند و موتور قابل بازیابی است.
 *
 * خاموش‌کردن: Intent با ACTION_STOP از پروسه‌ی اصلی (VexVpnService هنگام قطع).
 * پس از توقف، پروسه‌ی :lantern عمداً kill می‌شود تا هیچ نمونه‌ی زامبی flashlight در
 * پس‌زمینه نماند. اگر قبل از kill، دستور ACTION_START/ACTION_RESTART برسد، kill لغو
 * می‌شود (epoch guard) تا اتصال مجدد سریع، بدون spawn پروسه‌ی جدید انجام شود.
 */
class LanternService : Service() {

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** هر ACTION_STOP یک epoch جدید می‌سازد؛ kill فقط اگر epoch عوض نشده باشد */
    private val killEpoch = AtomicInteger(0)
    private val crashShieldInstalled = AtomicBoolean(false)

    /**
     * V5.11 — WakeLock جزئی پروسه‌ی لنترن (الگوی SagerNet/NekoBox):
     * وقتی CPU به خواب می‌رود (Doze)، گوروتین‌های flashlight هم ست می‌شوند ⇒ مسیر لنترن
     * وسط کار «یخ» می‌زند و کاربر قطعی می‌بیند. سرویس پیش‌زمینه بودن کافی نیست؛ این
     * WakeLock تا وقتی موتور روشن است CPU را بیدار نگه می‌دارد (در stop آزاد می‌شود).
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakelock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(android.os.PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vex:lnt").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWakelock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        installCrashShield()
        try { LanternEngine.writePid(this) } catch (_: Throwable) {}
        try { Notifier.ensureChannels(this) } catch (_: Throwable) {}
    }

    /**
     * سپر کرش — اولین کدِ این پروسه که اجرا می‌شود.
     * استثنای گرفته‌نشده = نوشتن در فایل کرش + زنده‌ماندن پروسه.
     */
    private fun installCrashShield() {
        if (!crashShieldInstalled.compareAndSet(false, true)) return
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("[$ts] thread=${t?.name} exception=${e.javaClass.name}\n")
                sb.append("message=${e.message ?: ""}\n")
                sb.append(Log.getStackTraceString(e))
                LanternEngine.crashFile(this).writeText(sb.toString())
            } catch (_: Throwable) {}
            // عمداً هندلر پیش‌فرض صدا زده نمی‌شود — مرگ پروسه یعنی «process is bad»
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // کل بدنه در برابر هر استثنایی مقاوم — هیچ استثنایی نباید اینجا پروسه را بکشد
        try {
            when {
                intent?.action == ACTION_STOP -> {
                    killEpoch.incrementAndGet()
                    LanternEngine.stop(this)
                    releaseWakelock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    // پروسه‌ی اختصاصی لنترن — پس از توقف، kill تمیز (بدون زامبی flashlight)
                    scheduleProcessKill()
                    return START_NOT_STICKY
                }
                // V5.9 — بوت مجدد flashlight درون همان پروسه (سرور جایگزین لنترن، بی‌صدا)
                intent?.action == ACTION_RESTART -> {
                    killEpoch.incrementAndGet() // kill در انتظار را لغو کن — پروسه باید زنده بماند
                    goForeground()
                    acquireWakelock()
                    LanternEngine.restartInProcess(this)
                }
                // رستارت توسط سیستم بدون Intent — یعنی کاربر قطع کرده و نباید دوباره بوت شود
                intent == null -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
                else -> {
                    // START (و بوت مجدد بعد از شکست): kill در انتظار را لغو کن
                    killEpoch.incrementAndGet()
                    // سرویس پیش‌زمینه — تا سیستم پروسه‌ی لنترن را وسط کار نکشد
                    goForeground()
                    acquireWakelock() // V5.11 — CPU بیدار بماند؛ ست‌شدن flashlight یعنی قطعی
                    if (!LanternEngine.isRunning()) LanternEngine.start(this)
                }
            }
        } catch (t: Throwable) {
            // حتی اگر پیش‌زمینه‌شدن شکست بخورد، بوت هسته‌ی لنترن باید ادامه پیدا کند
            Log.w("VexLantern", "onStartCommand suppressed: ${t.message}")
        }
        return START_NOT_STICKY
    }

    private fun scheduleProcessKill() {
        val ep = killEpoch.get()
        mainHandler.postDelayed({
            if (killEpoch.get() == ep) { // اگر بین STOP و kill یک START/RESTART آمده باشد، لغو
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
            }
        }, 800)
    }

    /**
     * نوع سرویس «specialUse» فقط از API 34 شناخته می‌شود. روی اندروید ۱۰ تا ۱۳ (API 29..33)
     * پاس‌دادن FOREGROUND_SERVICE_TYPE_SPECIAL_USE به startForeground می‌تواند
     * IllegalArgumentException بدهد — و چون این کد در همان لحظه‌ی بوتِ پروسه‌ی «:lantern»
     * اجرا می‌شود، کل پروسه می‌میرد (رفع‌شده در V5.8 و همین‌طور حفظ شده).
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
        releaseWakelock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.vex.client.LANTERN_START"
        const val ACTION_STOP = "com.vex.client.LANTERN_STOP"
        /** V5.9 — بوت مجدد flashlight درون همان پروسه (بدون spawn) — failover بی‌صدای لنترن */
        const val ACTION_RESTART = "com.vex.client.LANTERN_RESTART"
        private const val NOTIF_ID = 2
    }
}
