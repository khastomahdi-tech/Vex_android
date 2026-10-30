package com.vex.client

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * ضربان پس‌زمینه‌ی اعلان‌های مدیر — با WorkManager:
 *
 *  حتی وقتی کاربر برنامه را کاملاً بسته باشد (Back یا سوایپ از Recents)،
 *  سیستم اندروید این کار را هر ۱۵ دقیقه بیدار می‌کند؛ اعلان‌های تازه‌ی مدیر
 *  (ارسال‌شده از ربات یا پنل) از دروازه خوانده می‌شود و به‌صورت
 *  نوتیفیکیشن واقعی سیستم بالای صفحه نشان داده می‌شود.
 *
 *  • حداقل بازه‌ی مجاز WorkManager ۱۵ دقیقه است؛ وقتی برنامه باز است، تیکِ
 *    ۴۵ ثانیه‌ایِ MainActivity اعلان‌ها را خیلی سریع‌تر می‌رساند.
 *  • کار به‌صورت «دائمی» چیده می‌شود و بعد از ریستارت گوشی هم توسط سیستم
 *    از نو برنامه‌ریزی می‌شود — هیچ سرویس دائمی و هیچ آیکون اضافه‌ای ندارد.
 *  • FORCE-STOP از تنظیمات گوشی تنها حالتی است که اندروید همه‌چیز را می‌کشد
 *    (محدودیت سیستم‌عامل، همه‌ی برنامه‌ها همین‌طورند).
 */
class NotifyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            Notifier.fetchAndShow(applicationContext)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "vex_notify_pulse"

        /** چیدن ضربان (اگر از قبل نباشد) — ارزان و idempotent؛ KEEP یعنی دوباره‌سازی نمی‌کند */
        fun ensure(context: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (_: Throwable) {}
        }
    }
}
