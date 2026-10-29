package com.vex.client

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * دکمه‌ی پاور سلطنتی VEX — مطابق طرح مرجع:
 *  - حلقه‌ی اصلی با گرادیان طلایی فلزی
 *  - دو خط لبه‌ی باریک بالا و پایین حلقه
 *  - حلقه‌ی باریک دوم + حلقه‌ی تزئینی نقطه‌چین بیرونی
 *  - تاج طلایی نشسته بر بالای حلقه
 *  - صفحه‌ی داخلی تیره با درخشش ملایم مرکز
 *  - گلیف پاور طلایی با هاله در حالت روشن، تنفس/چرخش در حالت اتصال
 */
class PowerButtonView(context: Context) : View(context) {

    var mode: String = "off"   // off | busy | on
        set(value) { field = value; restartAnim(); invalidate() }

    var palette: Palette = Themes.royal()
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

    /** بازخورد لمسی لوکس: فرو رفتن نرم هنگام فشردن، جهش هنگام رها کردن */
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
        val cy = h / 2f + dp(6f)
        val p = palette
        val rMain = min(w, h) / 2f - dp(38f) // شعاع حلقه‌ی اصلی

        // رنگ پایه‌ی حلقه: اتصال موفق = سبز، بقیه = طلایی/اکsent تم
        val connected = mode == "on"
        val ringBase = if (connected) 0xFF3DC871.toInt() else p.accent

        // ---------- ۱) هاله‌ی بیرونی ----------
        val glowA = when (mode) {
            "on" -> 0.40f + 0.10f * sin(phase * 2f * Math.PI).toFloat()
            "busy" -> 0.20f + 0.22f * (0.5f + 0.5f * sin(phase * 2f * Math.PI).toFloat())
            else -> 0.10f // نفس ملایم حتی در حالت خاموش — حس فلز گران‌بها
        }
        if (glowA > 0.01f) {
            paint.shader = RadialGradient(
                cx, cy, rMain * 1.62f,
                UI.alpha(ringBase, glowA.coerceIn(0f, 1f)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, rMain * 1.62f, paint)
            paint.shader = null
        }
        // هاله‌ی دوم دوردست — عمق بیشتر در حالت روشن
        if (mode != "off") {
            paint.shader = RadialGradient(
                cx, cy, rMain * 1.95f,
                UI.alpha(ringBase, 0.12f), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, rMain * 1.95f, paint)
            paint.shader = null
        }

        // ---------- ۲) حلقه‌های تزئینی بیرونی ----------
        // حلقه‌ی نقطه‌چین (حکاکی)
        stroke.shader = null
        stroke.color = UI.alpha(ringBase, 0.5f)
        stroke.strokeWidth = dp(1.2f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(1.6f), dp(5.2f)), 0f)
        canvas.drawCircle(cx, cy, rMain + dp(26f), stroke)
        stroke.pathEffect = null
        // حلقه‌ی باریک دوم
        stroke.color = UI.alpha(ringBase, 0.85f)
        stroke.strokeWidth = dp(1.4f)
        canvas.drawCircle(cx, cy, rMain + dp(19f), stroke)
        // تیک‌های حکاکی بین دو حلقه (نقش فلز کنده‌کاری‌شده‌ی طرح مرجع)
        stroke.color = UI.alpha(ringBase, 0.34f)
        stroke.strokeWidth = dp(1.1f)
        stroke.pathEffect = null
        val rTickIn = rMain + dp(20.4f)
        val rTickOut = rTickIn + dp(3.4f)
        for (i in 0 until 72) {
            val ang = Math.PI * 2.0 * i / 72.0
            val cosA = Math.cos(ang).toFloat(); val sinA = sin(ang).toFloat()
            val long = i % 6 == 0
            val ro = if (long) rTickOut + dp(1.6f) else rTickOut
            canvas.drawLine(cx + cosA * rTickIn, cy + sinA * rTickIn,
                            cx + cosA * ro, cy + sinA * ro, stroke)
        }
        stroke.pathEffect = null

        // ستاره‌ی مدارگرد روی حلقه‌ی نقطه‌چین (در روشن/در حال اتصال)
        if (mode != "off") {
            val orbA = phase * 2f * Math.PI
            val orbR = rMain + dp(26f)
            val orbCol = if (connected) 0xFFC9FFDD.toInt() else 0xFFFFE9A8.toInt()
            fillP.shader = RadialGradient(
                cx + (orbR * Math.cos(orbA)).toFloat(), cy + (orbR * Math.sin(orbA)).toFloat(), dp(8f),
                orbCol, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx + (orbR * Math.cos(orbA)).toFloat(), cy + (orbR * Math.sin(orbA)).toFloat(), dp(8f), fillP)
            fillP.shader = null
        }

        // ---------- ۳) حلقه‌ی اصلی گرادیانی (اتصال موفق = سبز فلزی) ----------
        val golds = if (connected) intArrayOf(
            0xFF1E7A46.toInt(), 0xFF4CE07E.toInt(), 0xFF2FBF66.toInt(),
            0xFF14522E.toInt(), 0xFF37A85C.toInt(), 0xFF1E7A46.toInt()
        ) else intArrayOf(
            0xFF8A6A1B.toInt(), 0xFFF5D061.toInt(), 0xFFE8B923.toInt(),
            0xFF6E5412.toInt(), 0xFFC99B2E.toInt(), 0xFF8A6A1B.toInt()
        )
        val sweep = SweepGradient(cx, cy, golds, null)
        canvas.save()
        if (mode == "busy") canvas.rotate(phase * 360f, cx, cy)
        stroke.shader = sweep
        stroke.strokeWidth = dp(11f)
        canvas.drawCircle(cx, cy, rMain, stroke)
        stroke.shader = null
        canvas.restore()

        // لبه‌های باریک بالا/پایین حلقه‌ی اصلی
        stroke.color = UI.alpha(if (connected) 0xFF8CF5B0.toInt() else 0xFFF5D061.toInt(), 0.55f)
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, rMain + dp(6.2f), stroke)
        canvas.drawCircle(cx, cy, rMain - dp(6.2f), stroke)

        // درخشش آینه‌ای چرخان روی حلقه — حس فلز صیقلی
        val specSweep = SweepGradient(
            cx, cy,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, UI.alpha(0xFFFFF6D8.toInt(), 0.75f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 0.5f, 0.58f)
        )
        canvas.save()
        if (mode != "off") canvas.rotate(phase * 360f, cx, cy) else canvas.rotate(-38f, cx, cy)
        stroke.shader = specSweep
        stroke.strokeWidth = dp(11f)
        canvas.drawCircle(cx, cy, rMain, stroke)
        stroke.shader = null
        canvas.restore()

        // ---------- ۴) صفحه‌ی داخلی تیره ----------
        fillP.shader = if (connected) RadialGradient(
            cx - rMain * 0.25f, cy - rMain * 0.32f, rMain * 1.35f,
            0xFF0B2E1A.toInt(), 0xFF020604.toInt(), Shader.TileMode.CLAMP
        ) else RadialGradient(
            cx - rMain * 0.25f, cy - rMain * 0.32f, rMain * 1.35f,
            0xFF2B2109.toInt(), 0xFF060401.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rMain - dp(7f), fillP)
        fillP.shader = null
        // نور افتاده از بالا-چپ روی صفحه (جلوی محدب)
        fillP.shader = RadialGradient(
            cx - rMain * 0.38f, cy - rMain * 0.45f, rMain * 1.05f,
            UI.alpha(if (connected) 0xFF8CF5B0.toInt() else 0xFFF5D061.toInt(), 0.10f), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rMain - dp(7f), fillP)
        fillP.shader = null
        // دو حلقه‌ی حکاکی ظریف داخل صفحه
        stroke.shader = null
        stroke.color = UI.alpha(ringBase, 0.20f)
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, rMain - dp(14f), stroke)
        stroke.color = UI.alpha(ringBase, 0.12f)
        canvas.drawCircle(cx, cy, rMain - dp(17.5f), stroke)

        // ---------- ۵) گلیف پاور ----------
        val gr = rMain * 0.46f
        if (mode == "on") {
            fillP.shader = RadialGradient(
                cx, cy, gr * 2.3f,
                UI.alpha(if (connected) 0xFF9FFFBF.toInt() else 0xFFFFD868.toInt(), 0.38f), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, gr * 2.3f, fillP)
            fillP.shader = null
        }
        glyph.color = when (mode) {
            "on" -> if (connected) 0xFF8CF5B0.toInt() else 0xFFFFD868.toInt()
            "busy" -> UI.alpha(p.accent, 0.85f)
            else -> 0xFFE3B94F.toInt()
        }
        // هاله‌ی زیر گلیف در حالت روشن (نور پخش)
        if (mode == "on") {
            val haloCol = if (connected) 0xFF8CF5B0.toInt() else 0xFFFFD868.toInt()
            val halo = Paint(glyph).apply {
                strokeWidth = dp(11f)
                this.color = UI.alpha(haloCol, 0.22f)
            }
            canvas.drawLine(cx, cy - gr, cx, cy - gr * 0.06f, halo)
            rect.set(cx - gr, cy - gr, cx + gr, cy + gr)
            canvas.drawArc(rect, -55f, 270f, false, halo)
        }
        glyph.strokeWidth = dp(5.8f)
        canvas.drawLine(cx, cy - gr, cx, cy - gr * 0.06f, glyph)
        rect.set(cx - gr, cy - gr, cx + gr, cy + gr)
        canvas.drawArc(rect, -55f, 270f, false, glyph)
        // نقطه‌ی درخشان سر خط پاور
        if (mode != "off") {
            fillP.color = UI.alpha(
                if (connected) 0xFFE9FFF2.toInt() else 0xFFFFF3C4.toInt(),
                if (mode == "on") 0.95f else 0.6f)
            canvas.drawCircle(cx, cy - gr, dp(2.4f), fillP)
        }

        // ---------- ۶) تاج طلایی بالای حلقه ----------
        drawCrown(canvas, cx, cy - rMain - dp(21f), dp(15f))
    }

    /** تاج طلایی؛ top = مرکز عمودی تاج، hw = نصف عرض تاج */
    private fun drawCrown(canvas: Canvas, cx: Float, top: Float, hw: Float) {
        val peak = top - hw * 0.42f
        val baseY = top + hw * 0.55f
        val path = Path()
        path.moveTo(cx - hw, baseY)
        path.lineTo(cx - hw, top - hw * 0.18f)
        path.lineTo(cx - hw * 0.48f, top + hw * 0.14f)
        path.lineTo(cx, peak)
        path.lineTo(cx + hw * 0.48f, top + hw * 0.14f)
        path.lineTo(cx + hw, top - hw * 0.18f)
        path.lineTo(cx + hw, baseY)
        path.close()
        fillP.shader = LinearGradient(
            cx - hw, peak, cx + hw, baseY,
            0xFFF5D061.toInt(), 0xFFB98A1E.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fillP)
        fillP.shader = null
        // گوی‌های نوک تاج
        fillP.color = 0xFFF5D061.toInt()
        canvas.drawCircle(cx - hw, top - hw * 0.30f, dp(2.1f), fillP)
        canvas.drawCircle(cx, peak - dp(1.6f), dp(2.3f), fillP)
        canvas.drawCircle(cx + hw, top - hw * 0.30f, dp(2.1f), fillP)
        // نوار پایه
        fillP.color = UI.alpha(0xFF9A7824.toInt(), 0.95f)
        canvas.drawRoundRect(
            RectF(cx - hw, baseY + dp(1.2f), cx + hw, baseY + dp(3.6f)),
            dp(1.4f), dp(1.4f), fillP
        )
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
