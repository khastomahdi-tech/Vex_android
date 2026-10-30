package com.vex.client

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * دکمه‌ی پاور VEX — طراحی تمیز و مدرن (بدون جلوه‌های طلایی):
 *  یک دایره‌ی آراسته با حلقه‌ی رنگی وضعیت (اکسنت تم = خاموش، سبز = متصل)،
 *  صفحه‌ی داخلی روشن و گلیف پاور ساده؛ در حالت اتصال‌درحال یک قوس چرخان
 *  روی حلقه می‌گردد و در حالت متصل یک هاله‌ی بسیار ملایم نفس می‌کشد.
 */
class PowerButtonView(context: Context) : View(context) {

    var mode: String = "off"   // off | busy | on
        set(value) { field = value; restartAnim(); invalidate() }

    var palette: Palette = Themes.light()
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var animator: ValueAnimator? = null
    private var phase = 0f
    private var pressAnim: ObjectAnimator? = null

    /** بازخورد لمسی: فرو رفتن نرم هنگام فشردن، جهش هنگام رها کردن */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animateScale(0.945f, 110)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(1f, 260)
        }
        return super.onTouchEvent(event)
    }

    private fun animateScale(to: Float, dur: Long) {
        pressAnim?.cancel()
        pressAnim = ObjectAnimator.ofFloat(this, "scaleX", scaleX, to).apply {
            duration = dur
            interpolator = if (to > scaleX) OvershootInterpolator(2.4f) else LinearInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(this, "scaleY", scaleY, to).apply {
            duration = dur
            interpolator = if (to > scaleX) OvershootInterpolator(2.4f) else LinearInterpolator()
            start()
        }
    }

    private fun restartAnim() {
        animator?.cancel()
        animator = null
        if (mode != "off") {
            val a = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3200
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
        val p = palette
        val connected = mode == "on"
        val ringBase = if (connected) 0xFF27BE6D.toInt() else p.accent
        val rMain = min(w, h) / 2f - dp(26f)
        val maxR = min(w, h) / 2f - dp(2f)

        // ---------- سایه‌ی ملایم زیر دکمه ----------
        val shA = if (Themes.isLightBg(p)) 0.16f else 0.32f
        fillP.shader = RadialGradient(
            cx, cy + dp(7f), rMain * 1.22f,
            UI.alpha(0xFF0B1226.toInt(), shA), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + dp(7f), rMain * 1.22f, fillP)
        fillP.shader = null

        // ---------- هاله‌ی وضعیت — فقط متصل / در حال اتصال، بسیار ملایم ----------
        if (mode != "off") {
            val glowA = (if (connected) 0.20f else 0.15f) +
                    (if (mode == "on") 0.05f * kotlin.math.sin(phase * 2f * Math.PI).toFloat() else 0f)
            val glowR = minOf(rMain + dp(16f), maxR)
            fillP.shader = RadialGradient(
                cx, cy, glowR,
                UI.alpha(ringBase, glowA.coerceIn(0f, 0.3f)), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, glowR, fillP)
            fillP.shader = null
        }

        // ---------- صفحه‌ی داخلی ----------
        val discR = rMain - dp(11f)
        fillP.color = p.card
        canvas.drawCircle(cx, cy, discR, fillP)
        if (connected) {
            fillP.color = UI.alpha(ringBase, 0.10f)
            canvas.drawCircle(cx, cy, discR, fillP)
        }
        fillP.shader = null
        stroke.shader = null
        stroke.color = p.line
        stroke.strokeWidth = dp(1.2f)
        canvas.drawCircle(cx, cy, discR, stroke)

        // ---------- حلقه‌ی اصلی (وضعیت رنگ) ----------
        stroke.shader = null
        stroke.strokeWidth = dp(9f)
        if (mode == "busy") {
            stroke.color = UI.alpha(ringBase, 0.18f)
            canvas.drawCircle(cx, cy, rMain, stroke)
            stroke.color = ringBase
            rect.set(cx - rMain, cy - rMain, cx + rMain, cy + rMain)
            canvas.drawArc(rect, phase * 360f - 80f, 80f, false, stroke)
        } else {
            stroke.color = ringBase
            canvas.drawCircle(cx, cy, rMain, stroke)
        }
        stroke.shader = null

        // ---------- گلیف پاور ----------
        val gr = rMain * 0.44f
        if (mode == "on") {
            fillP.shader = RadialGradient(
                cx, cy, gr * 2.1f,
                UI.alpha(ringBase, 0.14f), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, gr * 2.1f, fillP)
            fillP.shader = null
        }
        glyph.color = when (mode) {
            "on" -> 0xFF1E9E5B.toInt()
            "busy" -> UI.alpha(p.accent, 0.9f)
            else -> p.accent
        }
        glyph.strokeWidth = dp(6f)
        canvas.drawLine(cx, cy - gr, cx, cy - gr * 0.06f, glyph)
        rect.set(cx - gr, cy - gr, cx + gr, cy + gr)
        canvas.drawArc(rect, -45f, 270f, false, glyph)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
