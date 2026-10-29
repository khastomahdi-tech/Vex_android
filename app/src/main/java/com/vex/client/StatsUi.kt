package com.vex.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * صفحه‌ی آمار پیشرفته (تب «آمار»):
 *  سرعت لحظه‌ای دانلود/آپلود + نمودار زنده‌ی ۶۰ ثانیه‌ای سرعت،
 *  اوج و میانگین سرعت این جلسه، مجموع ترافیک جلسه (دانلود/آپلود/کل)،
 *  وضعیت اتصال، سرور فعال، مدت جلسه + دکمه‌های تست اینترنت و جزئیات خطا.
 */
class StatsUi(private val act: MainActivity) {

    lateinit var root: LinearLayout
        private set

    private lateinit var dnSpeed: TextView
    private lateinit var upSpeed: TextView
    private lateinit var dnTotal: TextView
    private lateinit var upTotal: TextView
    private lateinit var allTotal: TextView
    private lateinit var peakText: TextView
    private lateinit var avgText: TextView
    private lateinit var graph: SpeedGraphView
    private lateinit var stateText: TextView
    private lateinit var serverText: TextView
    private lateinit var sessionText: TextView
    private lateinit var testBtn: TextView
    private lateinit var errBtn: TextView

    private var p: Palette = Themes.royal()
    private var peakDn: Long = 0
    private var peakUp: Long = 0
    private var sumDn: Long = 0
    private var sumUp: Long = 0
    private var samples: Int = 0

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = UI.col(ctx)
        root.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 10), UI.dp(ctx, 16), UI.dp(ctx, 14))
        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }

        // ================= سرعت لحظه‌ای =================
        val speedCard = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 16))
        }
        speedCard.addView(sectionLabel(ctx, "سرعت لحظه‌ای"))
        val speedRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }
        fun speedCol(lbl: String, tv: TextView, ic: String): LinearLayout {
            val c = UI.col(ctx).apply { gravity = Gravity.CENTER }
            val icon = IconView(ctx, ic).apply { iconColor = p.accent; bold = true }
            c.addView(icon, LinearLayout.LayoutParams(UI.dp(ctx, 22), UI.dp(ctx, 22)))
            tv.gravity = Gravity.CENTER
            tv.textDirection = View.TEXT_DIRECTION_LTR
            tv.textSize = 19f
            tv.typeface = Fonts.bold(act)
            tv.setPadding(0, UI.dp(ctx, 6), 0, 0)
            c.addView(tv)
            val l = UI.label(ctx, lbl, 12f, p.muted)
            l.gravity = Gravity.CENTER
            l.setPadding(0, UI.dp(ctx, 3), 0, 0)
            c.addView(l)
            return c
        }
        dnSpeed = TextView(ctx); upSpeed = TextView(ctx)
        speedRow.addView(speedCol("دانلود", dnSpeed, "down"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedRow.addView(speedCol("آپلود", upSpeed, "up"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedCard.addView(speedRow)
        addCard(speedCard)

        // ================= نمودار زنده =================
        val graphCard = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 16))
        }
        graphCard.addView(sectionLabel(ctx, "نمودار سرعت (۶۰ ثانیه اخیر)"))
        graph = SpeedGraphView(ctx)
        graph.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 120)).apply {
            setMargins(0, UI.dp(ctx, 10), 0, 0)
        }
        graphCard.addView(graph)
        val legend = UI.row(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 8), 0, 0)
        }
        fun legendItem(color: Int, label: String): View {
            val r = UI.row(ctx)
            val dot = View(ctx)
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            r.addView(dot, LinearLayout.LayoutParams(UI.dp(ctx, 9), UI.dp(ctx, 9)))
            val t = UI.label(ctx, label, 11.5f, p.muted)
            t.setPadding(UI.dp(ctx, 5), 0, 0, 0)
            r.addView(t)
            return r
        }
        legend.addView(legendItem(p.accent, "دانلود"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 16)
        })
        legend.addView(legendItem(p.good, "آپلود"))
        graphCard.addView(legend)
        addCard(graphCard)

        // ================= اوج و میانگین =================
        val peakCard = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 16))
        }
        peakCard.addView(sectionLabel(ctx, "اوج و میانگین این جلسه"))
        peakText = twoColRow(peakCard, "اوج سرعت (دانلود / آپلود)")
        avgText = twoColRow(peakCard, "میانگین سرعت (دانلود / آپلود)")
        addCard(peakCard)

        // ================= مجموع جلسه =================
        val totalCard = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 16))
        }
        totalCard.addView(sectionLabel(ctx, "مجموع این جلسه"))
        val totalRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }
        fun totalCol(lbl: String, tv: TextView): LinearLayout {
            val c = UI.col(ctx).apply { gravity = Gravity.CENTER }
            tv.gravity = Gravity.CENTER
            tv.textDirection = View.TEXT_DIRECTION_LTR
            tv.textSize = 17f
            tv.typeface = Fonts.bold(act)
            c.addView(tv)
            val l = UI.label(ctx, lbl, 12f, p.muted)
            l.gravity = Gravity.CENTER
            l.setPadding(0, UI.dp(ctx, 3), 0, 0)
            c.addView(l)
            return c
        }
        dnTotal = TextView(ctx); upTotal = TextView(ctx)
        totalRow.addView(totalCol("دانلود", dnTotal),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        totalRow.addView(totalCol("آپلود", upTotal),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        totalCard.addView(totalRow)
        allTotal = twoColRow(totalCard, "مجموع کل")
        val hint = UI.label(ctx, "مجموع‌ها با هر اتصال جدید از صفر شمرده می‌شوند.", 11f, p.muted)
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, UI.dp(ctx, 10), 0, 0)
        totalCard.addView(hint)
        addCard(totalCard)

        // ================= وضعیت جلسه =================
        val stateCard = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 16))
        }
        stateCard.addView(sectionLabel(ctx, "وضعیت جلسه"))
        stateText = infoRow(stateCard, "وضعیت")
        serverText = infoRow(stateCard, "سرور فعال")
        sessionText = infoRow(stateCard, "مدت اتصال")
        addCard(stateCard)

        // ================= دکمه‌ها =================
        val btnRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 12), 0, 0) }
        testBtn = UI.softButton(ctx, "تست اینترنت", p)
        testBtn.setOnClickListener { act.testInternetNow() }
        errBtn = UI.softButton(ctx, "جزئیات خطا", p)
        errBtn.setOnClickListener { act.showErrDetails() }
        testBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UI.dp(ctx, 6)
        }
        errBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(ctx, 6)
        }
        btnRow.addView(testBtn); btnRow.addView(errBtn)
        root.addView(btnRow)

        scroll.addView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.isFillViewport = true
        applyTheme(p)
        return scroll
    }

    /** یک ردیف «عنوان: مقدار»؛ خروجی = TextView مقدار */
    private fun infoRow(card: LinearLayout, title: String): TextView {
        val ctx = act
        val row = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 9), 0, 0) }
        val t = UI.label(ctx, title, 13f, p.muted)
        t.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val v = UI.label(ctx, "—", 13f, p.text, bold = true).apply {
            gravity = Gravity.RIGHT
            maxLines = 2
        }
        v.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(t)
        row.addView(v)
        card.addView(row)
        return v
    }

    /** ردیف دو ستونی عنوان/مقدار — بازگشت TextView مقدار */
    private fun twoColRow(card: LinearLayout, title: String): TextView {
        val ctx = act
        val row = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 9), 0, 0) }
        val t = UI.label(ctx, title, 13f, p.muted)
        t.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val v = UI.label(ctx, "—", 13.5f, p.text, bold = true).apply {
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_LTR
        }
        v.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(t)
        row.addView(v)
        card.addView(row)
        return v
    }

    private fun addCard(view: View) {
        val ctx = act
        val wrap = LinearLayout(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }
        view.background = UI.card(ctx, p)
        wrap.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(wrap)
    }

    private fun sectionLabel(ctx: Context, text: String): TextView =
        UI.label(ctx, text, 16f, p.accent, bold = true)

    // ---------------------------------------------------------- رندر

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        for (i in 0 until root.childCount) {
            val wrap = root.getChildAt(i)
            if (wrap is LinearLayout && wrap.childCount == 1) {
                wrap.getChildAt(0).background = UI.card(ctx, p)
            }
        }
        testBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        testBtn.setTextColor(p.text)
        errBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        errBtn.setTextColor(p.text)
        graph.palette = p
        render()
    }

    fun render() {
        val st = act.connectionState
        stateText.text = when (st) {
            "connected" -> if (act.netState == 2) "متصل — بدون اینترنت" else "متصل است"
            "connecting" -> "در حال اتصال…"
            else -> "قطع است"
        }
        stateText.setTextColor(when (st) {
            "connected" -> p.good
            "connecting" -> p.mid
            else -> p.muted
        })
        val s = act.store.selected()
        serverText.text = if (s != null) "${s.name} · ${UI.protoLabel(s.protocol)}" else "—"
        val started = VexVpnService.connectedAtMs
        sessionText.text = if (st == "connected" && started > 0) {
            val sec = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0)
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val ss = sec % 60
            if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, ss)
            else String.format(java.util.Locale.US, "%02d:%02d", m, ss)
        } else "—"
    }

    /** به‌روزرسانی هر ثانیه از سرویس */
    fun onStats(dn: Long, up: Long, tdn: Long, tup: Long) {
        dnSpeed.text = "${UI.fmtBytes(dn)}/s"
        upSpeed.text = "${UI.fmtBytes(up)}/s"
        dnTotal.text = UI.fmtBytes(tdn)
        upTotal.text = UI.fmtBytes(tup)
        allTotal.text = UI.fmtBytes(tdn + tup)

        // اوج و میانگین
        if (dn > peakDn) peakDn = dn
        if (up > peakUp) peakUp = up
        sumDn += dn; sumUp += up; samples++
        peakText.text = "${UI.fmtBytes(peakDn)}/s · ${UI.fmtBytes(peakUp)}/s"
        avgText.text = if (samples > 0)
            "${UI.fmtBytes(sumDn / samples)}/s · ${UI.fmtBytes(sumUp / samples)}/s"
        else "—"

        graph.addSample(dn.toFloat(), up.toFloat())
        render()
    }

    /** ریست آمار وقتی اتصال از نو برقرار می‌شود */
    fun resetSession() {
        peakDn = 0; peakUp = 0; sumDn = 0; sumUp = 0; samples = 0
        peakText.text = "—"; avgText.text = "—"
        graph.clear()
    }
}

/**
 * نمودار زنده‌ی سرعت — ۶۰ نمونه‌ی آخر:
 *  دانلود = ناحیه‌ی پرشده با گرادیان؛ آپلود = خط منحنی؛
 *  خطوط شبکه‌ی افقی محو برای مقیاس.
 */
class SpeedGraphView(context: Context) : View(context) {

    var palette: Palette = Themes.dark()
        set(value) { field = value; invalidate() }

    private val n = 60
    private val dnData = FloatArray(n)
    private val upData = FloatArray(n)
    private var head = 0
    private var filled = 0
    private var maxVal = 1f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f
    }
    private val upLinePaint = Paint(linePaint)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun addSample(dn: Float, up: Float) {
        dnData[head] = dn
        upData[head] = up
        head = (head + 1) % n
        if (filled < n) filled++
        // max نمونه‌ها (کف ۱ کیلوبایت تا نمودار صFlat نشود)
        var m = 1024f
        for (i in 0 until filled) {
            if (dnData[i] > m) m = dnData[i]
            if (upData[i] > m) m = upData[i]
        }
        maxVal = m * 1.15f
        invalidate()
    }

    fun clear() {
        dnData.fill(0f); upData.fill(0f)
        head = 0; filled = 0; maxVal = 1024f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = palette
        val padB = UI.dp(context, 6).toFloat()
        val chartH = h - padB - UI.dp(context, 4)

        // خطوط شبکه
        gridPaint.color = UI.alpha(p.line, 0.8f)
        for (g in 1..3) {
            val y = chartH * g / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (filled < 2) return

        // مسیرها (از قدیمی به جدید، چپ به راست)
        fun buildPath(data: FloatArray): Path {
            val path = Path()
            val count = filled
            val stepX = w / (n - 1).coerceAtLeast(1)
            val startX = w - (count - 1) * stepX
            path.moveTo(startX, chartH - (data[(head - count + n) % n] / maxVal) * chartH)
            for (i in 1 until count) {
                val idx = (head - count + i + n) % n
                val x = startX + i * stepX
                val y = chartH - (data[idx] / maxVal).coerceIn(0f, 1f) * chartH
                path.lineTo(x, y)
            }
            return path
        }

        // دانلود: ناحیه‌ی پرشده
        val dnPath = buildPath(dnData)
        val area = Path(dnPath)
        val lastIdx = (head - 1 + n) % n
        val stepX = w / (n - 1).coerceAtLeast(1)
        val endX = w
        val startX = w - (filled - 1) * stepX
        area.lineTo(endX, chartH)
        area.lineTo(startX, chartH)
        area.close()
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, chartH,
            UI.alpha(p.accent, 0.38f), UI.alpha(p.accent, 0.02f), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fillPaint)
        fillPaint.shader = null

        // خط دانلود
        linePaint.color = p.accent
        linePaint.strokeWidth = UI.dp(context, 2.4f).toFloat()
        canvas.drawPath(dnPath, linePaint)

        // خط آپلود
        upLinePaint.color = p.good
        upLinePaint.strokeWidth = UI.dp(context, 2f).toFloat()
        canvas.drawPath(buildPath(upData), upLinePaint)

        // نقطه‌ی فعلی دانلود
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
        val yNow = chartH - (dnData[lastIdx] / maxVal).coerceIn(0f, 1f) * chartH
        canvas.drawCircle(endX - UI.dp(context, 2), yNow, UI.dp(context, 3.2f), dotPaint)
    }
}
