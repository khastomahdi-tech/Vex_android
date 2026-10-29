package com.vex.client

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * صفحه‌ی اصلی نیتیو: دکمه‌ی پاور، وضعیت، آمار ترافیک، کارت سرور فعال.
 * بدون WebView — کاملاً Kotlin.
 */
class HomeUi(private val act: MainActivity) {

    lateinit var root: LinearLayout
        private set

    private lateinit var power: PowerButtonView
    private lateinit var stateText: TextView
    private lateinit var netText: TextView
    private lateinit var statsCard: LinearLayout
    private lateinit var dnSpeed: TextView
    private lateinit var upSpeed: TextView
    private lateinit var dnTotal: TextView
    private lateinit var upTotal: TextView
    private lateinit var serverCard: LinearLayout
    private lateinit var serverName: TextView
    private lateinit var serverSub: TextView
    private lateinit var serverPing: TextView
    private lateinit var serverBadge: TextView
    private lateinit var serverEmptyHint: TextView
    private lateinit var testBtn: TextView
    private lateinit var detailsBtn: TextView
    private lateinit var versionText: TextView

    private var p: Palette = Themes.dark()

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = UI.col(ctx)
        root.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 12))

        // ---- دکمه‌ی پاور
        power = PowerButtonView(ctx)
        val pw = UI.dp(ctx, 188)
        val powerWrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        power.layoutParams = LinearLayout.LayoutParams(pw, pw)
        powerWrap.addView(power)
        power.setOnClickListener { act.togglePower() }
        root.addView(powerWrap)

        // ---- وضعیت
        stateText = TextView(ctx).apply {
            textSize = 23f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 16), 0, UI.dp(ctx, 2))
        }
        root.addView(stateText)

        netText = TextView(ctx).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(UI.dp(ctx, 10), 0, UI.dp(ctx, 10), 0)
        }
        root.addView(netText)

        // ---- کارت آمار
        statsCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 12), UI.dp(ctx, 16), UI.dp(ctx, 12))
        }
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        fun statCol(lbl: String, tv: TextView): LinearLayout {
            val c = UI.col(ctx)
            c.gravity = Gravity.CENTER
            val l = UI.label(ctx, lbl, 11.5f, p.muted)
            l.gravity = Gravity.CENTER
            tv.gravity = Gravity.CENTER
            tv.textDirection = View.TEXT_DIRECTION_LTR
            tv.textSize = 15f
            tv.typeface = Typeface.DEFAULT_BOLD
            c.addView(l)
            c.addView(tv)
            return c
        }
        dnSpeed = TextView(ctx); upSpeed = TextView(ctx); dnTotal = TextView(ctx); upTotal = TextView(ctx)
        val c1 = statCol("سرعت دانلود", dnSpeed)
        val c2 = statCol("سرعت آپلود", upSpeed)
        val c3 = statCol("مجموع دانلود", dnTotal)
        val c4 = statCol("مجموع آپلود", upTotal)
        for (c in arrayOf(c1, c2, c3, c4)) c.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val row1 = UI.row(ctx); row1.addView(c1); row1.addView(c2)
        val row2 = UI.row(ctx); row2.addView(c3); row2.addView(c4)
        row2.setPadding(0, UI.dp(ctx, 10), 0, 0)
        statsCard.addView(row1)
        statsCard.addView(row2)
        val statsWrap = LinearLayout(ctx).apply { setPadding(0, UI.dp(ctx, 14), 0, 0) }
        statsWrap.addView(statsCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(statsWrap)

        // ---- کارت سرور فعال
        serverCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 12), UI.dp(ctx, 16), UI.dp(ctx, 12))
            setOnClickListener { act.gotoServers() }
        }
        val scRow = UI.row(ctx)
        serverBadge = UI.pill(ctx, "—", p.card2, p.muted)
        serverName = UI.label(ctx, "سروری انتخاب نشده", 16f, p.text, bold = true)
        serverName.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(ctx, 8)
        }
        serverPing = UI.pill(ctx, "—", p.card2, p.muted)
        scRow.addView(serverBadge)
        scRow.addView(serverName)
        scRow.addView(serverPing)
        serverSub = UI.label(ctx, "برای انتخاب سرور ضربه بزنید", 12f, p.muted)
        serverSub.setPadding(0, UI.dp(ctx, 6), 0, 0)
        serverEmptyHint = serverSub
        serverCard.addView(scRow)
        serverCard.addView(serverSub)
        val serverWrap = LinearLayout(ctx).apply { setPadding(0, UI.dp(ctx, 12), 0, 0) }
        serverWrap.addView(serverCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(serverWrap)

        // ---- دکمه‌های تست
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, UI.dp(ctx, 14), 0, 0)
        }
        testBtn = UI.softButton(ctx, "تست اینترنت", p)
        testBtn.setOnClickListener { act.testInternetNow() }
        detailsBtn = UI.softButton(ctx, "جزئیات خطا", p)
        detailsBtn.setOnClickListener { act.showErrDetails() }
        testBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UI.dp(ctx, 6)
        }
        detailsBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(ctx, 6)
        }
        btnRow.addView(testBtn); btnRow.addView(detailsBtn)
        root.addView(btnRow)

        versionText = UI.label(ctx, "VEX CLIENT v2.0.0 · هسته sing-box v1.11.15", 11f, p.muted)
        versionText.gravity = Gravity.CENTER
        versionText.setPadding(0, UI.dp(ctx, 18), 0, UI.dp(ctx, 4))
        root.addView(versionText)

        applyTheme(p)
        return ScrollView(ctx).also { sv ->
            sv.addView(root)
            sv.isFillViewport = true
            sv.overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        root.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 12))
        power.palette = p
        power.shir = p.isShir
        stateText.setTextColor(p.text)
        netText.setTextColor(p.muted)
        statsCard.background = UI.card(ctx, p)
        dnSpeed.setTextColor(p.text); upSpeed.setTextColor(p.text)
        dnTotal.setTextColor(p.text); upTotal.setTextColor(p.text)
        // رنگ لیبل‌ها داخل ستون‌ها
        colorStatLabels(statsCard, p)
        serverCard.background = UI.card(ctx, p)
        serverName.setTextColor(p.text)
        serverSub.setTextColor(p.muted)
        testBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        testBtn.setTextColor(p.text)
        detailsBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        detailsBtn.setTextColor(p.text)
        versionText.setTextColor(p.muted)
        renderServerCard()
    }

    private fun colorStatLabels(card: LinearLayout, p: Palette) {
        for (i in 0 until card.childCount) {
            val row = card.getChildAt(i)
            if (row is LinearLayout) {
                for (j in 0 until row.childCount) {
                    val col = row.getChildAt(j)
                    if (col is LinearLayout && col.childCount >= 2) {
                        (col.getChildAt(0) as? TextView)?.setTextColor(p.muted)
                        (col.getChildAt(1) as? TextView)?.setTextColor(p.text)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------- رندر

    fun render() {
        renderPower()
        renderServerCard()
    }

    private fun renderPower() {
        val st = act.connectionState
        power.mode = when (st) {
            "connected" -> "on"
            "connecting" -> "busy"
            else -> "off"
        }
        stateText.text = when (st) {
            "connected" -> "متصل است"
            "connecting" -> "در حال اتصال…"
            else -> "قطع است"
        }
        stateText.setTextColor(
            when (st) {
                "connected" -> p.good
                "connecting" -> p.mid
                else -> p.muted
            }
        )
        when (st) {
            "connected" -> when (act.netState) {
                1 -> if (act.netMs >= 0) "اینترنت از مسیر سرور تأیید شد · ${act.netMs}ms"
                else "اینترنت از مسیر سرور تأیید شد"
                2 -> act.netMsg.ifEmpty { "تونل فعال است ولی اینترنت رد نشد" }
                else -> "در حال تست اینترنت…"
            }
            "connecting" -> "هسته sing-box در حال راه‌اندازی…"
            else -> act.lastError.takeIf { it.isNotEmpty() } ?: "برای اتصال، دکمه را بزنید"
        }.let { netText.text = it }
        if (st != "connected") netText.setTextColor(if (act.netState == 2 && st != "connected") p.bad else p.muted)
        else netText.setTextColor(if (act.netState == 2) p.mid else p.good)
    }

    fun renderServerCard() {
        val s = act.store.selected()
        if (s == null) {
            serverName.text = "سروری انتخاب نشده"
            serverName.setTextColor(p.muted)
            serverBadge.text = "—"
            serverBadge.background = UI.card(act, p, 20, fillColor = p.card2)
            serverBadge.setTextColor(p.muted)
            serverPing.text = "—"
            serverPing.background = UI.card(act, p, 20, fillColor = p.card2)
            serverPing.setTextColor(p.muted)
            serverSub.text = "برای انتخاب سرور ضربه بزنید"
            return
        }
        serverName.text = s.name
        serverName.setTextColor(p.text)
        serverBadge.text = UI.protoLabel(s.protocol)
        serverBadge.background = UI.card(act, p, 20, fillColor = UI.alpha(p.accent, 0.16f))
        serverBadge.setTextColor(p.accent)
        serverPing.text = UI.pingText(s.ping)
        serverPing.background = UI.card(act, p, 20, fillColor = UI.alpha(UI.pingColor(p, s.ping), 0.16f))
        serverPing.setTextColor(UI.pingColor(p, s.ping))
        val inf = try { LinkParser.info(s.raw) } catch (_: Throwable) { null }
        val hp = inf?.host?.let { h -> inf.port?.let { po -> "$h:$po" } ?: h } ?: ""
        serverSub.text = hp.ifEmpty { "برای تغییر ضربه بزنید" }
    }

    /** به‌روزرسانی آمار هر ثانیه از سرویس */
    fun onStats(dn: Long, up: Long, tdn: Long, tup: Long) {
        dnSpeed.text = "${UI.fmtBytes(dn)}/s"
        upSpeed.text = "${UI.fmtBytes(up)}/s"
        dnTotal.text = UI.fmtBytes(tdn)
        upTotal.text = UI.fmtBytes(tup)
    }

    /** حالت‌های اولیه‌ی آمار بر اساس snapshot سرویس */
    fun restoreStats() {
        onStats(VexVpnService.snapDn, VexVpnService.snapUp, VexVpnService.snapTDn, VexVpnService.snapTUp)
    }
}
