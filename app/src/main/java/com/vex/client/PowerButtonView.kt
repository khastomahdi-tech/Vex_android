package com.vex.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * دکمه‌ی پاور گرد برنامه:
 *  - off : حلقه‌ی بی‌روشن + آیکون پاور
 *  - busy: تنفس (پالس هاله) در حال اتصال
 *  - on  : هاله‌ی روشن accent
 * در تم «شیر و خورشید» به‌جای آیکون پاور، خورشید با پرتو رسم می‌شود.
 */
class PowerButtonView(context: Context) : View(context) {

    var mode: String = "off"   // off | busy | on
        set(value) { field = value; restartAnim(); invalidate() }

    var shir: Boolean = false
        set(value) { field = value; invalidate() }

    var palette: Palette = Themes.dark()
        set(value) { field = value; invalidate() }

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var animator: ValueAnimator? = null
    private var phase = 0f // 0..1 برای تنفس/چرخش پرتوها

    private fun restartAnim() {
        animator?.cancel()
        animator = null
        if (mode == "busy" || (mode == "on" && shir)) {
            val a = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
            }
            a.start()
            animator = a
        } else {
            phase = 0f
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - dp(6f)
        val p = palette

        // هاله‌ی بیرونی
        val glowStrength = when {
            mode == "busy" -> 0.35f + 0.45f * (0.5f + 0.5f * sin(phase * 2f * PI).toFloat())
            mode == "on" -> (if (shir) 0.55f + 0.25f * sin(phase * 2f * PI).toFloat() else 0.8f)
            else -> 0f
        }
        if (glowStrength > 0.01f) {
            glowPaint.shader = RadialGradient(
                cx, cy, r * 1.55f,
                UI.alpha(p.accent, glowStrength.coerceIn(0f, 1f)),
                android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r * 1.55f, glowPaint)
        }

        // بدنه‌ی دکمه
        val bodyColor = when (mode) {
            "on" -> p.accent
            "busy" -> p.card2
            else -> p.card2
        }
        circle.color = bodyColor
        canvas.drawCircle(cx, cy, r, circle)

        ring.color = when (mode) {
            "on" -> UI.alpha(p.accent, 0.9f)
            "busy" -> p.line
            else -> p.line
        }
        ring.strokeWidth = dp(1.6f)
        canvas.drawCircle(cx, cy, r - dp(1f), ring)

        // آیکون
        val iconR = r * 0.46f
        glyph.strokeWidth = dp(if (mode == "on") 3.4f else 3f)
        if (shir) {
            drawSun(canvas, cx, cy, iconR * 1.05f, p)
        } else {
            drawPower(canvas, cx, cy, iconR, p)
        }
    }

    private fun drawPower(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Palette) {
        val color = if (mode == "on") p.onAccent else p.muted
        glyph.color = color
        // خط عمودی
        canvas.drawLine(cx, cy - r, cx, cy - r * 0.08f, glyph)
        // قوس ۲۷۰ درجه (بالا باز)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -55f, 270f, false, glyph)
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Palette) {
        val sunColor = if (mode == "on") p.onAccent else p.accent
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sunColor }
        val rays = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = sunColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(3f)
        }
        // صورت خورشید
        canvas.drawCircle(cx, cy, r * 0.55f, core)
        // پرتوها (در حالت روشن/اتصال می‌چرخند)
        val rot = if (mode == "off") 0f else phase * 360f
        canvas.save()
        canvas.rotate(rot, cx, cy)
        for (i in 0 until 12) {
            val a = i * (2.0 * PI / 12.0)
            val x1 = cx + (r * 0.72 * cos(a)).toFloat()
            val y1 = cy + (r * 0.72 * sin(a)).toFloat()
            val x2 = cx + (r * 0.98 * cos(a)).toFloat()
            val y2 = cy + (r * 0.98 * sin(a)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, rays)
        }
        canvas.restore()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
