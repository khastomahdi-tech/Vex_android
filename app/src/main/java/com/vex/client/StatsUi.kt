package com.vex.client

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * صفحه‌ی آمار (تب «آمار»): سرعت لحظه‌ای، مجموع ترافیک جلسه،
 * وضعیت اتصال، سرور فعال، مدت جلسه + دکمه‌های تست اینترنت و جزئیات خطا.
 */
class StatsUi(private val act: MainActivity) {

    lateinit var root: LinearLayout
        private set

    private lateinit var dnSpeed: TextView
    private lateinit var upSpeed: TextView
    private lateinit var dnTotal: TextView
    private lateinit var upTotal: TextView
    private lateinit var stateText: TextView
    private lateinit var serverText: TextView
    private lateinit var sessionText: TextView
    private lateinit var testBtn: TextView
    private lateinit var errBtn: TextView

    private var p: Palette = Themes.royal()

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

    /** یک ردیف «عنوان: مقدار» داخل کارت وضعیت؛ خروجی = TextView مقدار */
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
        render()
    }
}
