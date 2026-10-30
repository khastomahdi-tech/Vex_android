package com.vex.client

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.view.Gravity
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * پیش‌نمایش ورود VEX — تمیز و ساده:
 *  آیکون خود برنامه از بالای صفحه به مرکز سقوط می‌کند و با نشستن نرم می‌ایستد،
 *  عنوان VEX CLIENT زیر آن محو-ظاهر می‌شود؛ بعد صفحه با یک حرکت ملایم به سمت بالا
 *  راهی و رابط برنامه نمایش داده می‌شود. لمس صفحه = رد کردن.
 */
object IntroSplash {

    fun show(act: Activity, onDone: () -> Unit = {}) {
        val ctx = act
        val density = ctx.resources.displayMetrics.density

        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(0xFFF5F7FB.toInt())
            isClickable = true
            isFocusable = true
        }

        // ---------------- صحنه‌ی مرکزی: آیکون برنامه + عنوان ----------------
        val stage = FrameLayout(ctx)
        val stageLp = FrameLayout.LayoutParams(
            UI.dp(ctx, 340), UI.dp(ctx, 300), Gravity.CENTER)

        val icon = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        stage.addView(icon, FrameLayout.LayoutParams(
            UI.dp(ctx, 118), UI.dp(ctx, 118),
            Gravity.CENTER).apply { bottomMargin = UI.dp(ctx, 44) })

        val title = TextView(ctx).apply {
            text = "VEX CLIENT"
            textSize = 22f
            typeface = Fonts.bold(ctx)
            setTextColor(0xFF172033.toInt())
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        stage.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER).apply { topMargin = UI.dp(ctx, 88) })

        overlay.addView(stage, stageLp)

        // شروع: آیکون بالای صفحه (بیرون از دید)
        val dropFrom = -(UI.dp(ctx, 340)).toFloat()
        icon.translationY = dropFrom

        // ---------------- انیمیشن سقوط و نشستن ----------------
        val fall = ValueAnimator.ofFloat(dropFrom, 0f).apply {
            duration = 680
            interpolator = DecelerateInterpolator(1.9f)
            addUpdateListener { icon.translationY = it.animatedValue as Float }
        }

        // لرزش نشستن (bounce کوچک بعد از فرود)
        val settle = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 680
            duration = 320
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                icon.translationY = -12f * density * (1f - t) * kotlin.math.sin(t * Math.PI).toFloat()
            }
        }

        val titleIn = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 540
            duration = 420
            addUpdateListener {
                val v = it.animatedValue as Float
                title.alpha = v
                title.scaleX = 0.9f + 0.1f * v
                title.scaleY = 0.9f + 0.1f * v
            }
        }

        val anims = AnimatorSet().apply {
            playTogether(fall, settle, titleIn)
        }
        anims.start()

        // ---------------- بستن: صفحه‌ی اصلی از پایین آرام بالا می‌آید ----------------
        var closed = false
        fun revealMain(instant: Boolean) {
            val content = act.findViewById<FrameLayout>(android.R.id.content)
            val mainRoot = if (content.childCount > 0) content.getChildAt(0) else null
            if (mainRoot == null || instant) {
                mainRoot?.translationY = 0f
                mainRoot?.alpha = 1f
                return
            }
            if (mainRoot.translationY == 0f && mainRoot.alpha == 1f) return
            mainRoot.animate().translationY(0f).alpha(1f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }

        fun close(now: Boolean) {
            if (closed) return
            closed = true
            val finish = {
                try {
                    (overlay.parent as? FrameLayout)?.removeView(overlay)
                } catch (_: Throwable) {}
                onDone()
            }
            if (now) {
                anims.cancel()
                revealMain(instant = true)
                finish()
            } else {
                revealMain(instant = false)
                overlay.animate().alpha(0f).setDuration(400)
                    .setInterpolator(AccelerateInterpolator())
                    .setStartDelay(240)
                    .withEndAction { finish() }
                    .start()
            }
        }

        overlay.setOnClickListener { close(now = true) }
        anims.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { close(now = false) }
        })

        // افزودن روی کل محتوای پنجره؛ قبل از افتادن آیکون، صفحه‌ی اصلی کمی پایین‌تر و محو است
        val content = act.findViewById<FrameLayout>(android.R.id.content)
        if (content.childCount > 0) {
            val mainRoot = content.getChildAt(0)
            mainRoot.translationY = UI.dp(ctx, 42).toFloat()
            mainRoot.alpha = 0.9f
        }
        content.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(160).start()
    }
}
