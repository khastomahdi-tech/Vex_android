package com.vex.client

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * دکمه‌ی پاور VEX — یک حلقه‌ی سفید با گلیف پاور در مرکز و «موج آبِ» چندلایه:
 *  خاموش  → موج خوابیده و خاکستری در ته دکمه (تقریباً خالی)؛
 *  اتصال  → موج مثل آب تا وسط بالا می‌آید و نارنجی می‌شود — سه لایه + نفس کشیدن؛
 *  متصل   → موج سبز می‌شود و کل حباب را پر می‌کند (فقط یک ذره سرِ حباب خالی می‌ماند)؛
 *  خاموشی → موج دوباره می‌خوابد و خاکستری می‌شود.
 *
 *  v3.23: موج‌ها بیشتر و واقعی‌تر شدند — سه لایه‌ی آب با فاصله‌ی فاز/فرکانس متفاوت،
 *  نفس عمودیِ سطح آب (bob)، دو خط کف و هاله‌ی رنگی وقتی حباب پر است.
 */
class PowerButtonView(context: Context) : View(context) {

    var mode: String = "off"   // off | busy | on
        set(value) {
            if (field == value) return
            field = value
            animateTo(value)
            invalidate()
        }

    var palette: Palette = Themes.light()
        set(value) { field = value; invalidate() }

    // ---------- رنگ‌های موج ----------
    private val cOrange = 0xFFFB8A34.toInt()   // در حال اتصال
    private val cGreen = 0xFF27BE6D.toInt()    // متصل
    private val cGray = 0xFF94A0B2.toInt()     // خاموش
    private val cWhite = 0xFFFFFFFF.toInt()    // حلقه و گلیف

    // ---------- وضعیت فعلی موج (انیمیت‌شونده) ----------
    private var level = 0.08f      // ۰ = ته دکمه، ۰٫۵ = وسط
    private var amp = 0f           // دامنه‌ی موج (px)
    private var waterColor = cGray
    private var waterAlpha = 0.55f
    private var phase = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val waterP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foamP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val clip = Path()
    private val rect = RectF()

    private var flow: ValueAnimator? = null    // حرکت دائمی موج
    private var settle: ValueAnimator? = null  // جابه‌جایی نرم بین حالت‌ها
    private var pressAnim: ObjectAnimator? = null

    /** بازخورد لمسی: فرو رفتن نرم هنگام فشردن، جهش هنگام رها کردن */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animateScale(0.955f, 110)
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

    // ------------------------------------------------------------ انیمیشن موج

    private val ev = ArgbEvaluator()

    /** اهداف هر حالت: level / ampDp / رنگ / آلفا */
    private fun targets(m: String): FloatArray = when (m) {
        "busy" -> floatArrayOf(0.5f, 6.5f, 0.96f, 0f)
        // متصل: موج کل حباب را می‌گیرد — فقط یک ذره سرِ حباب خالی می‌ماند
        "on" -> floatArrayOf(0.93f, 4.4f, 0.96f, 1f)
        else -> floatArrayOf(0.08f, 1.9f, 0.55f, 2f)
    }

    private fun colorOf(m: String): Int = when (m) {
        "busy" -> cOrange
        "on" -> cGreen
        else -> cGray
    }

    private fun animateTo(m: String) {
        settle?.cancel()
        val t = targets(m)
        val fromLevel = level
        val fromAmp = amp
        val fromColor = waterColor
        val fromAlpha = waterAlpha
        val toColor = colorOf(m)
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val k = it.animatedValue as Float
                level = fromLevel + (t[0] - fromLevel) * k
                amp = dp(fromAmp + (t[1] - fromAmp) * k)
                waterColor = ev.evaluate(k, fromColor, toColor) as Int
                waterAlpha = fromAlpha + (t[2] - fromAlpha) * k
                invalidate()
            }
        }.also { it.start() }

        // موج فقط وقتی زنده است که روشن/در حال اتصال باشد؛ در خاموشی بعد از نشستن، می‌خوابد
        if (m != "off") startFlow() else settle?.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (mode == "off") stopFlow()
            }
        })
    }

    private fun startFlow() {
        if (flow?.isRunning == true) return
        flow = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = (it.animatedValue as Float) * 2f * Math.PI.toFloat()
                invalidate()
            }
        }.also { it.start() }
    }

    private fun stopFlow() {
        flow?.cancel()
        flow = null
        phase = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        settle?.cancel(); flow?.cancel(); pressAnim?.cancel()
        settle = null; flow = null; pressAnim = null
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ رسم

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val light = Themes.isLightBg(palette)
        val rMain = min(w, h) / 2f - dp(15f)
        val ringW = dp(7f)
        val innerR = rMain - ringW / 2f - dp(4f)

        // ---------- سایه‌ی ملایم زیر دکمه (حلقه‌ی سفید روی زمینه‌ی روشن هم دیده شود) ----------
        paint.shader = RadialGradient(
            cx, cy + dp(8f), rMain * 1.22f,
            UI.alpha(0xFF0B1226.toInt(), if (light) 0.16f else 0.34f),
            Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + dp(8f), rMain * 1.22f, paint)
        paint.shader = null

        // ---------- موج آب داخل دکمه (سه لایه + نفس عمودی) ----------
        if (level > 0.004f && innerR > 0) {
            val save = canvas.save()
            clip.reset()
            clip.addCircle(cx, cy, innerR - dp(1f), Path.Direction.CW)
            canvas.clipPath(clip)
            // سطح آب: ارتفاع هدف + نفسِ آرام عمودی (bob) تا آب واقعی‌تر به‌نظر برسد
            val bob = Math.sin(phase * 0.5).toFloat() * amp * 0.38f
            val surfaceY = (cy + innerR) - level * (2f * innerR) + bob
            // هاله‌ی رنگی وقتی حباب پر است — نور از ته آب
            if (level > 0.55f) {
                paint.shader = RadialGradient(
                    cx, cy + innerR * 0.45f, innerR * 1.25f,
                    UI.alpha(waterColor, 0.30f * (level - 0.55f) / 0.45f),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, innerR, paint)
                paint.shader = null
            }
            // لایه‌ی پشتی — کم‌رنگ، فاز برعکس، موج بلندتر
            drawWater(canvas, cx, cy, innerR, surfaceY,
                amp * 0.75f, phase + 2.4f, waterColor, waterAlpha * 0.38f, 1.0f)
            // لایه‌ی اصلی — امضای موج
            drawWater(canvas, cx, cy, innerR, surfaceY,
                amp, phase, waterColor, waterAlpha, 1.15f)
            // لایه‌ی جلو — موج‌های ریز سطحی با فرکانس بالاتر (موج‌های بیشتر)
            drawWater(canvas, cx, cy, innerR, surfaceY + dp(2f),
                amp * 0.42f, phase * 1.7f + 1.1f, waterColor, waterAlpha * 0.32f, 2.2f)
            // خط کفِ موج — نور روی سطح آب + کفِ محو لایه‌ی جلو
            drawFoam(canvas, cx, innerR, surfaceY, amp, phase, 1.15f, 0.42f, dp(1.8f))
            drawFoam(canvas, cx, innerR, surfaceY + dp(2f), amp * 0.42f,
                phase * 1.7f + 1.1f, 2.2f, 0.20f, dp(1.1f))
            canvas.restoreToCount(save)
        }

        // ---------- حلقه‌ی سفید توخالی ----------
        ringP.color = cWhite
        ringP.strokeWidth = ringW
        canvas.drawCircle(cx, cy, rMain - ringW / 2f, ringP)

        // ---------- گلیف پاور سفید (با سایه‌ی خیلی ملایم برای خوانایی) ----------
        val gr = rMain * 0.34f
        canvas.save()
        canvas.translate(dp(1.2f), dp(1.8f))
        glyphP.color = UI.alpha(0xFF10151E.toInt(), if (light) 0.18f else 0.30f)
        glyphP.strokeWidth = dp(6f)
        drawGlyph(canvas, cx, cy, gr)
        canvas.restore()
        glyphP.color = cWhite
        glyphP.strokeWidth = dp(6f)
        drawGlyph(canvas, cx, cy, gr)
    }

    /** یک لایه‌ی موج: سطح آب در surfaceY با دامنه‌ی ampPx، فاز ph و فرکانس freq */
    private fun drawWater(canvas: Canvas, cx: Float, cy: Float, r: Float,
                          surfaceY: Float, ampPx: Float, ph: Float, color: Int, alpha: Float,
                          freq: Float) {
        waterP.color = UI.alpha(color, alpha)
        val path = Path()
        val left = cx - r - dp(4f)
        val right = cx + r + dp(4f)
        val bottom = cy + r + dp(6f)
        val step = dp(3f)
        var x = left
        path.moveTo(x, bottom)
        while (x <= right) {
            val t = (x - left) / (right - left)          // 0..1 روی قطر
            val y = surfaceY + ampPx *
                    Math.sin(t * 2.0 * Math.PI * freq + ph).toFloat()
            path.lineTo(x, y)
            x += step
        }
        path.lineTo(right, bottom)
        path.close()
        canvas.drawPath(path, waterP)
    }

    /** خط باریک روشن روی سطح آب — «کفِ» موج */
    private fun drawFoam(canvas: Canvas, cx: Float, r: Float, surfaceY: Float,
                         ampPx: Float, ph: Float, freq: Float, alpha: Float, w: Float) {
        foamP.color = UI.alpha(0xFFFFFFFF.toInt(), alpha)
        foamP.strokeWidth = w
        val path = Path()
        val left = cx - r - dp(4f)
        val right = cx + r + dp(4f)
        val step = dp(3f)
        var x = left
        var first = true
        while (x <= right) {
            val t = (x - left) / (right - left)
            val y = surfaceY + ampPx * Math.sin(t * 2.0 * Math.PI * freq + ph).toFloat()
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += step
        }
        canvas.drawPath(path, foamP)
    }

    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawLine(cx, cy - r, cx, cy - r * 0.06f, glyphP)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -45f, 270f, false, glyphP)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
