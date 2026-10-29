package com.vex.client

import android.content.Context
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * صفحه‌ی اصلی VEX — دقیقاً مطابق طرح مرجع:
 *  کارت وضعیت (دکمه‌ی کره‌ی زمین + «قطع است» + نقش شیر و خورشید)،
 *  ناحیه‌ی پاور با دو شیر بالدار روبه‌روی هم و دکمه‌ی تاج‌دار،
 *  دو کارت آمار (دانلود / آپلود) با قاب دوبل طلایی،
 *  کارت سرور انتخاب‌شده (نام + IP + نشان قرمز پروتکل با تاج + پینگ)،
 *  دکمه‌ی شناور «+».
 */
class HomeUi(private val act: MainActivity) {

    lateinit var root: FrameLayout
        private set

    private lateinit var power: PowerButtonView
    private lateinit var statusCard: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var netText: TextView
    private lateinit var globeBtn: FrameLayout
    private lateinit var globeIc: IconView
    private lateinit var lionSunIv: ImageView
    private lateinit var lionPanel: FrameLayout

    private lateinit var dnSpeed: TextView
    private lateinit var upSpeed: TextView
    private lateinit var dnTotal: TextView
    private lateinit var upTotal: TextView
    private lateinit var dnLabel: TextView
    private lateinit var upLabel: TextView
    private lateinit var dnIc: IconView
    private lateinit var upIc: IconView
    private lateinit var dnIcBox: FrameLayout
    private lateinit var upIcBox: FrameLayout
    private lateinit var dnOuter: LinearLayout
    private lateinit var upOuter: LinearLayout
    private lateinit var dnInner: LinearLayout
    private lateinit var upInner: LinearLayout

    private lateinit var serverCard: LinearLayout
    private lateinit var serverLabel: TextView
    private lateinit var serverName: TextView
    private lateinit var serverIp: TextView
    private lateinit var serverBadge: LinearLayout
    private lateinit var serverBadgeText: TextView
    private lateinit var serverBadgeCrown: IconView
    private lateinit var serverPing: TextView
    private lateinit var serverPingIc: IconView

    private lateinit var fab: FrameLayout
    private lateinit var fabIc: IconView

    private var p: Palette = Themes.royal()

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = FrameLayout(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = UI.col(ctx)
        col.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 96))

        col.addView(buildStatusCard())
        col.addView(buildPowerZone())
        col.addView(buildStatsRow())
        col.addView(buildServerCard())

        scroll.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ---- دکمه‌ی شناور + (افزودن کانفیگ)
        fab = FrameLayout(ctx).apply { setPadding(UI.dp(ctx, 13), UI.dp(ctx, 13), UI.dp(ctx, 13), UI.dp(ctx, 13)) }
        fabIc = IconView(ctx, "plus")
        fab.addView(fabIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 28), UI.dp(ctx, 28), Gravity.CENTER))
        fab.setOnClickListener { act.showAddMenu() }
        root.addView(fab, FrameLayout.LayoutParams(
            UI.dp(ctx, 56), UI.dp(ctx, 56),
            Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = UI.dp(ctx, 20); bottomMargin = UI.dp(ctx, 16)
        })

        applyTheme(p)
        return root
    }

    // ---------------------------------------------------------- کارت وضعیت

    private fun buildStatusCard(): View {
        val ctx = act
        statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(ctx, 10), UI.dp(ctx, 10), UI.dp(ctx, 10), UI.dp(ctx, 10))
            minimumHeight = UI.dp(ctx, 92)
        }

        // دکمه‌ی کره‌ی زمین (انتخاب سرور)
        globeBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 12), UI.dp(ctx, 12), UI.dp(ctx, 12), UI.dp(ctx, 12))
        }
        globeIc = IconView(ctx, "globe")
        globeBtn.addView(globeIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 27), UI.dp(ctx, 27), Gravity.CENTER))
        globeBtn.setOnClickListener { act.gotoServers() }
        statusCard.addView(globeBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 52), UI.dp(ctx, 52)))

        // متن وضعیت
        val textCol = UI.col(ctx).apply { gravity = Gravity.CENTER }
        stateText = TextView(ctx).apply {
            textSize = 19f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
        }
        netText = TextView(ctx).apply {
            textSize = 12f
            typeface = Fonts.regular(ctx)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(UI.dp(ctx, 6), 0, UI.dp(ctx, 6), 0)
        }
        textCol.addView(stateText)
        textCol.addView(netText)
        textCol.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(UI.dp(ctx, 6), 0, UI.dp(ctx, 6), 0)
        }
        statusCard.addView(textCol)

        // نقش برجسته‌ی شیر و خورشید داخل قاب حکاکی‌شده (مطابق طرح مرجع)
        lionPanel = FrameLayout(ctx)
        lionSunIv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(UI.dp(ctx, 5), UI.dp(ctx, 4), UI.dp(ctx, 5), UI.dp(ctx, 4))
        }
        lionPanel.addView(lionSunIv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        statusCard.addView(lionPanel, LinearLayout.LayoutParams(
            UI.dp(ctx, 124), LinearLayout.LayoutParams.MATCH_PARENT))

        return wrapCard(statusCard, topMarginDp = 0)
    }

    // ---------------------------------------------------------- ناحیه‌ی پاور

    private fun buildPowerZone(): View {
        val ctx = act
        val zone = FrameLayout(ctx)

        // شیر بالدار چپ (رو به راست = سمت مرکز) — برنزی تیره مطابق طرح مرجع
        val bronze = LightingColorFilter(0xFFC7A75E.toInt(), 0xFF140C02.toInt())
        val lionL = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        lionL.setImageResource(R.drawable.art_lion)
        lionL.colorFilter = bronze
        lionL.alpha = 0.9f
        zone.addView(lionL, FrameLayout.LayoutParams(
            UI.dp(ctx, 160), UI.dp(ctx, 168),
            Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = UI.dp(ctx, 2) })

        // شیر بالدار راست (آینه‌ی چپ)
        val lionR = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            scaleX = -1f
        }
        lionR.setImageResource(R.drawable.art_lion)
        lionR.colorFilter = bronze
        lionR.alpha = 0.9f
        zone.addView(lionR, FrameLayout.LayoutParams(
            UI.dp(ctx, 160), UI.dp(ctx, 168),
            Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = UI.dp(ctx, 2) })

        // دکمه‌ی پاور تاج‌دار
        power = PowerButtonView(ctx)
        power.setOnClickListener { act.togglePower() }
        zone.addView(power, FrameLayout.LayoutParams(
            UI.dp(ctx, 252), UI.dp(ctx, 252), Gravity.CENTER))

        zone.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 270)).apply {
            setMargins(0, UI.dp(ctx, 4), 0, UI.dp(ctx, 6))
        }
        return zone
    }

    // ---------------------------------------------------------- کارت‌های آمار

    private fun buildStatsRow(): View {
        val ctx = act

        fun statCard(label: String, icon: String): LinearLayout {
            val outer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UI.dp(ctx, 7), UI.dp(ctx, 7), UI.dp(ctx, 7), UI.dp(ctx, 7))
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UI.dp(ctx, 11), UI.dp(ctx, 10), UI.dp(ctx, 11), UI.dp(ctx, 11))
            }
            // ردیف اول: لیبل راست + آیکون دایره‌ای طلایی چپ (دو سر کارت — مطابق طرح)
            val r1 = UI.row(ctx)
            val lbl = UI.label(ctx, label, 14.5f, p.text, bold = true)
            lbl.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val icBox = FrameLayout(ctx)
            val ic = IconView(ctx, icon).apply { iconColor = p.accent; bold = true }
            icBox.addView(ic, FrameLayout.LayoutParams(
                UI.dp(ctx, 15), UI.dp(ctx, 15), Gravity.CENTER))
            r1.addView(lbl)
            r1.addView(icBox, LinearLayout.LayoutParams(
                UI.dp(ctx, 30), UI.dp(ctx, 30)))
            inner.addView(r1)

            val speed = TextView(ctx).apply {
                textSize = 21f
                typeface = Fonts.bold(ctx)
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                text = "0 B/s"
            }
            speed.setPadding(0, UI.dp(ctx, 7), 0, 0)
            inner.addView(speed)

            val total = TextView(ctx).apply {
                textSize = 12f
                typeface = Fonts.regular(ctx)
                // RTL طبیعی مثل طرح مرجع (کل: 0 B) ولی چسبیده به لبه‌ی چپ کارت
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
                gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                text = "کل: 0 B"
            }
            total.setPadding(0, UI.dp(ctx, 2), 0, 0)
            inner.addView(total)

            outer.addView(inner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            if (icon == "down") {
                dnLabel = lbl; dnIc = ic; dnIcBox = icBox; dnSpeed = speed; dnTotal = total; dnOuter = outer; dnInner = inner
            } else {
                upLabel = lbl; upIc = ic; upIcBox = icBox; upSpeed = speed; upTotal = total; upOuter = outer; upInner = inner
            }
            return outer
        }

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val dn = statCard("دانلود", "down")
        val up = statCard("آپلود", "up")
        row.addView(dn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(up, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(ctx, 8)
        })
        return wrapCard(row, topMarginDp = 2)
    }

    // ---------------------------------------------------------- کارت سرور

    private fun buildServerCard(): View {
        val ctx = act
        serverCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, 14), UI.dp(ctx, 11), UI.dp(ctx, 14), UI.dp(ctx, 12))
            setOnClickListener { act.gotoServers() }
        }

        serverLabel = UI.label(ctx, "سرور انتخابشده", 12f, p.muted)

        val row = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 7), 0, 0) }

        // نام + IP
        val nameCol = UI.col(ctx)
        serverName = UI.label(ctx, "سروری انتخاب نشده", 16f, p.text, bold = true).apply {
            maxLines = 1
        }
        serverIp = UI.label(ctx, "—", 12f, p.muted).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.RIGHT
            setPadding(0, UI.dp(ctx, 2), 0, 0)
        }
        nameCol.addView(serverName)
        nameCol.addView(serverIp)
        nameCol.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(nameCol)

        // نشان قرمز پروتکل با تاج
        serverBadge = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(ctx, 9), UI.dp(ctx, 4), UI.dp(ctx, 9), UI.dp(ctx, 4))
        }
        serverBadgeText = UI.label(ctx, "—", 11f, Color.WHITE, bold = true)
        serverBadgeCrown = IconView(ctx, "crown").apply { iconColor = Color.WHITE; bold = true }
        serverBadge.addView(serverBadgeText)
        serverBadge.addView(serverBadgeCrown, LinearLayout.LayoutParams(
            UI.dp(ctx, 13), UI.dp(ctx, 13)).apply { marginStart = UI.dp(ctx, 5) })
        row.addView(serverBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 8)
        })

        // پینگ با آیکون آنتن
        val pingBox = UI.row(ctx)
        serverPing = UI.label(ctx, "—", 13.5f, p.good, bold = true).apply {
            textDirection = View.TEXT_DIRECTION_LTR
        }
        serverPingIc = IconView(ctx, "signal").apply { iconColor = p.good }
        pingBox.addView(serverPing)
        pingBox.addView(serverPingIc, LinearLayout.LayoutParams(
            UI.dp(ctx, 19), UI.dp(ctx, 19)).apply { marginStart = UI.dp(ctx, 5) })
        row.addView(pingBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 10)
        })

        serverCard.addView(serverLabel)
        serverCard.addView(row)
        return wrapCard(serverCard, topMarginDp = 10)
    }

    /** قاب دوبل طلایی دور کارت‌ها (مطابق حکاکی طرح مرجع) */
    private fun wrapCard(inner: View, topMarginDp: Int): View {
        val ctx = act
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8))
        }
        wrap.addView(inner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        wrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, topMarginDp), 0, 0)
        }
        return wrap
    }

    // ---------------------------------------------------------- تم

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act

        // کارت وضعیت — قاب برجسته‌ی طلایی مثل طرح
        statusCard.background = UI.card(ctx, p, 22, stroke = p.line, fillColor = p.card, strokeW = 1.5f)
        globeBtn.background = UI.card(ctx, p, 15, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        globeIc.iconColor = p.accent
        stateText.setTextColor(stateColor())
        netText.setTextColor(if (act.connectionState == "connected" && act.netState == 2) p.mid else p.muted)
        // نقش شیر و خورشید داخل قاب تیره‌ی حکاکی‌شده
        lionPanel.background = UI.card(ctx, p, 14, stroke = UI.alpha(p.line, 0.75f), fillColor = 0xFF0C0903.toInt())
        lionSunIv.setImageResource(R.drawable.art_lionsun)

        // دکمه پاور
        power.palette = p

        // کارت‌های آمار — قاب دوبل
        dnOuter.background = UI.card(ctx, p, 19, stroke = p.line, fillColor = p.card, strokeW = 1.5f)
        upOuter.background = UI.card(ctx, p, 19, stroke = p.line, fillColor = p.card, strokeW = 1.5f)
        dnInner.background = UI.card(ctx, p, 14, stroke = UI.alpha(p.line, 0.5f), fillColor = Color.TRANSPARENT)
        upInner.background = UI.card(ctx, p, 14, stroke = UI.alpha(p.line, 0.5f), fillColor = Color.TRANSPARENT)
        dnLabel.setTextColor(p.text); upLabel.setTextColor(p.text)
        dnIc.iconColor = p.accent; upIc.iconColor = p.accent
        // دایره‌ی طلایی دور آیکون‌های دانلود/آپلود (مطابق طرح مرجع)
        fun iconCircle(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(UI.alpha(p.accent, 0.16f))
            setStroke(UI.dp(ctx, 1), UI.alpha(p.accent, 0.55f))
        }
        dnIcBox.background = iconCircle()
        upIcBox.background = iconCircle()
        dnSpeed.setTextColor(p.text); upSpeed.setTextColor(p.text)
        dnTotal.setTextColor(p.muted); upTotal.setTextColor(p.muted)

        // کارت سرور
        serverCard.background = UI.card(ctx, p, 20, stroke = p.line, fillColor = p.card, strokeW = 1.5f)
        serverLabel.setTextColor(p.muted)
        serverIp.setTextColor(p.muted)
        renderServerCard()

        // دکمه‌ی شناور
        fab.background = UI.fabBg(ctx, p)
        fabIc.iconColor = p.accent
    }

    private fun stateColor(): Int = when (act.connectionState) {
        "connected" -> p.good
        "connecting" -> p.mid
        else -> 0xFFE9C464.toInt() // طلایی گرم «قطع است» مطابق طرح مرجع
    }

    // ---------------------------------------------------------- رندر

    fun render() {
        renderStatus()
        renderServerCard()
    }

    private fun renderStatus() {
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
        stateText.setTextColor(stateColor())
        netText.text = when (st) {
            "connected" -> when (act.netState) {
                1 -> if (act.netMs >= 0) "اینترنت تأیید شد · ${act.netMs}ms" else "اینترنت تأیید شد"
                2 -> (act.netMsg.ifEmpty { "تونل فعال است ولی اینترنت رد نشد" })
                else -> "در حال تست اینترنت…"
            }
            "connecting" -> "در حال اتصال به سرور…"
            else -> act.lastError.takeIf { it.isNotEmpty() }?.lineSequence()?.firstOrNull()
                    ?: "برای اتصال دکمهی پاور را بزن"
        }
        netText.maxLines = if (st == "connected" && act.netState == 2) 3 else 2
        netText.setTextColor(if (st == "connected" && act.netState == 2) p.mid else p.muted)
    }

    fun renderServerCard() {
        val s = act.store.selected()
        if (s == null) {
            serverName.text = "سروری انتخاب نشده"
            serverName.setTextColor(p.muted)
            serverIp.text = "برای انتخاب، کارت را لمس کن"
            serverBadgeText.text = "—"
            serverBadge.background = UI.card(act, p, 10, fillColor = p.card2)
            serverBadgeText.setTextColor(p.muted)
            serverBadgeCrown.iconColor = p.muted
            serverPing.text = "—"
            serverPing.setTextColor(p.muted)
            serverPingIc.iconColor = p.muted
            return
        }
        serverName.text = s.name
        serverName.setTextColor(p.text)
        val inf = try { LinkParser.info(s.raw) } catch (_: Throwable) { null }
        serverIp.text = inf?.host ?: "—"
        serverBadgeText.text = UI.protoLabel(s.protocol)
        serverBadge.background = UI.card(act, p, 10, fillColor = 0xFFC43A32.toInt())
        serverBadgeText.setTextColor(Color.WHITE)
        serverBadgeCrown.iconColor = Color.WHITE
        serverPing.text = UI.pingText(s.ping)
        serverPing.setTextColor(UI.pingColor(p, s.ping))
        serverPingIc.iconColor = UI.pingColor(p, s.ping)
    }

    /** به‌روزرسانی آمار هر ثانیه از سرویس */
    fun onStats(dn: Long, up: Long, tdn: Long, tup: Long) {
        dnSpeed.text = "${UI.fmtBytes(dn)}/s"
        upSpeed.text = "${UI.fmtBytes(up)}/s"
        dnTotal.text = "کل: ${UI.fmtBytes(tdn)}"
        upTotal.text = "کل: ${UI.fmtBytes(tup)}"
    }

    /** حالت‌های اولیه‌ی آمار بر اساس snapshot سرویس */
    fun restoreStats() {
        onStats(VexVpnService.snapDn, VexVpnService.snapUp, VexVpnService.snapTDn, VexVpnService.snapTUp)
    }
}
