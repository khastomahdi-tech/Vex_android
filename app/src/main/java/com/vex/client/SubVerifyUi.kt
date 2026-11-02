package com.vex.client

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * صفحه‌ی «تأیید حساب کاربری» — نسخه‌ی کاملاً نیتیو (بدون WebView/HTML)،
 * مو‌به‌مو مطابق طرح مرجع telegram-verify-v2-6:
 *
 *  • پس‌زمینه‌ی گرادیانی روشن با دو «بلابِ» محو آبی/فیروزه‌ای که آرام شنا می‌شوند؛
 *  • کارت سفید گرد با حاشیه و سایه‌ی نرم در مرکز؛
 *  • نشان کلید با دو حلقه‌ی پالس‌زننده‌ی دورش + تکان‌خوردنِ آرام آیکون کلید؛
 *  • کادر کد با آیکون اثر انگشت (راست) و متن وسط‌چین؛
 *  • دکمه‌ی آبی گرادیانی «ورود» با موج نور (shine) و اسپینر هنگام بررسی؛
 *  • دکمه‌ی «دریافت کد اشتراک» با آیکون تلگرام داخل دایره‌ی گرادیانی؛
 *  • خطای قرمز وسط‌چین + لرزش (shake) کارت هنگام خطا.
 *
 *  امنیت ورود از سمت MainActivity تأمین می‌شود (فعال‌سازی/بررسی سرور + امضای HMAC).
 */
object SubVerifyUi {

    // ---------------- پالت طرح مرجع (روشن — مطابق HTML) ----------------
    private val bgTop = 0xFFEEF4FF.toInt()
    private val bgBottom = 0xFFF8FBFF.toInt()
    private val textNavy = 0xFF0F172A.toInt()
    private val textMuted = 0xFF64748B.toInt()
    private val blue = 0xFF3B82F6.toInt()
    private val blueDark = 0xFF2563EB.toInt()
    private val errRed = 0xFFEF4444.toInt()
    private val borderGray = 0xFFCBD5E1.toInt()
    private val blobBlue = 0xFF60A5FA.toInt()
    private val blobTeal = 0xFF2DD4BF.toInt()

    private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    private fun dpI(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** پس‌زمینه‌ی گرادیانی + دو بلاب شناور */
    private class Backdrop(ctx: Context) : FrameLayout(ctx) {
        init {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(bgTop, bgBottom))
            // بلاب آبی — بالا راست (نیمه بیرون از صفحه)
            val a = blobView(blobBlue, dpI(ctx, 300))
            addView(a, LayoutParams(dpI(ctx, 300), dpI(ctx, 300), Gravity.TOP or Gravity.END).apply {
                marginEnd = dpI(ctx, -95)
                topMargin = dpI(ctx, -75)
            })
            float(a)
            // بلاب فیروزه‌ای — پایین چپ
            val b = blobView(blobTeal, dpI(ctx, 260))
            addView(b, LayoutParams(dpI(ctx, 260), dpI(ctx, 260), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dpI(ctx, -75)
                bottomMargin = dpI(ctx, -55)
            })
            float(b, 6500)
        }

        private fun blobView(color: Int, sizePx: Int): View {
            val v = View(context)
            val g = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.5f, 0.5f)
                gradientRadius = sizePx * 0.5f
                colors = intArrayOf(UI.alpha(color, 0.40f), UI.alpha(color, 0.16f), Color.TRANSPARENT)
            }
            v.background = g
            return v
        }

        /** شناوری آرام بلاب — همان float ۱۴ ثانیه‌ای طرح مرجع */
        private fun float(v: View, dur: Long = 8000) {
            val ax = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, dp(context, 26f))
            ax.duration = dur; ax.repeatCount = ValueAnimator.INFINITE
            ax.repeatMode = ValueAnimator.REVERSE
            ax.interpolator = DecelerateInterpolator(); ax.start()
            val ay = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -dp(context, 26f))
            ay.duration = dur; ay.repeatCount = ValueAnimator.INFINITE
            ay.repeatMode = ValueAnimator.REVERSE
            ay.interpolator = DecelerateInterpolator(); ay.start()
        }
    }

    /** حلقه‌ی پالس دور نشان کلید — scale ۰٫۸۵→۱٫۳۵ + محوشدن، تکرار بی‌پایان (مطابق pulse) */
    private class PulseRing(ctx: Context, col: Int, phaseMs: Long) : View(ctx) {

        var scaleScale = 0.85f
        var scaleAlpha = 0.9f

        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(ctx, 2f)
            color = col
        }
        private var anim: ValueAnimator? = null

        init {
            anim = ValueAnimator.ofFloat(0.85f, 1.35f).apply {
                duration = 2800
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    scaleScale = it.animatedValue as Float
                    val k = (it.animatedFraction)
                    scaleAlpha = (1f - k).coerceIn(0f, 1f) * 0.9f + 0.1f
                    invalidate()
                }
                start()
                // فاز دوم حلقه با نصف دوره جلو است (مثل animation-delay در CSS)
                if (phaseMs > 0) setCurrentPlayTime(phaseMs)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val r = (minOf(width, height) / 2f) - p.strokeWidth / 2f
            p.alpha = (scaleAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(width / 2f, height / 2f, r, p)
        }
    }

    /**
     * صفحه‌ی کامل — خروجی View آماده‌ی setContentView.
     *  prefillCode   → کد ذخیره‌شده‌ی قبلی (اگر معتبر باشد داخل کادر می‌نشیند)
     *  initialError  → پیام اولیه (کیک/انقضا/قطع سرور)
     *  onSubmit(code, setLoading, showError) → دکمه‌ی ورود (منطق سرور در MainActivity)
     *  onGetCode()   → دکمه‌ی «دریافت کد اشتراک» (ربات)
     */
    fun build(
        act: MainActivity,
        prefillCode: String,
        initialError: String?,
        onSubmit: (code: String, setLoading: (Boolean) -> Unit, showError: (String) -> Unit) -> Unit,
        onGetCode: () -> Unit
    ): View {
        val ctx = act as Context

        val backdrop = Backdrop(ctx)

        // ---------------- کارت مرکزی ----------------
        val card = UI.col(ctx).apply {
            setPadding(dpI(ctx, 24), dpI(ctx, 26), dpI(ctx, 24), dpI(ctx, 22))
        }
        card.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 26f)
            setColor(UI.alpha(0xFFFFFFFF.toInt(), 0.94f))
            setStroke(dpI(ctx, 1), UI.alpha(borderGray, 0.55f))
        }
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(ctx, 26f))
            }
        }
        card.clipToOutline = true
        card.elevation = dp(ctx, 10f)

        // عنوان کوچک بالای کارت
        card.addView(TextView(ctx).apply {
            text = "تأیید حساب کاربری"
            textSize = 13f
            typeface = Fonts.regular(ctx)
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---------------- نشان کلید با پالس ----------------
        val badgeZone = FrameLayout(ctx)
        badgeZone.addView(PulseRing(ctx, UI.alpha(blue, 0.35f), 0), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        badgeZone.addView(PulseRing(ctx, UI.alpha(blue, 0.35f), 1400), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // هسته‌ی نشان: دایره‌ی گرادیانی روشن + آیکون کلید
        val core = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFFDBEAFE.toInt(), 0xFFEFF6FF.toInt())
            }
            elevation = dp(ctx, 6f)
        }
        // تکان‌خوردن آرام آیکون کلید (wiggle ۴ ثانیه‌ای — pivot در ۳۰٪ عرض)
        val key = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_key_blue)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        core.addView(key, FrameLayout.LayoutParams(
            dpI(ctx, 60), dpI(ctx, 60), Gravity.CENTER))
        val wig = ObjectAnimator.ofFloat(key, View.ROTATION, 0f, -8f, 0f)
        wig.duration = 4000
        wig.repeatCount = ValueAnimator.INFINITE
        wig.interpolator = DecelerateInterpolator()
        wig.addUpdateListener { key.pivotX = key.width * 0.30f; key.pivotY = key.height * 0.5f }
        wig.start()

        badgeZone.addView(core, FrameLayout.LayoutParams(
            dpI(ctx, 110), dpI(ctx, 110), Gravity.CENTER))
        card.addView(badgeZone, LinearLayout.LayoutParams(
            UI.dp(ctx, 140), UI.dp(ctx, 140)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpI(ctx, 22)
        })

        // «تأیید از طریق تلگرام»
        card.addView(TextView(ctx).apply {
            text = "تأیید از طریق تلگرام"
            textSize = 22f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            setTextColor(textNavy)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpI(ctx, 20)
        })

        // توضیح
        card.addView(TextView(ctx).apply {
            text = "کد اشتراک را از ربات تلگرام دریافت کرده و در کادر زیر وارد کنید."
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(textMuted)
            setLineSpacing(dp(ctx, 3f), 1f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpI(ctx, 8)
            marginStart = dpI(ctx, 10); marginEnd = dpI(ctx, 10)
        })

        // خطای قرمز (پیش‌فرض مخفی)
        val err = TextView(ctx).apply {
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(errRed)
            setLineSpacing(dp(ctx, 2f), 1f)
            visibility = View.GONE
        }
        card.addView(err, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpI(ctx, 10)
        })
        initialError?.takeIf { it.isNotBlank() }?.let {
            err.text = it
            err.visibility = View.VISIBLE
        }

        // ---------------- کادر کد ----------------
        val field = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18f)
                setColor(UI.alpha(0xFFFFFFFF.toInt(), 0.65f))
                setStroke(dpI(ctx, 2), borderGray)
            }
            setPadding(dpI(ctx, 16), 0, dpI(ctx, 16), 0)
        }
        field.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_fingerprint)
            setColorFilter(blue)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dpI(ctx, 24), dpI(ctx, 24)))

        val input = EditText(ctx).apply {
            hint = "کد اشتراک را وارد کنید"
            textSize = 14.5f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(35))
            setTextColor(textNavy)
            setHintTextColor(UI.alpha(textMuted, 0.75f))
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setTextIsSelectable(false)
            setPadding(dpI(ctx, 10), 0, dpI(ctx, 4), 0)
            if (prefillCode.isNotBlank()) setText(prefillCode)
            // فوکوس خودکار نمی‌گیرد تا کیبورد روی صفحه بالا نیاید — با لمس باز می‌شود
            isFocusable = true
            isFocusableInTouchMode = true
        }
        field.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpI(ctx, 52)))
        card.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpI(ctx, 60)).apply {
            topMargin = dpI(ctx, 22)
        })

        // ---------------- دکمه‌ی آبی «ورود» (گرادیان + شاین + اسپینر) ----------------
        val goBox = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18f)
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(blue, blueDark)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(ctx, 18f))
                }
            }
            clipToOutline = true
        }
        val goLabel = TextView(ctx).apply {
            text = "ورود"
            textSize = 16.5f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        goBox.addView(goLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        goBox.addView(spinner, FrameLayout.LayoutParams(
            dpI(ctx, 22), dpI(ctx, 22), Gravity.CENTER))
        // موج نور (shine) — نوار روشن مایل که هر ۳٫۲ ثانیه از روی دکمه رد می‌شود
        val shine = View(ctx).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(Color.TRANSPARENT, UI.alpha(0xFFFFFFFF.toInt(), 0.45f), Color.TRANSPARENT)
            }
            rotation = 12f
            visibility = View.GONE
        }
        goBox.addView(shine, FrameLayout.LayoutParams(
            dpI(ctx, 70), FrameLayout.LayoutParams.MATCH_PARENT))
        var shineAnim: ObjectAnimator? = null
        goBox.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // شاین فقط وقتی در حال بررسی نیست؟ — مطابق مرجع همیشه می‌تابد
                goBox.post {
                    shineAnim?.cancel()
                    shineAnim = ObjectAnimator.ofFloat(
                        shine, View.TRANSLATION_X,
                        -dp(ctx, 90f), goBox.width + dp(ctx, 90f)
                    ).apply {
                        duration = 3200
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        start()
                    }
                    shine.visibility = View.VISIBLE
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                shineAnim?.cancel(); shineAnim = null
            }
        })

        var busy = false
        fun setLoading(loading: Boolean) {
            busy = loading
            goBox.isEnabled = !loading
            goBox.alpha = if (loading) 0.9f else 1f
            goLabel.visibility = if (loading) View.INVISIBLE else View.VISIBLE
            spinner.visibility = if (loading) View.VISIBLE else View.GONE
        }
        fun showError(msg: String) {
            err.text = msg
            err.visibility = View.VISIBLE
            // لرزش کارت (shake .4s مطابق مرجع)
            try {
                val sh = AnimationUtils.loadAnimation(ctx, R.anim.shake)
                card.startAnimation(sh)
            } catch (_: Throwable) {
                // انیمیشن نبود → لرزش دستی
                val a = ObjectAnimator.ofFloat(card, View.TRANSLATION_X, 0f, -10f, 10f, -8f, 8f, 0f)
                a.duration = 400; a.start()
            }
        }

        goBox.setOnClickListener {
            if (busy) return@setOnClickListener
            val code = SubStore.normalize(input.text.toString())
            if (!SubStore.valid(code)) {
                showError("کد اشتراک باید ۳۰ حرف انگلیسی باشد")
                input.requestFocus()
                return@setOnClickListener
            }
            err.visibility = View.GONE
            input.clearFocus()
            onSubmit(code, ::setLoading, ::showError)
        }

        card.addView(goBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpI(ctx, 56)).apply {
            topMargin = dpI(ctx, 22)
        })

        // ---------------- دکمه‌ی «دریافت کد اشتراک» با آیکون تلگرام ----------------
        val tgBtn = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18f)
                setColor(UI.alpha(0xFFFFFFFF.toInt(), 0.6f))
                setStroke(dpI(ctx, 2), UI.alpha(0xFF60A5FA.toInt(), 0.6f))
            }
        }
        tgBtn.addView(TextView(ctx).apply {
            text = "دریافت کد اشتراک"
            textSize = 14.5f
            typeface = Fonts.bold(ctx)
            setTextColor(textNavy)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tgBtn.addView(FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF38BDF8.toInt(), blueDark)
            }
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_telegram)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(
                dpI(ctx, 15), dpI(ctx, 15), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dpI(ctx, 32), dpI(ctx, 32)).apply {
            marginStart = dpI(ctx, 9)
        })
        tgBtn.setOnClickListener { onGetCode() }
        card.addView(tgBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpI(ctx, 56)).apply {
            topMargin = dpI(ctx, 12)
        })

        // ---------------- چیدمان نهایی: بلاب‌ها + کارت اسکرول‌شدنی در مرکز ----------------
        val scroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val center = FrameLayout(ctx)
        // عرض کارت مثل مرجع محدود است (max-width 440) ولی روی صفحه‌های باریک جا می‌شود
        center.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER).apply {
            marginStart = dpI(ctx, 16); marginEnd = dpI(ctx, 16)
        })
        // انیمیشن بالاآمدن کارت (rise .8s)
        try {
            card.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.rise))
        } catch (_: Throwable) {}
        scroll.addView(center, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        backdrop.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return backdrop
    }
}
