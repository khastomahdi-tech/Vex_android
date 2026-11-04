package com.vex.client

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * صفحه‌ی اصلی VEX — چیدمان تمیز:
 *  ناحیه‌ی پاور فقط خودِ دکمه‌ی پاور است — بدون شیر، تاج یا هر علامت اضافه؛
 *  بالای پاور هیچ چیز نیست — هویت برنامه فقط در هدر بالای صفحه است؛
 *  زیر پاور: متن وضعیت («اتصال با موفقیت انجام شد») + تایمر زنده‌ی مدت اتصال؛
 *  دو کارت آمار (دانلود / آپلود) با قاب دوبل طلایی؛
 *  کارت آی‌پی خروجی (IP + کشور + پرچم)؛
 *  کارت سرور انتخاب‌شده (نام + IP + نشان قرمز پروتکل + پینگ).
 */
class HomeUi(private val act: MainActivity) {

    lateinit var root: FrameLayout
        private set

    private lateinit var power: PowerButtonView
    private lateinit var statusText: TextView
    private lateinit var statusSpin: ProgressBar
    private lateinit var timerText: TextView

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
    private lateinit var serverWrap: LinearLayout
    private lateinit var serverLabel: TextView
    private lateinit var serverName: TextView
    private lateinit var serverIp: TextView
    private lateinit var serverBadge: LinearLayout
    private lateinit var serverBadgeText: TextView
    private lateinit var serverPing: TextView
    private lateinit var serverPingIc: IconView

    // ---- کارت «آی‌پی خروجی» ----
    private lateinit var exitWrap: LinearLayout
    private lateinit var exitCard: LinearLayout
    private lateinit var exitLabel: TextView
    private lateinit var exitIc: IconView
    private lateinit var exitIcBox: FrameLayout
    private lateinit var exitFlag: TextView
    private lateinit var exitGlobeIc: IconView
    private lateinit var exitFlagBox: FrameLayout
    private lateinit var exitCountry: TextView
    private lateinit var exitIp: TextView
    // ---- گزینش موتور اتصال (اتصال خودکار / Vex / لنترن / ترکیبی) — انتهای صفحه ----
    private lateinit var engAuto: LinearLayout
    private lateinit var engVex: LinearLayout
    private lateinit var engLnt: LinearLayout
    private lateinit var engHyb: LinearLayout
    private lateinit var engWarp: LinearLayout
    private lateinit var engSmart: LinearLayout
    private lateinit var engAutoIc: IconView
    private lateinit var engVexIc: IconView
    private lateinit var engLntIc: IconView
    private lateinit var engHybIc: IconView
    private lateinit var engWarpIc: IconView
    private lateinit var engSmartIc: IconView
    private lateinit var engAutoTx: TextView
    private lateinit var engVexTx: TextView
    private lateinit var engLntTx: TextView
    private lateinit var engHybTx: TextView
    private lateinit var engWarpTx: TextView
    private lateinit var engSmartTx: TextView
    private var dlg: android.app.AlertDialog? = null
    private var exitInfo: ExitInfo? = null

    // ---- بنر «نسخه‌ی جدید منتشر شده» + بروزرسانی خودکار درون‌برنامه‌ای ----
    private lateinit var updateWrap: LinearLayout
    private lateinit var updateCard: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateBtn: TextView
    private lateinit var progRow: LinearLayout
    private lateinit var progBar: ProgressBar
    private lateinit var progPct: TextView
    private var updateShownVer = 0
    private var updateLatest: Updater.Latest? = null

    private var p: Palette = Themes.royal()

    /** تیک هر ثانیه برای به‌روزرسانی تایمر مدت اتصال */
    private val ticker = object : Runnable {
        override fun run() {
            updateTimer()
            root.postDelayed(this, 1000)
        }
    }

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = FrameLayout(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = UI.col(ctx)
        col.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 2), UI.dp(ctx, 14), UI.dp(ctx, 24))

        col.addView(buildUpdateBanner(), 0)
        col.addView(buildPowerZone())
        col.addView(buildConnectionStatus())
        col.addView(buildStatsRow())
        col.addView(buildExitIpCard())
        col.addView(buildServerCard())
        // گزینش موتور — پایین صفحه (زیر کارت سرور) تا انتخاب از پایین‌تر انجام شود
        col.addView(buildEngineBar())

        scroll.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.post(ticker)

        applyTheme(p)
        return root
    }

    // ---------------------------------------------------------- بنر نسخه‌ی جدید

    /** بنر «نسخه‌ی جدید» — بالای صفحه‌ی اصلی، با دکمه‌ی بروزرسانی درون‌برنامه‌ای و بستن */
    private fun buildUpdateBanner(): View {
        val ctx = act
        updateCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(ctx, 13), UI.dp(ctx, 10), UI.dp(ctx, 13), UI.dp(ctx, 10))
        }
        val txtCol = UI.col(ctx)
        // آیکون زنگ SVG — جایگزین ایموجی
        val bellBox = FrameLayout(ctx)
        val bell = IconView(ctx, "bell").apply { iconColor = p.accent; bold = true }
        bellBox.addView(bell, FrameLayout.LayoutParams(
            UI.dp(ctx, 19), UI.dp(ctx, 19), Gravity.CENTER))
        updateCard.addView(bellBox, LinearLayout.LayoutParams(
            UI.dp(ctx, 34), UI.dp(ctx, 34)).apply { marginEnd = UI.dp(ctx, 10) })
        updateText = UI.label(ctx, "بروزرسانی جدید برای کلاینت اومده", 13.5f, p.text, bold = true).apply {
            maxLines = 2
        }
        txtCol.addView(updateText)
        updateCard.addView(txtCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        updateBtn = UI.softButton(ctx, "بروزرسانی", p)
        updateBtn.setOnClickListener { act.startInAppUpdate() }
        updateCard.addView(updateBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 92), UI.dp(ctx, 40)).apply { marginStart = UI.dp(ctx, 8) })

        val xBox = FrameLayout(ctx)
        val xIc = IconView(ctx, "x").apply { iconColor = p.muted; bold = true }
        xIc.setOnClickListener { hideUpdateBanner() }
        xBox.addView(xIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 14), UI.dp(ctx, 14), Gravity.CENTER))
        xBox.setPadding(UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6))
        xBox.setOnClickListener { hideUpdateBanner() }
        updateCard.addView(xBox, LinearLayout.LayoutParams(
            UI.dp(ctx, 32), UI.dp(ctx, 32)))

        // ردیف درصد پیشرفت دانلود — هنگام بروزرسانی خودکار دیده می‌شود
        progBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        progPct = UI.label(ctx, "در حال بروزرسانی… ٪۰", 12.5f, p.text, bold = true)
        progRow = UI.row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        progRow.addView(progBar, LinearLayout.LayoutParams(0, UI.dp(ctx, 18), 1f))
        progRow.addView(progPct, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 10)
        })
        progRow.setPadding(UI.dp(ctx, 13), UI.dp(ctx, 2), UI.dp(ctx, 13), UI.dp(ctx, 10))
        progRow.visibility = View.GONE

        updateWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, UI.dp(ctx, 6), 0, 0)
            }
        }
        updateWrap.addView(updateCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateWrap.addView(progRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return updateWrap
    }

    /** نمایش بنر — اگر کاربر قبلاً همین نسخه را بسته باشد، دیگر نمی‌آید */
    fun showUpdateBanner(ver: Int) {
        try {
            val dismissed = act.getSharedPreferences("vex_gate", Context.MODE_PRIVATE)
                .getInt("upd_dismissed", 0)
            if (dismissed >= ver) return
            updateShownVer = ver
            updateText.text = "بروزرسانی جدید برای کلاینت اومده — می‌تونی از طریق دکمه‌ی زیر بروزرسانی کنی"
            updateWrap.visibility = View.VISIBLE
        } catch (_: Throwable) {}
    }

    /** نمایش بنر با اطلاعات واقعی آخرین نسخه (پاسخ /v1/app/latest) — بروزرسانی درون‌برنامه‌ای */
    fun showUpdateBanner(latest: Updater.Latest) {
        try {
            val dismissed = act.getSharedPreferences("vex_gate", Context.MODE_PRIVATE)
                .getInt("upd_dismissed", 0)
            if (dismissed >= latest.code.toInt()) return
            updateShownVer = latest.code.toInt()
            updateLatest = latest
            updateText.text = "بروزرسانی جدید برای کلاینت اومده — می‌تونی از طریق دکمه‌ی زیر بروزرسانی کنی"
            progRow.visibility = View.GONE
            updateBtn.visibility = View.VISIBLE
            updateWrap.visibility = View.VISIBLE
        } catch (_: Throwable) {}
    }

    /** شروع دانلود بروزرسانی — دکمه به نوار درصد تبدیل می‌شود */
    fun updateProgressStart() {
        try {
            updateBtn.visibility = View.GONE
            progBar.progress = 0
            progPct.text = "در حال بروزرسانی… ٪۰"
            progPct.setTextColor(p.text)
            progRow.visibility = View.VISIBLE
        } catch (_: Throwable) {}
    }

    /** تازه‌کردن درصد پیشرفت */
    fun updateProgress(pct: Int) {
        try {
            progBar.progress = pct
            progPct.text = "در حال بروزرسانی… ٪$pct"
        } catch (_: Throwable) {}
    }

    /** دانلود کامل شد — نصب آغاز می‌شود */
    fun updateProgressDone() {
        try { progPct.text = "نصب بروزرسانی…" } catch (_: Throwable) {}
    }

    /** دانلود ناموفق — دکمه برمی‌گردد تا کاربر دوباره امتحان کند */
    fun updateProgressFail() {
        try {
            progRow.visibility = View.GONE
            updateBtn.visibility = View.VISIBLE
        } catch (_: Throwable) {}
    }

    /** بستن بنر — تا نسخه‌ی بعدی دیگر نمی‌آید */
    fun hideUpdateBanner() {
        updateWrap.visibility = View.GONE
        try {
            if (updateShownVer > 0) act.getSharedPreferences("vex_gate", Context.MODE_PRIVATE)
                .edit().putInt("upd_dismissed", updateShownVer).apply()
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------- ناحیه‌ی پاور

    private fun buildPowerZone(): View {
        val ctx = act
        val zone = FrameLayout(ctx)

        // فقط خود دکمه‌ی پاور — شیرها حذف شدند؛ وسط‌چین با فاصله‌ی نفس بالا/پایین
        power = PowerButtonView(ctx)
        power.setOnClickListener { act.togglePower() }
        zone.addView(power, FrameLayout.LayoutParams(
            UI.dp(ctx, 248), UI.dp(ctx, 248), Gravity.CENTER))

        zone.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 262)).apply {
            setMargins(0, UI.dp(ctx, 4), 0, UI.dp(ctx, 0))
        }
        return zone
    }

    // ---------------------------------------------------------- موتور اتصال (خودکار / Vex / لنترن / ترکیبی)

    /**
     * سکشن «موتور اتصال» — انتهای صفحه‌ی اصلی:
     *  • عنوان «موتور اتصال» + توضیح «موتور اتصال شما میتونید دستی انتخاب کنید»
     *  • چهار قرص انتخاب (بدون ایموجی — آیکون‌ها SVG وکتوری‌اند)
     *  • دکمه‌ی «انتخاب موتور اتصال» → دیالوگ انتخاب
     * کارت طلایی RADIANCE قبلی حذف شد (طبق درخواست کاربر).
     */
    private fun buildEngineBar(): View {
        val ctx = act
        val sec = UI.col(ctx)

        // ---- سرتیتر سکشن
        val headRow = UI.row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val headIc = IconView(ctx, "gear").apply { iconColor = p.accent; bold = true }
        headRow.addView(headIc, LinearLayout.LayoutParams(
            UI.dp(ctx, 16), UI.dp(ctx, 16)).apply { marginEnd = UI.dp(ctx, 7) })
        headRow.addView(UI.label(ctx, "موتور اتصال", 15f, p.text, bold = true))
        headRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 6), 0, UI.dp(ctx, 2))
        }
        sec.addView(headRow)

        val sub = UI.label(ctx, "موتور اتصال شما میتونید دستی انتخاب کنید", 11.5f, p.muted)
        sub.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, UI.dp(ctx, 4))
        }
        sec.addView(sub)

        // ---- قرص‌های انتخاب
        val row = UI.row(ctx).apply { gravity = Gravity.CENTER }

        fun pill(icon: String, title: String): Triple<LinearLayout, IconView, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(UI.dp(ctx, 12), UI.dp(ctx, 8), UI.dp(ctx, 12), UI.dp(ctx, 8))
            }
            val iv = IconView(ctx, icon).apply { bold = true }
            box.addView(iv, LinearLayout.LayoutParams(
                UI.dp(ctx, 14), UI.dp(ctx, 14)).apply { marginEnd = UI.dp(ctx, 6) })
            val tv = UI.label(ctx, title, 12.5f, p.muted, bold = true)
            box.addView(tv)
            return Triple(box, iv, tv)
        }

        val a = pill("bolt", "اتصال خودکار")
        engAuto = a.first; engAutoIc = a.second; engAutoTx = a.third
        val sm = pill("target", "هوشمند")
        engSmart = sm.first; engSmartIc = sm.second; engSmartTx = sm.third
        val v = pill("shield", "Vex")
        engVex = v.first; engVexIc = v.second; engVexTx = v.third
        val ln = pill("sun", "لنترن")
        engLnt = ln.first; engLntIc = ln.second; engLntTx = ln.third
        val wp = pill("globe", "وارپ")
        engWarp = wp.first; engWarpIc = wp.second; engWarpTx = wp.third
        val hy = pill("link", "ترکیبی")
        engHyb = hy.first; engHybIc = hy.second; engHybTx = hy.third
        engAuto.setOnClickListener { switchEngine("auto") }
        engSmart.setOnClickListener { switchEngine("smart") }
        engVex.setOnClickListener { switchEngine("vex") }
        engLnt.setOnClickListener { switchEngine("lantern") }
        engWarp.setOnClickListener { switchEngine("warp") }
        engHyb.setOnClickListener { switchEngine("hybrid") }

        val gap = TextView(ctx).apply { setPadding(UI.dp(ctx, 4), 0, UI.dp(ctx, 4), 0) }
        val gap2 = TextView(ctx).apply { setPadding(UI.dp(ctx, 4), 0, UI.dp(ctx, 4), 0) }
        val gap3 = TextView(ctx).apply { setPadding(UI.dp(ctx, 4), 0, UI.dp(ctx, 4), 0) }
        val gap4 = TextView(ctx).apply { setPadding(UI.dp(ctx, 4), 0, UI.dp(ctx, 4), 0) }
        val gap5 = TextView(ctx).apply { setPadding(UI.dp(ctx, 4), 0, UI.dp(ctx, 4), 0) }
        row.addView(engSmart)
        row.addView(gap4)
        row.addView(engAuto)
        row.addView(gap)
        row.addView(engVex)
        row.addView(gap2)
        row.addView(engLnt)
        row.addView(gap3)
        row.addView(engWarp)
        row.addView(gap5)
        row.addView(engHyb)
        // نکته: Kotlin به nested class ارث‌بری‌شده از سوپرکلاس جاوا اجازه‌ی دسترسی نمی‌دهد
        // (HorizontalScrollView.LayoutParams resolve نمی‌شود) → FrameLayout.LayoutParams
        row.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        // پنج حالت در یک ردیف جا نمی‌شود — ردیف قابل اسکرول افقی است
        val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        scroller.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 6), 0, UI.dp(ctx, 10))
        }
        sec.addView(scroller)

        // ---- دکمه‌ی «انتخاب موتور اتصال» — دیالوگ انتخاب را باز می‌کند
        val btn = TextView(ctx).apply {
            text = "انتخاب موتور اتصال"
            textSize = 13f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 11), UI.dp(ctx, 16), UI.dp(ctx, 11))
            background = GradientDrawable().apply {
                setColor(UI.alpha(p.accent, 0.10f))
                setStroke(UI.dp(ctx, 1), UI.alpha(p.accent, 0.45f))
                cornerRadius = UI.dp(ctx, 14).toFloat()
            }
            setTextColor(p.accent)
            setOnClickListener { showEnginePicker() }
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        sec.addView(btn)

        sec.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 8), 0, UI.dp(ctx, 8))
        }
        return sec
    }

    /** دیالوگ انتخاب موتور — چهار حالت با توضیح (بدون ایموجی — آیکون‌های SVG) */
    private fun showEnginePicker() {
        val ctx = act
        val col = UI.col(ctx)
        val cur = ctx.store.settings.engine

        fun opt(icon: String, title: String, sub: String, tag: String) {
            val rowC = UI.row(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(UI.dp(ctx, 14), UI.dp(ctx, 11), UI.dp(ctx, 14), UI.dp(ctx, 11))
                background = UI.card(ctx, p, 12,
                    fillColor = if (cur == tag) UI.alpha(p.accent, 0.14f) else p.card2,
                    stroke = if (cur == tag) p.accent else null)
            }
            val ic = IconView(ctx, icon).apply {
                bold = true
                iconColor = if (cur == tag) p.accent else p.muted
            }
            val txt = UI.col(ctx)
            val t1 = UI.label(ctx, title, 14.5f, if (cur == tag) p.accent else p.text, bold = true)
            val t2 = UI.label(ctx, sub, 11.5f, p.muted)
            txt.addView(t1); txt.addView(t2)
            rowC.addView(ic, LinearLayout.LayoutParams(
                UI.dp(ctx, 20), UI.dp(ctx, 20)))
            rowC.addView(TextView(ctx).apply { setPadding(UI.dp(ctx, 10), 0, UI.dp(ctx, 10), 0) })
            rowC.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
            rowC.setOnClickListener {
                dlg?.dismiss(); dlg = null
                switchEngine(tag)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = UI.dp(ctx, 8)
            rowC.layoutParams = lp
            col.addView(rowC)
        }

        opt("bolt", "اتصال خودکار", "استخر سرورها + انتخاب و سوییچ خودکار بهترین سرور", "auto")
        opt("target", "هوشمند (زنجیره)", "لنترن ← وارپ ← بهترین سرور Vex — خودش هر مسیر مرده را عوض می‌کند", "smart")
        opt("shield", "Vex", "اتصال با سرور انتخابی شما", "vex")
        opt("sun", "لنترن (Lantern)", "موتور رسمی لنترن — بدون انتخاب سرور، خودش وصل می‌شود", "lantern")
        opt("globe", "وارپ (Cloudflare)", "تونل رایگان کلودفلر — بدون انتخاب سرور، هسته‌ی وایرگارد", "warp")
        opt("link", "ترکیبی (Vex + لنترن)", "اتصال به سرور از داخل تونل لنترن — ضد فیلترینگ", "hybrid")

        val titleView = UI.label(ctx, "انتخاب موتور اتصال", 17f, p.text, bold = true)
        titleView.setPadding(UI.dp(ctx, 22), UI.dp(ctx, 18), UI.dp(ctx, 22), UI.dp(ctx, 4))
        val b = android.app.AlertDialog.Builder(ctx)
            .setCustomTitle(titleView)
            .setView(col)
            .setNegativeButton("انصراف") { d, _ -> d.dismiss() }
        val d = b.create()
        dlg = d
        d.show()
        try {
            d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.muted)
            d.window?.setBackgroundDrawable(UI.card(ctx, p, 20, stroke = p.line, fillColor = p.bg2))
        } catch (_: Throwable) {}
    }

    private fun switchEngine(which: String) {
        if (act.connectionState != "stopped") {
            UI.toast(act, "اول اتصال را قطع کن، بعد حالت را عوض کن")
            return
        }
        if (act.store.settings.engine == which) return
        act.store.settings.engine = which
        act.store.saveSettings()
        renderEngineBar()
        renderServerCard()
        UI.toast(
            act,
            when (which) {
                "auto" -> "اتصال خودکار فعال شد — بهترین سرور خودکار انتخاب و در صورت قطعی سوییچ می‌شود"
                "lantern" -> "موتور لنترن فعال شد — بدون نیاز به سرور، خودش قوی‌ترین مسیر را پیدا می‌کند"
                "warp" -> "موتور وارپ فعال شد — تونل رایگان کلودفلر، بدون انتخاب سرور"
                "smart" -> "موتور هوشمند فعال شد — لنترن ← وارپ ← بهترین سرور، همه بی‌صدا"
                "hybrid" -> "حالت ترکیبی فعال شد — اتصال به سرور از داخل تونل لنترن عبور می‌کند؛ ضد فیلترینگ و پایدارتر"
                else -> "موتور Vex فعال شد — با سرور انتخابی وصل می‌شود"
            }
        )
    }

    /** ظاهر چهار قرص بر اساس حالت فعال — انتخاب‌شده: قاب accent + متن/آیکون accent */
    fun renderEngineBar() {
        val eng = act.store.settings.engine
        val selBg = UI.alpha(p.accent, 0.16f)
        val selStroke = UI.alpha(p.accent, 0.55f)
        val idleBg = UI.card(act, p, 20, fillColor = p.card2)

        fun paint(box: LinearLayout, iv: IconView, tv: TextView, selected: Boolean) {
            box.background = if (selected) GradientDrawable().apply {
                setColor(selBg); setStroke(UI.dp(act, 1), selStroke); cornerRadius = UI.dp(act, 20).toFloat()
            } else idleBg
            iv.iconColor = if (selected) p.accent else p.muted
            tv.setTextColor(if (selected) p.accent else p.muted)
        }
        paint(engSmart, engSmartIc, engSmartTx, eng == "smart")
        paint(engAuto, engAutoIc, engAutoTx, eng == "auto")
        paint(engVex, engVexIc, engVexTx, eng == "vex")
        paint(engLnt, engLntIc, engLntTx, eng == "lantern")
        paint(engWarp, engWarpIc, engWarpTx, eng == "warp")
        paint(engHyb, engHybIc, engHybTx, eng == "hybrid")
    }

    // ---------------------------------------------------------- وضعیت اتصال + تایمر

    /** زیر پاور: متن وضعیت اتصال («اتصال با موفقیت انجام شد») + دایره‌ی چرخان جست‌وجو + تایمر زنده */
    private fun buildConnectionStatus(): View {
        val ctx = act
        val col = UI.col(ctx).apply { gravity = Gravity.CENTER }

        val row = UI.row(ctx).apply { gravity = Gravity.CENTER }
        statusSpin = ProgressBar(ctx).apply {
            visibility = View.GONE
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(p.accent)
        }
        row.addView(statusSpin, LinearLayout.LayoutParams(
            UI.dp(ctx, 16), UI.dp(ctx, 16)).apply { marginEnd = UI.dp(ctx, 8) })

        statusText = TextView(ctx).apply {
            text = "اتصال قطع است"
            textSize = 14.5f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
        }
        row.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        timerText = TextView(ctx).apply {
            text = "—"
            textSize = 22f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            letterSpacing = 0.08f
            setPadding(0, UI.dp(ctx, 6), 0, 0)
        }

        col.addView(row)
        col.addView(timerText)

        col.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 2), 0, UI.dp(ctx, 10))
        }
        return col
    }

    /** تایمر مدت اتصال — هر ثانیه به‌روز می‌شود */
    private fun updateTimer() {
        if (act.connectionState == "connected" && VexVpnService.connectedAtMs > 0) {
            val sec = ((System.currentTimeMillis() - VexVpnService.connectedAtMs) / 1000L).coerceAtLeast(0)
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            timerText.text = String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
            timerText.setTextColor(p.good)
        } else {
            timerText.text = "—"
            timerText.setTextColor(p.muted)
        }
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
        return wrapCard(row, topMarginDp = 10)
    }

    // ---------------------------------------------------------- کارت آی‌پی خروجی

    /**
     * کارت «آی‌پی خروجی»: پس از اتصال، آی‌پی عمومی خروجی سرور + نام کشور + پرچم.
     * درخواست از داخل تونل می‌رود؛ پس آی‌پی برگشتی همان خروجی واقعی سرور است.
     */
    private fun buildExitIpCard(): View {
        val ctx = act
        exitCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, 14), UI.dp(ctx, 11), UI.dp(ctx, 14), UI.dp(ctx, 12))
        }

        exitLabel = UI.label(ctx, "آی‌پی خروجی", 12f, p.muted)
        val head = UI.row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        exitLabel.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val icBox = FrameLayout(ctx)
        exitIc = IconView(ctx, "globe").apply { iconColor = p.accent; bold = true }
        icBox.addView(exitIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 15), UI.dp(ctx, 15), Gravity.CENTER))
        head.addView(exitLabel)
        head.addView(icBox, LinearLayout.LayoutParams(
            UI.dp(ctx, 30), UI.dp(ctx, 30)))
        exitIcBox = icBox
        exitCard.addView(head)

        // ردیف مقدار: پرچم + کشور (راست) — آی‌پی (چپ، LTR)
        val row = UI.row(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UI.dp(ctx, 9), 0, 0)
        }
        // جای پرچم: پرچم واقعی کشور بعد از اتصال — تا آن موقع آیکون کره‌ی زمین SVG
        exitFlagBox = FrameLayout(ctx)
        exitGlobeIc = IconView(ctx, "globe").apply { iconColor = p.muted; bold = true }
        exitFlagBox.addView(exitGlobeIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 24), UI.dp(ctx, 24), Gravity.CENTER))
        exitFlag = TextView(ctx).apply {
            text = ""
            textSize = 24f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        exitFlagBox.addView(exitFlag, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        row.addView(exitFlagBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 10)
        })

        exitCountry = UI.label(ctx, "برای نمایش وصل شو", 15f, p.muted, bold = true).apply {
            maxLines = 1
        }
        row.addView(exitCountry, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        exitIp = TextView(ctx).apply {
            text = "—"
            textSize = 14f
            typeface = Fonts.bold(ctx)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.04f
            maxLines = 1
        }
        row.addView(exitIp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 8)
        })
        exitCard.addView(row)

        exitWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6))
        }
        exitWrap.addView(exitCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        exitWrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 12), 0, 0)
        }
        return exitWrap
    }

    /** نتیجه‌ی موفق آی‌پی خروجی از MainActivity می‌آید */
    fun setExitIp(info: ExitInfo?) {
        exitInfo = info
        renderExitIp()
    }

    /** قطع اتصال → همه‌چیز خالی می‌شود */
    fun clearExitIp() {
        exitInfo = null
        renderExitIp()
    }

    private fun renderExitIp() {
        val info = exitInfo
        fun showFlag(v: Boolean) {
            exitFlag.visibility = if (v) View.VISIBLE else View.GONE
            exitGlobeIc.visibility = if (v) View.GONE else View.VISIBLE
        }
        when {
            act.connectionState == "connected" && info != null -> {
                exitFlag.text = NetUtil.flagEmoji(info.code)
                // کشور نامشخص → پرچم خالی می‌ماند و آیکون SVG کره‌ی زمین دیده می‌شود
                showFlag(exitFlag.text.isNotBlank())
                exitCountry.text = info.country.ifEmpty { "نامشخص" }
                exitCountry.setTextColor(p.text)
                exitIp.text = info.ip
                exitIp.setTextColor(p.good)
            }
            act.connectionState == "connected" -> {
                showFlag(false)
                exitGlobeIc.iconColor = p.mid
                exitCountry.text = "در حال دریافت آی‌پی خروجی…"
                exitCountry.setTextColor(p.muted)
                exitIp.text = "—"
                exitIp.setTextColor(p.muted)
            }
            act.connectionState == "connecting" -> {
                showFlag(false)
                exitGlobeIc.iconColor = p.mid
                exitCountry.text = "در حال اتصال…"
                exitCountry.setTextColor(p.mid)
                exitIp.text = "—"
                exitIp.setTextColor(p.muted)
            }
            else -> {
                showFlag(false)
                exitGlobeIc.iconColor = p.muted
                exitCountry.text = "برای نمایش آی‌پی خروجی وصل شو"
                exitCountry.setTextColor(p.muted)
                exitIp.text = "—"
                exitIp.setTextColor(p.muted)
            }
        }
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

        val row = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 9), 0, 0) }

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

        // نشان قرمز پروتکل
        serverBadge = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(ctx, 9), UI.dp(ctx, 4), UI.dp(ctx, 9), UI.dp(ctx, 4))
        }
        serverBadgeText = UI.label(ctx, "—", 11f, Color.WHITE, bold = true)
        serverBadge.addView(serverBadgeText)
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
        return wrapCard(serverCard, topMarginDp = 12, framed = true).also {
            serverWrap = it as LinearLayout
        }
    }

    /**
     * قاب دوبل طلایی دور کارت‌ها (مطابق حکاکی طرح مرجع):
     *  framed = true → یک قاب بیرونی با فاصله‌ی کم دور قاب داخلی (کارت سرور).
     *  ردیف آمار خودش دو قاب داخلی دارد → framed = false.
     */
    private fun wrapCard(inner: View, topMarginDp: Int, framed: Boolean = false): View {
        val ctx = act
        val pad = if (framed) 6 else 8
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, pad), UI.dp(ctx, pad), UI.dp(ctx, pad), UI.dp(ctx, pad))
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
        // قرص‌های موتور اتصال هم با تم هماهنگ می‌شوند (اگر ساخته شده باشند)
        if (::engVex.isInitialized && ::engLnt.isInitialized) renderEngineBar()

        // بنر نسخه‌ی جدید — کارت اکسنت ملایم
        updateCard.background = GradientDrawable().apply {
            cornerRadius = UI.dp(ctx, 18).toFloat()
            setColor(UI.alpha(p.accent, 0.12f))
            setStroke(UI.dp(ctx, 1), UI.alpha(p.accent, 0.5f))
        }
        updateText.setTextColor(p.text)
        if (::progBar.isInitialized) {
            progBar.progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
            progBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(UI.alpha(p.accent, 0.25f))
            progPct.setTextColor(p.text)
        }

        // دکمه پاور
        power.palette = p

        // متن وضعیت زیر پاور
        renderStatus()

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
        // عمق ملایم کارت‌های آمار
        dnOuter.elevation = UI.dp(ctx, 2f).toFloat()
        upOuter.elevation = UI.dp(ctx, 2f).toFloat()

        // کارت آی‌پی خروجی — قاب دوبل مثل کارت سرور
        val exitGlow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UI.dp(ctx, 20).toFloat()
            colors = intArrayOf(p.card2, p.card)
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.42f)
            gradientRadius = UI.dp(ctx, 240).toFloat()
            setStroke(UI.dp(ctx, 1), p.line)
        }
        exitCard.background = exitGlow
        exitWrap.background = UI.card(ctx, p, 26, stroke = UI.alpha(p.line, 0.7f), fillColor = Color.TRANSPARENT)
        exitWrap.elevation = UI.dp(ctx, 2f).toFloat()
        exitLabel.setTextColor(p.muted)
        exitIc.iconColor = p.accent
        exitIcBox.background = iconCircle()
        exitFlag.setTextColor(p.text)
        if (!act.autoSearching) exitGlobeIc.iconColor = if (act.connectionState == "connected") p.mid else p.muted
        renderExitIp()

        // کارت سرور — هاله‌ی شعاعی محو + قاب دوبل بیرونی
        val serverGlow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UI.dp(ctx, 20).toFloat()
            colors = intArrayOf(p.card2, p.card)
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.42f)
            gradientRadius = UI.dp(ctx, 240).toFloat()
            setStroke(UI.dp(ctx, 1), p.line)
        }
        serverCard.background = serverGlow
        serverWrap.background = UI.card(ctx, p, 26, stroke = UI.alpha(p.line, 0.7f), fillColor = Color.TRANSPARENT)
        serverWrap.elevation = UI.dp(ctx, 2f).toFloat()
        serverLabel.setTextColor(p.muted)
        serverIp.setTextColor(p.muted)
        renderServerCard()
    }

    // ---------------------------------------------------------- رندر

    fun render() {
        renderStatus()
        renderEngineBar()
        renderServerCard()
        renderExitIp()
    }

    /**
     * وضعیت اتصال: حلقه‌ی سبز دکمه‌ی پاور (mode) + متن زیر پاور.
     * متصل → «اتصال با موفقیت انجام شد» به رنگ سبز؛ در حال اتصال → نارنجی؛ قطع → خاکستری.
     */
    private fun renderStatus() {
        // «اتصال خودکار» در حال جست‌وجوی بهترین سرور — دایره‌ی چرخان + پیام جست‌وجو
        if (act.autoSearching) {
            power.mode = "busy"
            statusText.text = "در حال پیدا کردن سرور با بهترین پینگ…"
            statusText.setTextColor(p.mid)
            statusSpin.visibility = View.VISIBLE
            updateTimer()
            return
        }
        statusSpin.visibility = View.GONE
        power.mode = when (act.connectionState) {
            "connected" -> "on"
            "connecting" -> "busy"
            else -> "off"
        }
        when (act.connectionState) {
            "connected" -> {
                statusText.text = "اتصال با موفقیت انجام شد"
                statusText.setTextColor(p.good)
            }
            "connecting" -> {
                statusText.text = "در حال اتصال…"
                statusText.setTextColor(p.mid)
            }
            else -> {
                statusText.text = "اتصال قطع است"
                statusText.setTextColor(p.muted)
            }
        }
        updateTimer()
    }

    fun renderServerCard() {
        val s = act.store.selected()
        if (s == null) {
            when (act.store.settings.engine) {
                "lantern" -> {
                    serverName.text = "اتصال با لنترن"
                    serverName.setTextColor(p.accent)
                    serverIp.text = "بدون نیاز به سرور — پاور را بزن"
                }
                "warp" -> {
                    serverName.text = "اتصال با وارپ (کلودفلر)"
                    serverName.setTextColor(p.accent)
                    serverIp.text = "بدون نیاز به سرور — پاور را بزن"
                }
                "smart" -> {
                    serverName.text = "اتصال هوشمند"
                    serverName.setTextColor(p.accent)
                    serverIp.text = "لنترن ← وارپ ← بهترین سرور — خودش انتخاب می‌کند"
                }
                "hybrid" -> {
                    serverName.text = "حالت ترکیبی (Vex + لنترن)"
                    serverName.setTextColor(p.accent)
                    serverIp.text = "اول یک سرور انتخاب کن — اتصالش از لنترن می‌گذرد"
                }
                "auto" -> {
                    serverName.text = "اتصال خودکار"
                    serverName.setTextColor(p.accent)
                    serverIp.text = "بهترین سرور رایگان خودش پیدا و انتخاب می‌شود"
                }
                else -> {
                    serverName.text = "سروری انتخاب نشده"
                    serverName.setTextColor(p.muted)
                    serverIp.text = "برای انتخاب، کارت را لمس کن"
                }
            }
            serverBadgeText.text = "—"
            serverBadge.background = UI.card(act, p, 10, fillColor = p.card2)
            serverBadgeText.setTextColor(p.muted)
            serverPing.text = "—"
            serverPing.setTextColor(p.muted)
            serverPingIc.iconColor = p.muted
            return
        }
        serverName.text = s.name
        serverName.setTextColor(p.text)
        if (act.store.isFree(s)) {
            // سرورهای رایگان: مشخصات (IP/پورت) هرگز نشان داده نمی‌شود
            serverIp.text = "سرور رایگان — مشخصات مخفی است"
        } else {
            val inf = try { LinkParser.info(s.raw) } catch (_: Throwable) { null }
            serverIp.text = inf?.host ?: "—"
        }
        serverBadgeText.text = UI.protoLabel(s.protocol)
        serverBadge.background = UI.card(act, p, 10, fillColor = 0xFFC43A32.toInt())
        serverBadgeText.setTextColor(Color.WHITE)
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
