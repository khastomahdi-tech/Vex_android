package com.vex.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * حلقه‌ی پیشرفت زمان باقی‌مانده‌ی اشتراک (تب حساب کاربری):
 *  یک حلقه‌ی باریک خاکستری که خطِ رنگی داخلش با نسبتِ «باقی‌مانده ÷ کل اشتراک» پر می‌شود
 *  و وسط حلقه: زمان باقی‌مانده (بزرگ و بولد) + برچسب کوچک زیرش — دقیقاً همان چیزی که
 *  کاربر خواست: «دایره‌ای که یک خط داخلش دارد پر می‌شود و زمان باقی‌مانده داخلش است».
 */
class RingProgressView(context: Context) : View(context) {

    /** 0..1 — نسبت پرشدن حلقه */
    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var mainText: String = "—"
        set(v) { field = v; invalidate() }

    var subText: String = "زمان باقی‌مانده"
        set(v) { field = v; invalidate() }

    var ringColor: Int = 0xFF27BE6D.toInt()
        set(v) { field = v; invalidate() }

    var trackColor: Int = 0x22FFFFFF
        set(v) { field = v; invalidate() }

    var mainColor: Int = 0xFF27BE6D.toInt()
        set(v) { field = v; invalidate() }

    var subColor: Int = 0xFF64748B.toInt()
        set(v) { field = v; invalidate() }

    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val mainP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.bold(context)
    }
    private val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.regular(context)
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val ringW = w * 0.055f
        val r = (minOf(w, h) / 2f) - ringW / 2f - dp(2f)

        // زمینه‌ی حلقه
        trackP.strokeWidth = ringW
        trackP.color = trackColor
        canvas.drawCircle(cx, cy, r, trackP)

        // خطِ پرشونده — از بالای دایره (ساعت ۱۲) شروع می‌شود
        if (progress > 0.002f) {
            progP.strokeWidth = ringW * 0.92f
            progP.color = ringColor
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -90f, 360f * progress, false, progP)
        }

        // متن مرکزی
        mainP.color = mainColor
        mainP.textSize = dp(17f)
        val mainY = cy - dp(2f) - (mainP.descent() + mainP.ascent()) / 2f
        canvas.drawText(mainText, cx, mainY, mainP)

        subP.color = subColor
        subP.textSize = dp(10.5f)
        val subY = cy + dp(15f) - (subP.descent() + subP.ascent()) / 2f
        canvas.drawText(subText, cx, subY, subP)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
