package com.vex.client

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat

/** فونت فارسی وزیرمتن — مطابق تایپوگرافی طرح مرجع VEX */
object Fonts {
    @Volatile private var reg: Typeface? = null
    @Volatile private var bold: Typeface? = null

    fun regular(ctx: Context): Typeface {
        val c = reg
        if (c != null) return c
        return try {
            ResourcesCompat.getFont(ctx, R.font.vazir_regular)?.also { reg = it } ?: Typeface.DEFAULT
        } catch (_: Throwable) { Typeface.DEFAULT }
    }

    fun bold(ctx: Context): Typeface {
        val c = bold
        if (c != null) return c
        return try {
            ResourcesCompat.getFont(ctx, R.font.vazir_bold)?.also { bold = it } ?: regular(ctx)
        } catch (_: Throwable) { regular(ctx) }
    }
}

/** پالت رنگی یک تم */
data class Palette(
    val bg: Int,
    val bg2: Int,
    val card: Int,
    val card2: Int,
    val line: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val good: Int,
    val mid: Int,
    val slow: Int,
    val bad: Int,
    val glow: Int,
    val isShir: Boolean
)

/** تم‌های برنامه: روشن / تیره / شیر و خورشید / سیستم */
object Themes {

    fun light() = Palette(
        bg = 0xFFEEF2FA.toInt(), bg2 = 0xFFFFFFFF.toInt(),
        card = 0xFFFFFFFF.toInt(), card2 = 0xFFF2F5FC.toInt(),
        line = 0xFFE0E6F2.toInt(), text = 0xFF152038.toInt(), muted = 0xFF525E7A.toInt(),
        accent = 0xFF3F6EF0.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        good = 0xFF149A66.toInt(), mid = 0xFFC57F00.toInt(), slow = 0xFFD4531F.toInt(),
        bad = 0xFFD62E56.toInt(), glow = 0x294F7CFF, isShir = false
    )

    fun dark() = Palette(
        bg = 0xFF060A14.toInt(), bg2 = 0xFF0C1426.toInt(),
        card = 0xFF111B31.toInt(), card2 = 0xFF17233F.toInt(),
        line = 0xFF1F2D4D.toInt(), text = 0xFFEEF3FF.toInt(), muted = 0xFFA5B2D2.toInt(),
        accent = 0xFF5F88FF.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        good = 0xFF3BD487.toInt(), mid = 0xFFF6A821.toInt(), slow = 0xFFFF7A59.toInt(),
        bad = 0xFFFF5470.toInt(), glow = 0x3D4F7CFF, isShir = false
    )

    fun shir() = Palette(
        bg = 0xFF100C05.toInt(), bg2 = 0xFF17120A.toInt(),
        card = 0xFF1C1509.toInt(), card2 = 0xFF261D0E.toInt(),
        line = 0xFF3E3015.toInt(), text = 0xFFFFF3D6.toInt(), muted = 0xFFCDBB8E.toInt(),
        accent = 0xFFE8B923.toInt(), onAccent = 0xFF221A05.toInt(),
        good = 0xFF9ACD64.toInt(), mid = 0xFFE8B923.toInt(), slow = 0xFFE88B3B.toInt(),
        bad = 0xFFE85D5D.toInt(), glow = 0x47E8B923, isShir = true
    )

    /**
     * تم سلطنتی VEX — مشکی خالص با طلایی حروف‌نگاری‌شده (شیر بالدار و تاج).
     * دقیقاً مطابق طرح مرجع: پس‌زمینه مشکی، کارت‌های مشکی با حاشیه‌ی طلایی،
     * اکسنت طلایی E8B923 و متن‌های کرم‌رنگ گرم.
     */
    fun royal() = Palette(
        bg = 0xFF000000.toInt(), bg2 = 0xFF080704.toInt(),
        card = 0xFF0B0906.toInt(), card2 = 0xFF171204.toInt(),
        line = 0xFF856A22.toInt(), text = 0xFFF3E5C6.toInt(), muted = 0xFFC6A96A.toInt(),
        accent = 0xFFE8B923.toInt(), onAccent = 0xFF1A1304.toInt(),
        good = 0xFF6FCF5A.toInt(), mid = 0xFFE8B923.toInt(), slow = 0xFFE88B3B.toInt(),
        bad = 0xFFE85D5D.toInt(), glow = 0x59E8B923, isShir = false
    )

    /**
     * تم «سلطنتی روشن» — حالت روزِ تم سلطنتی طلایی:
     * پس‌زمینه‌ی کرم گرم، کارت‌های روشن با حاشیه‌ی طلایی ملایم و اکسنت طلایی.
     * دکمه‌ی خورشید هدر بین این تم و تم سلطنتیِ شب جابه‌جا می‌شود.
     */
    fun royalLight() = Palette(
        bg = 0xFFFBF4E2.toInt(), bg2 = 0xFFFFFCF3.toInt(),
        card = 0xFFFFFFFF.toInt(), card2 = 0xFFF8EED7.toInt(),
        line = 0xFFE6D5A6.toInt(), text = 0xFF2B220E.toInt(), muted = 0xFF8B7648.toInt(),
        accent = 0xFFD9A521.toInt(), onAccent = 0xFF1A1304.toInt(),
        good = 0xFF149A66.toInt(), mid = 0xFFC57F00.toInt(), slow = 0xFFD4531F.toInt(),
        bad = 0xFFD62E56.toInt(), glow = 0x40D9A521, isShir = false
    )

    /** رنگ طلاییِ کم‌رنگ برای آیکون‌های غیرفعال نوار پایین */
    val goldMuted: Int = 0xFFC9A94E.toInt()

    /** آیا پس‌زمینه‌ی این تم روشن است؟ (برای تنظیم خودکار رنگ عنوان‌ها) */
    fun isLightBg(p: Palette): Boolean {
        val r = (p.bg shr 16) and 0xFF
        val g = (p.bg shr 8) and 0xFF
        val b = p.bg and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 140.0
    }

    /** انتخاب پالت بر اساس تنظیمات و وضعیت تیره/روشن سیستم */
    fun resolve(context: Context, setting: String): Palette = when (setting) {
        "light" -> light()
        "dark" -> dark()
        "shir" -> shir()
        "royal" -> royal()
        "royal_light" -> royalLight()
        // تم «سیستم» واقعاً از تیره/روشن اندروید پیروی می‌کند
        "system" -> {
            val mode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES) dark() else light()
        }
        // تم پیش‌فرض برنامه = سلطنتی طلایی (شب)
        else -> royal()
    }
}

/** ابزارهای مشترک ساخت UI نیتیو */
object UI {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    fun card(context: Context, p: Palette, radiusDp: Int = 18, stroke: Int? = null, fillColor: Int? = null, strokeW: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fillColor ?: p.card)
            if (stroke != null) setStroke(dp(context, if (strokeW <= 1f) 1 else if (strokeW <= 1.5f) 2 else 3), stroke)
        }

    fun label(context: Context, text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) Fonts.bold(context) else Fonts.regular(context)
        }

    fun pill(context: Context, text: String, bg: Int, fg: Int, bold: Boolean = true): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(fg)
            typeface = if (bold) Fonts.bold(context) else Fonts.regular(context)
            gravity = Gravity.CENTER
            setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(bg)
            }
        }

    /** دکمه‌ی پررنگ (accent) */
    fun primaryButton(context: Context, text: String, p: Palette): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.onAccent)
            gravity = Gravity.CENTER
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat()
                setColor(p.accent)
            }
        }

    /** پس‌زمینه‌ی دکمه‌ی پررنگ */
    fun primaryBg(context: Context, p: Palette): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, 14).toFloat()
            setColor(p.accent)
        }

    /** دکمه‌ی ملایم (کارت رنگی) */
    fun softButton(context: Context, text: String, p: Palette): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
            gravity = Gravity.CENTER
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat()
                setColor(p.card2)
                setStroke(dp(context, 1), p.line)
            }
        }

    /** پس‌زمینه‌ی دکمه‌ی شناور «+» — دایره‌ی تیره با حلقه‌ی طلایی */
    fun fabBg(context: Context, p: Palette): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.card2)
            setStroke(dp(context, 2), p.accent)
        }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    fun col(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    fun toast(context: Context, msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    fun fmtBytes(n: Long): String {
        if (n < 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
        return if (i == 0) "${n} B"
        else java.lang.String.format(java.util.Locale.US, "%.2f %s", v, units[i])
    }

    fun pingColor(p: Palette, ms: Int): Int = when {
        ms == -1 || ms == -2 -> p.muted
        ms < 150 -> p.good
        ms < 300 -> p.mid
        else -> p.slow
    }

    fun pingText(ms: Int): String = when {
        ms == -1 -> "—"
        ms == -2 -> "×"
        else -> "$ms ms"
    }

    fun protoLabel(proto: String): String = when (proto.lowercase()) {
        "shadowsocks", "ss" -> "SS"
        "hysteria2", "hy2" -> "HY2"
        "wireguard", "wg" -> "WG"
        "vmess" -> "VMESS"
        else -> proto.uppercase()
    }

    fun alpha(argb: Int, a: Float): Int {
        val base = argb and 0x00FFFFFF
        val ai = (a.coerceIn(0f, 1f) * 255).toInt()
        return (ai shl 24) or base
    }
}
