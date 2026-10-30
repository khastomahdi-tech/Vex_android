package com.vex.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * سوییچ لوکس VEX — جایگزین سوییچ پیش‌فرض اندروید:
 *  - ریل با گرادیان طلایی در حالت روشن و تیره‌ی حکاکی‌شده در حالت خاموش
 *  - دسته‌ی متحرک با انیمیشن نرم و درخشش طلایی
 *  - هاله‌ی ملایم هنگام روشن شدن
 */
class VexSwitch(context: Context) : View(context) {

    var palette: Palette = Themes.royal()
        set(value) { field = value; invalidate() }

    var isChecked: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            animateTo(v)
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        // ⚠️ بدون این، View کلیک‌پذیر نیست و لمس‌ها نادیده گرفته می‌شوند
        // (علت واقعی «کار نکردن دکمه‌های تنظیمات» همین بود)
        isClickable = true
        // فوکوس‌پذیر نیست → مستطیل فوکوس دور سوییچ (درخواست کاربر) حذف می‌شود؛
        // کل ردیف در تنظیمات هدف لمس است و سوییچ نیازی به فوکوس ندارد.
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val knobP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineP = Paint(Paint.ANTI_ALIAS_FLAG)

    private var prog = 0f // 0 = خاموش، 1 = روشن
    private var animator: ValueAnimator? = null

    private fun animateTo(target: Boolean) {
        animator?.cancel()
        val from = prog
        val to = if (target) 1f else 0f
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = 190
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                prog = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = UI.dp(context, 52)
        val h = UI.dp(context, 29)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val p = palette
        val strokeW = UI.dp(context, 1.4f).toFloat()

        // ---------- ریل (تم‌پذیر — در تم روشن هم خوانا) ----------
        val light = Themes.isLightBg(p)
        val offTrack = if (light) 0xFFE3E8F2.toInt() else 0xFF2B3448.toInt()
        val onTrack = p.accent
        val c = lerpColor(offTrack, onTrack, prog)
        track.shader = LinearGradient(
            0f, 0f, w, h,
            lerpColor(c, 0xFFFFFFFF.toInt(), 0.10f * prog), lerpColor(c, 0xFF000000.toInt(), 0.22f * prog),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, track)
        track.shader = null
        val ringOn = p.accent
        trackStroke.color = UI.alpha(if (prog > 0.5f) ringOn else p.line, 0.55f + 0.45f * prog)
        trackStroke.strokeWidth = strokeW
        canvas.drawRoundRect(RectF(strokeW / 2f, strokeW / 2f, w - strokeW / 2f, h - strokeW / 2f), r - strokeW / 2f, r - strokeW / 2f, trackStroke)

        // ---------- هاله‌ی روشن ----------
        val knobR = r - UI.dp(context, 3.4f)
        val pad = r - knobR
        val kx = lerp(pad + knobR, w - pad - knobR, prog)
        if (prog > 0.03f) {
            glowP.shader = RadialGradient(
                kx, r, knobR * 2.6f,
                UI.alpha(p.accent, 0.40f * prog), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(kx, r, knobR * 2.6f, glowP)
            glowP.shader = null
        }

        // ---------- دسته (تم‌پذیر) ----------
        val knobOff = if (light) 0xFFF7F8FB.toInt() else 0xFF9AA3B2.toInt()
        val knobOn = 0xFFFFFFFF.toInt()
        val knobCol = lerpColor(knobOff, knobOn, prog)
        knobP.shader = RadialGradient(
            kx - knobR * 0.3f, r - knobR * 0.35f, knobR * 1.7f,
            lerpColor(knobCol, 0xFFFFFFFF.toInt(), 0.35f), knobCol, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(kx, r, knobR, knobP)
        knobP.shader = null
        // حلقه‌ی روش دور دسته در حالت روشن
        if (prog > 0.03f) {
            trackStroke.color = UI.alpha(ringOn, 0.9f * prog)
            trackStroke.strokeWidth = UI.dp(context, 1.2f).toFloat()
            canvas.drawCircle(kx, r, knobR - trackStroke.strokeWidth / 2f, trackStroke)
        }
        // درخشش کوچک روی دسته
        shineP.color = UI.alpha(0xFFFFFFFF.toInt(), if (light) 0.30f else 0.55f)
        canvas.drawCircle(kx - knobR * 0.34f, r - knobR * 0.38f, knobR * 0.20f, shineP)
    }

    override fun performClick(): Boolean {
        isChecked = !isChecked
        onCheckedChange?.invoke(isChecked)
        return super.performClick()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val a1 = (c1 ushr 24) and 0xFF; val a2 = (c2 ushr 24) and 0xFF
        val r1 = (c1 ushr 16) and 0xFF; val r2 = (c2 ushr 16) and 0xFF
        val g1 = (c1 ushr 8) and 0xFF; val g2 = (c2 ushr 8) and 0xFF
        val b1 = c1 and 0xFF; val b2 = c2 and 0xFF
        val mix = { x: Int, y: Int -> (x + (y - x) * t).toInt().coerceIn(0, 255) }
        return ((mix(a1, a2) shl 24) or (mix(r1, r2) shl 16) or (mix(g1, g2) shl 8) or mix(b1, b2))
    }
}
