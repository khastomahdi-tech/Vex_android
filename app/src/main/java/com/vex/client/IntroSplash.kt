package com.vex.client

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * پیش‌نمایش ورود VEX — پیش از ورود به برنامه:
 *  دو شیر بالدار روبه‌روی هم از بالای صفحه به مرکز سقوط می‌کنند و می‌نشینند،
 *  تاج حذف شد — دو شیر + عنوان طلایی VEX CLIENT
 *  لمس صفحه = رد کردن؛ پس از ~۲٫۴ ثانیه محو و حذف می‌شود.
 */
object IntroSplash {

    fun show(act: Activity, onDone: () -> Unit = {}) {
        val ctx = act
        val density = ctx.resources.displayMetrics.density

        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }

        // ---------------- صحنه‌ی مرکزی: دو شیر + عنوان ----------------
        val stage = FrameLayout(ctx)
        val stageLp = FrameLayout.LayoutParams(
            UI.dp(ctx, 340), UI.dp(ctx, 300), Gravity.CENTER)

        // شیر بالدار چپ (رو به راست) — سقوط از بالا
        val leftBox = lionBox(ctx, mirrored = false)
        val leftLp = FrameLayout.LayoutParams(
            UI.dp(ctx, 170), UI.dp(ctx, 178),
            Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = UI.dp(ctx, 4) }
        stage.addView(leftBox, leftLp)

        // شیر بالدار راست (آینه‌ی چپ، رو به چپ)
        val rightBox = lionBox(ctx, mirrored = true)
        val rightLp = FrameLayout.LayoutParams(
            UI.dp(ctx, 170), UI.dp(ctx, 178),
            Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = UI.dp(ctx, 4) }
        stage.addView(rightBox, rightLp)

        // عنوان: VEX CLIENT
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val title = TextView(ctx).apply {
            text = "VEX CLIENT"
            textSize = 25f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            setTextColor(0xFFE8B923.toInt())
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setShadowLayer(9f * density, 0f, 0f, 0x66E8B923)
        }
        titleCol.addView(title)
        stage.addView(titleCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER).apply { topMargin = UI.dp(ctx, 100) })

        overlay.addView(stage, stageLp)

        // شروع: هر دو جعبه بالای صفحه (بیرون از دید)
        val dropFrom = -(UI.dp(ctx, 360)).toFloat()
        leftBox.translationY = dropFrom
        rightBox.translationY = dropFrom

        // ---------------- انیمیشن سقوط و نشستن ----------------
        fun fall(box: FrameLayout, delay: Long, dur: Long): Animator =
            ValueAnimator.ofFloat(dropFrom, 0f).apply {
                startDelay = delay
                duration = dur
                interpolator = DecelerateInterpolator(2.2f) // سقوط با ترمز نرم
                addUpdateListener { box.translationY = it.animatedValue as Float }
            }

        // لرزش نشستن (bounce کوچک بعد از فرود)
        fun settle(box: FrameLayout, delay: Long): Animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = delay
                duration = 340
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    box.translationY = -14f * density * (1f - t) * kotlin.math.sin(t * Math.PI).toFloat()
                }
            }

        val titleIn = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 720
            duration = 520
            addUpdateListener {
                val v = it.animatedValue as Float
                titleCol.alpha = v
                titleCol.scaleX = 0.86f + 0.14f * v
                titleCol.scaleY = 0.86f + 0.14f * v
            }
        }

        val anims = AnimatorSet().apply {
            playTogether(
                fall(leftBox, 120, 820),
                fall(rightBox, 260, 820),
                settle(leftBox, 940),
                settle(rightBox, 1060),
                titleIn
            )
        }
        anims.start()

        // ---------------- بستن ----------------
        var closed = false
        fun close(now: Boolean) {
            if (closed) return
            closed = true
            val finish = {
                try {
                    (overlay.parent as? FrameLayout)?.removeView(overlay)
                } catch (_: Throwable) {}
                onDone()
            }
            if (now) { anims.cancel(); finish() }
            else {
                overlay.animate().alpha(0f).setDuration(420)
                    .setInterpolator(AccelerateInterpolator())
                    .setStartDelay(620)
                    .withEndAction { finish() }
                    .start()
            }
        }

        overlay.setOnClickListener { close(now = true) }
        anims.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { close(now = false) }
        })

        // افزودن روی کل محتوای پنجره
        val content = act.findViewById<FrameLayout>(android.R.id.content)
        content.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
    }

    /** جعبه‌ی شیر طبیعی طلایی + هاله‌ی نور پشتش */
    private fun lionBox(ctx: Activity, mirrored: Boolean): FrameLayout {
        val box = FrameLayout(ctx)
        // هاله‌ی طلایی محو پشت شیر — جلوه‌ی سلطنتی روی زمینه‌ی سیاه
        val glow = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(
                UI.alpha(0xFFE8B923.toInt(), 0.34f),
                UI.alpha(0xFFE8B923.toInt(), 0.06f),
                android.graphics.Color.TRANSPARENT)
        }
        box.background = glow
        box.setPadding(UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8))
        val lion = ImageView(ctx).apply {
            setImageResource(R.drawable.art_lion)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (mirrored) scaleX = -1f
        }
        box.addView(lion, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return box
    }
}
