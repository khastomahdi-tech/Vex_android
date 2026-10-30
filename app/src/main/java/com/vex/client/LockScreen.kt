package com.vex.client

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/**
 * قفل برنامه با رمز (PIN) — کیبورد اختصاصی و شیک خود برنامه:
 *
 *  • showLock   → هنگام ورود به برنامه (اگر رمز گذاشته شده باشد)
 *  • showSetup  → گذاشتن رمز جدید از تنظیمات (رمز + تکرار رمز)
 *  • showVerify → گرفتن رمز فعلی (برداشتن/تغییر رمز)
 *
 *  همه‌ی حالت‌ها با همان کیبورد گرد طلایی، نقاط وضعیت و لرزش خطا — بدون کیبورد سیستم.
 *  رمز به‌صورت SHA-256 ذخیره می‌شود و هرگز متن خامش جایی نمی‌ماند.
 */
object LockScreen {

    private const val MIN_LEN = 4
    private const val MAX_LEN = 8

    /** آیا الان صفحه‌ی قفل/رمز روی صفحه است؟ (دکمه‌ی Back بی‌اثر می‌شود) */
    @Volatile
    var isActive = false
        private set

    fun sha256(s: String): String = try {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        d.joinToString("") { String.format("%02x", it) }
    } catch (_: Throwable) { s }

    // -------------------------------------------------------------- قفل ورود

    /** قفل ورود — تا رمز درست وارد نشود برنامه باز نمی‌شود */
    fun showLock(act: MainActivity, onUnlock: () -> Unit) {
        val expectedLen = act.store.settings.pinLen.takeIf { it in MIN_LEN..MAX_LEN } ?: 0
        buildFlow(act, title = "رمز ورود را وارد کن", subtitle = "برای باز شدن Vex، رمز برنامه را بزن") {
            ctx, p, root, dots, hint, input ->
            var buf = ""
            fun submit() {
                if (buf.isEmpty()) return
                if (sha256(buf) == act.store.settings.pinHash) {
                    deactivate()
                    onUnlock()
                } else {
                    renderDots(dots, buf.length, MAX_LEN, p, error = true)
                    hint.text = "رمز اشتباه است — دوباره تلاش کن"
                    hint.setTextColor(p.bad)
                    shake(dots)
                    buf = ""
                    Handler(Looper.getMainLooper()).postDelayed({
                        renderDots(dots, 0, MAX_LEN, p, error = false)
                    }, 620)
                }
            }
            fun refresh() {
                renderDots(dots, buf.length, MAX_LEN, p, error = false)
                if (expectedLen > 0 && buf.length == expectedLen) submit()
            }
            buildKeypad(ctx, p, root,
                onDigit = { d -> if (buf.length < MAX_LEN) { buf += d; refresh() } },
                onBack = { if (buf.isNotEmpty()) { buf = buf.dropLast(1); refresh() } },
                onSubmit = { if (expectedLen == 0) submit() else { if (buf.isNotEmpty()) submit() } }
            )
        }
    }

    // -------------------------------------------------------------- گذاشتن رمز

    /** گذاشتن رمز جدید — دو مرحله: رمز + تکرار. onDone(hash, len) */
    fun showSetup(act: MainActivity, onDone: (String, Int) -> Unit, onCancel: () -> Unit) {
        val first = StringBuilder()
        buildFlow(act, title = "رمز جدید را وارد کن", subtitle = "۴ تا ۸ رقم — با کیبورد خود برنامه") {
            ctx, p, root, dots, hint, input ->
            var buf = ""
            var stage = 0 // 0 = رمز اول، 1 = تکرار
            fun refresh() {
                renderDots(dots, buf.length, MAX_LEN, p, error = false)
                input.text = buf.length.toString() + " رقم"
                input.setTextColor(p.muted)
            }
            fun next() {
                if (buf.length < MIN_LEN) {
                    hint.text = "حداقل $MIN_LEN رقم لازم است"
                    hint.setTextColor(p.bad)
                    shake(dots)
                    return
                }
                if (stage == 0) {
                    first.clear(); first.append(buf)
                    stage = 1; buf = ""
                    input.text = "حالا تکرار همان رمز را بزن"
                    refresh()
                } else {
                    if (buf.toString() == first.toString()) {
                        deactivate()
                        onDone(sha256(first.toString()), first.length)
                    } else {
                        stage = 0; buf = ""
                        input.text = "رمزها یکی نبود — از اول"
                        refresh()
                        renderDots(dots, buf.length, MAX_LEN, p, error = true)
                        shake(dots)
                    }
                }
            }
            buildKeypad(ctx, p, root,
                onDigit = { d -> if (buf.length < MAX_LEN) { buf += d; refresh() } },
                onBack = { if (buf.isNotEmpty()) { buf = buf.dropLast(1); refresh() } },
                onSubmit = { next() }
            )
            // دکمه‌ی انصراف
            val cancel = TextView(ctx).apply {
                text = "انصراف از گذاشتن رمز"
                textSize = 13f
                typeface = Fonts.bold(ctx)
                setTextColor(p.muted)
                gravity = Gravity.CENTER
                setPadding(0, UI.dp(ctx, 16), 0, 0)
            }
            cancel.setOnClickListener {
                deactivate()
                onCancel()
            }
            root.addView(cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
    }

    // -------------------------------------------------------------- تأیید رمز فعلی

    /** گرفتن رمز فعلی برای تغییر/برداشتن قفل */
    fun showVerify(act: MainActivity, title: String, onOk: () -> Unit, onCancel: () -> Unit) {
        buildFlow(act, title = title, subtitle = "رمز فعلی برنامه را وارد کن") {
            ctx, p, root, dots, hint, input ->
            var buf = ""
            fun refresh() {
                renderDots(dots, buf.length, MAX_LEN, p, error = false)
            }
            fun submit() {
                if (buf.isEmpty()) return
                if (sha256(buf) == act.store.settings.pinHash) {
                    deactivate()
                    onOk()
                } else {
                    renderDots(dots, buf.length, MAX_LEN, p, error = true)
                    hint.text = "رمز اشتباه است"
                    hint.setTextColor(p.bad)
                    shake(dots)
                    buf = ""
                    Handler(Looper.getMainLooper()).postDelayed({
                        renderDots(dots, 0, MAX_LEN, p, error = false)
                    }, 620)
                }
            }
            buildKeypad(ctx, p, root,
                onDigit = { d -> if (buf.length < MAX_LEN) { buf += d; refresh() } },
                onBack = { if (buf.isNotEmpty()) { buf = buf.dropLast(1); refresh() } },
                onSubmit = { submit() }
            )
            val cancel = TextView(ctx).apply {
                text = "انصراف"
                textSize = 13f
                typeface = Fonts.bold(ctx)
                setTextColor(p.muted)
                gravity = Gravity.CENTER
                setPadding(0, UI.dp(ctx, 16), 0, 0)
            }
            cancel.setOnClickListener {
                deactivate()
                onCancel()
            }
            root.addView(cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
    }

    // -------------------------------------------------------------- سازنده‌های مشترک

    private fun deactivate() { isActive = false }

    /**
     * اسکلت مشترک همه‌ی حالت‌ها: پس‌زمینه‌ی تم فعلی، تاج طلایی، عنوان، نقاط،
     * برچسب وضعیت و کیبورد. بعد از ساخت، block با اجزا صدا زده می‌شود.
     */
    private fun buildFlow(
        act: MainActivity,
        title: String,
        subtitle: String,
        block: (Context, Palette, LinearLayout, LinearLayout, TextView, TextView) -> Unit
    ) {
        val ctx = act
        isActive = true
        val p = try { Themes.resolve(ctx, act.store.settings.theme) } catch (_: Throwable) { Themes.dark() }

        val root = UI.col(ctx).apply { gravity = Gravity.CENTER }
        root.setBackgroundColor(p.bg)
        root.setPadding(UI.dp(ctx, 24), UI.dp(ctx, 30), UI.dp(ctx, 24), UI.dp(ctx, 30))

        // تاج طلایی داخل دایره‌ی گرادیانی
        val icBox = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFFF0C64B.toInt(), 0xFFB9861B.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(UI.dp(ctx, 1), UI.alpha(0xFFF7DE8A.toInt(), 0.7f))
            }
        }
        val ic = IconView(ctx, "crown").apply {
            iconColor = 0xFF1A1304.toInt(); bold = true
        }
        icBox.addView(ic, FrameLayout.LayoutParams(UI.dp(ctx, 30), UI.dp(ctx, 30), Gravity.CENTER))
        root.addView(icBox, LinearLayout.LayoutParams(
            UI.dp(ctx, 78), UI.dp(ctx, 78)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // نام برنامه
        val nameRow = UI.row(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 12), 0, 0)
        }
        nameRow.addView(TextView(ctx).apply {
            text = "Vex"
            textSize = 21f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(if (Themes.isLightBg(p)) 0xFF111111.toInt() else 0xFFF5F7FF.toInt())
        })
        nameRow.addView(TextView(ctx).apply {
            text = " Client"
            textSize = 16f
            typeface = Fonts.bold(ctx)
            setTextColor(p.accent)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(nameRow)

        // عنوان و زیرعنوان
        val tv = TextView(ctx).apply {
            text = title
            textSize = 17f
            typeface = Fonts.bold(ctx)
            setTextColor(p.text)
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 22), 0, 0)
        }
        root.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val stv = TextView(ctx).apply {
            text = subtitle
            textSize = 13f
            setTextColor(p.muted)
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 6), 0, 0)
        }
        root.addView(stv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // نقاط وضعیت
        val dots = UI.row(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 20), 0, 0)
        }
        root.addView(dots, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 18)))

        // برچسب وضعیت (طول رمز / خطا)
        val input = TextView(ctx).apply {
            text = "—"
            textSize = 12.5f
            typeface = Fonts.bold(ctx)
            setTextColor(p.muted)
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 8), 0, 0)
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        renderDots(dots, 0, MAX_LEN, p, error = false)
        block(ctx, p, root, dots, stv, input)

        act.setContentView(root)
    }

    /** نقاط ورود: پرشده = اکسنت طلایی، خالی = حلقه‌ی محو، خطا = قرمز */
    private fun renderDots(dots: LinearLayout, filled: Int, total: Int, p: Palette, error: Boolean) {
        if (dots.childCount != total) {
            dots.removeAllViews()
            for (i in 0 until total) {
                val d = View(dots.context)
                dots.addView(d, LinearLayout.LayoutParams(UI.dp(dots.context, 13), UI.dp(dots.context, 13)).apply {
                    marginEnd = UI.dp(dots.context, 9)
                })
            }
        }
        for (i in 0 until dots.childCount) {
            val d = dots.getChildAt(i)
            val on = i < filled
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    error && on -> setColor(p.bad)
                    on -> setColor(p.accent)
                    error -> setColor(UI.alpha(p.bad, 0.35f))
                    else -> {
                        setColor(Color.TRANSPARENT)
                        setStroke(UI.dp(dots.context, 2), UI.alpha(p.muted, 0.55f))
                    }
                }
            }
        }
    }

    /** لرزش افقی نقاط هنگام خطا */
    private fun shake(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, -20f, 20f, -13f, 13f, -7f, 7f, 0f)
            .setDuration(430).start()
    }

    /**
     * کیبورد گرد اختصاصی: ۱..۹ + پاک‌کن + صفر + تأیید.
     * دکمه‌ها دایره‌های طلایی‌قاب با جهش نرم هنگام لمس و بازخورد لرزشی.
     */
    private fun buildKeypad(
        ctx: Context,
        p: Palette,
        root: LinearLayout,
        onDigit: (Int) -> Unit,
        onBack: () -> Unit,
        onSubmit: () -> Unit
    ) {
        fun keyBg(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.card2)
            setStroke(UI.dp(ctx, 1), p.line)
        }

        fun key(label: String, col: Int): FrameLayout {
            val b = FrameLayout(ctx).apply {
                background = keyBg()
                isClickable = true
                isFocusable = false
            }
            val t = TextView(ctx).apply {
                text = label
                textSize = 23f
                typeface = Fonts.bold(ctx)
                setTextColor(p.text)
                gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_LTR
            }
            b.addView(t, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            b.setOnClickListener {
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                ObjectAnimator.ofFloat(b, "scaleX", 0.9f, 1f).setDuration(150).start()
                ObjectAnimator.ofFloat(b, "scaleY", 0.9f, 1f).setDuration(150).start()
                when (col) {
                    1 -> onDigit(label.toIntOrNull() ?: return@setOnClickListener)
                    2 -> onBack()
                    else -> onSubmit()
                }
            }
            return b
        }

        fun iconKey(icon: String, col: Int, tint: Int): FrameLayout {
            val b = FrameLayout(ctx).apply {
                background = keyBg()
                isClickable = true
                isFocusable = false
            }
            val ic = IconView(ctx, icon).apply {
                iconColor = tint
                bold = true
            }
            b.addView(ic, FrameLayout.LayoutParams(UI.dp(ctx, 24), UI.dp(ctx, 24), Gravity.CENTER))
            b.setOnClickListener {
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                ObjectAnimator.ofFloat(b, "scaleX", 0.9f, 1f).setDuration(150).start()
                ObjectAnimator.ofFloat(b, "scaleY", 0.9f, 1f).setDuration(150).start()
                if (col == 2) onBack() else onSubmit()
            }
            return b
        }

        val pad = UI.col(ctx).apply { setPadding(0, UI.dp(ctx, 22), 0, 0) }
        val rows = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9")
        )
        for (r in rows) {
            val row = UI.row(ctx).apply { gravity = Gravity.CENTER }
            for (c in r) {
                row.addView(key(c, 1), LinearLayout.LayoutParams(
                    UI.dp(ctx, 66), UI.dp(ctx, 66)).apply {
                    setMargins(UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9))
                })
            }
            pad.addView(row)
        }
        // ردیف آخر: پاک‌کن | ۰ | تأیید
        val last = UI.row(ctx).apply { gravity = Gravity.CENTER }
        last.addView(iconKey("backspace", 2, p.muted), LinearLayout.LayoutParams(
            UI.dp(ctx, 66), UI.dp(ctx, 66)).apply {
            setMargins(UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9))
        })
        last.addView(key("0", 1), LinearLayout.LayoutParams(
            UI.dp(ctx, 66), UI.dp(ctx, 66)).apply {
            setMargins(UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9))
        })
        last.addView(iconKey("check", 0, p.accent), LinearLayout.LayoutParams(
            UI.dp(ctx, 66), UI.dp(ctx, 66)).apply {
            setMargins(UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9), UI.dp(ctx, 9))
        })
        pad.addView(last)
        root.addView(pad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }
}
