package com.vex.client

import android.animation.ObjectAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.animation.OvershootInterpolator

/**
 * صفحه‌ی سرورها (نیتیو) — دو تب:
 *  ۱) «سرورهای من»: کانفیگ‌های خود کاربر — افزودن با «+»، ویرایش، حذف، پینگ
 *  ۲) «سرورهای رایگان»: به‌صورت خودکار از منابع اشتراک عمومی گرفته می‌شوند؛
 *     فقط سرورهای سالم (پینگ‌دار) نشان داده می‌شوند؛ نام همه «Vex Free Server» است؛
 *     کاربر نمی‌تواند ویرایش/حذف کند یا مشخصات سرور را ببیند — فقط لمس = اتصال، یا پینگ.
 */
class ServersUi(private val act: MainActivity) {

    lateinit var root: FrameLayout
        private set

    /** تب فعلی: 0 = سرورهای من، 1 = سرورهای رایگان */
    var tab: Int = 0
        private set

    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var refreshBtn: FrameLayout
    private lateinit var refreshIc: IconView
    private lateinit var timerBtn: FrameLayout
    private lateinit var timerIc: IconView
    private lateinit var tabMine: TextView
    private lateinit var tabFree: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var freeProgress: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var deleteBtn: TextView
    private lateinit var fab: FrameLayout

    private val checked = mutableSetOf<Int>()
    private var p: Palette = Themes.dark()

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = FrameLayout(ctx)

        // ---- ستون اصلی (همه‌چیز داخل یک اسکرول — با هدر هم‌راه حرکت می‌کند)
        content = UI.col(ctx)
        content.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 96))

        // ---- نوار بالا: عنوان + دریافت رایگان + پینگ همه
        val top = UI.row(ctx)
        title = UI.label(ctx, "سرورها", 19f, p.text, bold = true)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(title)

        // دکمه‌ی دریافت/به‌روزرسانی سرورهای رایگان (فقط در تب رایگان دیده می‌شود)
        refreshBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11))
            visibility = View.GONE
            setOnClickListener { act.refreshFreeServers() }
        }
        refreshIc = IconView(ctx, "globe").apply { bold = true }
        refreshBtn.addView(refreshIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 22), UI.dp(ctx, 22), Gravity.CENTER))
        top.addView(refreshBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 46), UI.dp(ctx, 46)).apply { marginStart = UI.dp(ctx, 8) })

        // دکمه‌ی زمان‌سنج — پینگ همه‌ی سرورهای تب فعلی
        timerBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11))
            setOnClickListener {
                if (tab == 0) act.pingAll()
                else act.pingFreeAll()
            }
        }
        timerIc = IconView(ctx, "timer").apply { bold = true }
        timerBtn.addView(timerIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 22), UI.dp(ctx, 22), Gravity.CENTER))
        top.addView(timerBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 46), UI.dp(ctx, 46)).apply { marginStart = UI.dp(ctx, 8) })
        content.addView(top)

        // ---- تب‌ها: سرورهای من | سرورهای رایگان
        val tabsRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 8), 0, 0) }
        tabMine = tabPill("سرورهای من")
        tabFree = tabPill("سرورهای رایگان")
        tabsRow.addView(tabMine, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabsRow.addView(tabFree, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(ctx, 8)
        })
        content.addView(tabsRow)

        val hint = UI.label(ctx, "", 11.5f, p.muted).apply { tag = "hint" }
        hint.setPadding(0, UI.dp(ctx, 6), 0, UI.dp(ctx, 8))
        content.addView(hint)

        // ---- لیست (مستقیم داخل ستون؛ کل صفحه با هم اسکرول می‌شود)
        listContainer = UI.col(ctx)
        content.addView(listContainer)

        emptyHint = UI.label(ctx, "", 14f, p.muted)
        emptyHint.gravity = Gravity.CENTER
        emptyHint.setPadding(UI.dp(ctx, 20), UI.dp(ctx, 50), UI.dp(ctx, 20), 0)

        freeProgress = UI.label(ctx, "", 13.5f, p.accent, bold = true)
        freeProgress.gravity = Gravity.CENTER
        freeProgress.setPadding(UI.dp(ctx, 20), UI.dp(ctx, 70), UI.dp(ctx, 20), 0)
        freeProgress.visibility = View.GONE

        // ---- نوار پایین (فقط تب «سرورهای من» وقتی چک‌باکسی انتخاب شده باشد)
        bottomBar = UI.row(ctx).apply {
            setPadding(0, UI.dp(ctx, 10), 0, 0)
            visibility = View.GONE
        }
        deleteBtn = UI.softButton(ctx, "حذف انتخاب‌شده‌ها", p)
        deleteBtn.setTextColor(p.bad)
        deleteBtn.setOnClickListener {
            if (checked.isEmpty()) UI.toast(ctx, "چیزی برای حذف انتخاب نشده")
            else act.confirmDelete(checked.toSet()) { checked.clear(); syncSelectionMode(); render() }
        }
        deleteBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bottomBar.addView(deleteBtn)

        // ---- کل صفحه (هدر + عنوان + تب‌ها + لیست) داخل یک اسکرول واحد
        val outerScroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        outerScroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(outerScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ---- نوار حذف به‌صورت ثابت پایین صفحه (بیرون از اسکرول)
        root.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
            setMargins(UI.dp(ctx, 18), 0, UI.dp(ctx, 18), UI.dp(ctx, 10))
        })

        // ---- دکمه‌ی شناور «+» — پایین سمت راست (فقط تب سرورهای من)
        fab = buildFab()
        root.addView(fab, FrameLayout.LayoutParams(
            UI.dp(ctx, 58), UI.dp(ctx, 58),
            Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = UI.dp(ctx, 18)
            bottomMargin = UI.dp(ctx, 18)
        })

        applyTheme(p)
        return root
    }

    /** چیپ تب بالای لیست */
    private fun tabPill(text: String): TextView {
        val ctx = act
        val t = TextView(ctx).apply {
            this.text = text
            textSize = 13.5f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            setPadding(UI.dp(ctx, 12), UI.dp(ctx, 10), UI.dp(ctx, 12), UI.dp(ctx, 10))
            setOnClickListener { switchTab(if (text == "سرورهای من") 0 else 1) }
        }
        return t
    }

    fun switchTab(idx: Int) {
        tab = idx
        checked.clear()
        render()
        // دریافت خودکار: اولین باز شدن تب رایگان و لیست خالی → خودکار از منابع عمومی بگیر
        if (idx == 1 && act.store.freeServers.isEmpty() && act.store.freeLastFetch == 0L &&
            !FreeServers.fetching) {
            act.refreshFreeServers()
        }
    }

    /** دکمه‌ی شناور گرد طلایی با «+» — با جهش نرم هنگام لمس */
    private fun buildFab(): FrameLayout {
        val ctx = act
        val f = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 15), UI.dp(ctx, 15), UI.dp(ctx, 15), UI.dp(ctx, 15))
            elevation = UI.dp(ctx, 8).toFloat()
            contentDescription = "افزودن سرور"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                colors = intArrayOf(0xFFF0C64B.toInt(), 0xFFB9861B.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                setStroke(UI.dp(ctx, 1), UI.alpha(0xFFF7DE8A.toInt(), 0.7f))
            }
            setOnClickListener {
                // جهش کوچک بازخورد لمسی
                ObjectAnimator.ofFloat(it, "scaleX", 1f, 0.9f, 1f).setDuration(180).start()
                ObjectAnimator.ofFloat(it, "scaleY", 1f, 0.9f, 1f).setDuration(180).start()
                act.showAddMenu()
            }
        }
        val ic = IconView(ctx, "plus").apply {
            iconColor = 0xFF1A1304.toInt(); bold = true
        }
        f.addView(ic, FrameLayout.LayoutParams(
            UI.dp(ctx, 28), UI.dp(ctx, 28), Gravity.CENTER))
        return f
    }

    /** وقتی سروری انتخاب (چک) شد: نوار حذف می‌آید و دکمه‌ی + پنهان می‌شود */
    private fun syncSelectionMode() {
        val selecting = tab == 0 && checked.isNotEmpty()
        bottomBar.visibility = if (selecting) View.VISIBLE else View.GONE
        fab.visibility = if (tab == 0 && !selecting) View.VISIBLE else View.GONE
    }

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        title.setTextColor(p.text)
        // دکمه‌های گرد طلایی — هم‌سبک دکمه‌های صفحه‌ی خانه
        timerBtn.background = UI.card(ctx, p, 15, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        timerIc.iconColor = p.accent
        refreshBtn.background = UI.card(ctx, p, 15, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        refreshIc.iconColor = p.accent
        deleteBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        deleteBtn.setTextColor(p.bad)
        render()
    }

    // ---------------------------------------------------------- رندر لیست

    fun render() {
        listContainer.removeAllViews()
        val ctx = act
        syncSelectionMode()
        if (tab == 0) renderMine() else renderFree()

        // تمپ چیپ‌های تب
        val activeBg = UI.card(ctx, p, 20, fillColor = UI.alpha(p.accent, 0.2f), stroke = p.accent)
        val idleBg = UI.card(ctx, p, 20, fillColor = p.card2, stroke = p.line)
        tabMine.background = if (tab == 0) activeBg else idleBg
        tabFree.background = if (tab == 1) activeBg else idleBg
        tabMine.setTextColor(if (tab == 0) p.accent else p.text)
        tabFree.setTextColor(if (tab == 1) p.accent else p.text)

        // متن راهنمای هر تب
        refreshBtn.visibility = if (tab == 1) View.VISIBLE else View.GONE
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v is TextView && v.tag == "hint") {
                v.text = if (tab == 0)
                    "برای فعال‌سازی روی سرور ضربه بزنید · نگه‌داشتن = منوی سرور"
                else
                    "لمس = اتصال · فقط سرورهای سالم و پینگ‌دار نمایش داده می‌شوند"
            }
        }
    }

    private fun renderMine() {
        val ctx = act
        title.text = "سرورها (${act.store.servers.size})"
        val servers = act.store.servers
        if (servers.isEmpty()) {
            emptyHint.text = "هنوز سروری اضافه نشده.\nاز دکمه‌ی «+» پایین صفحه شروع کن:\nپیست، QR، فایل، دستی یا اشتراک."
            if (emptyHint.parent == null) listContainer.addView(emptyHint)
            emptyHint.visibility = View.VISIBLE
            freeProgress.visibility = View.GONE
        } else {
            emptyHint.visibility = View.GONE
            freeProgress.visibility = View.GONE
            for (s in servers) listContainer.addView(serverRow(s))
        }
    }

    private fun renderFree() {
        val ctx = act
        val free = act.store.freeServers
        title.text = "سرورها (${free.size} رایگان)"
        // در تب رایگان: بدون ویرایش/حذف/افزودن
        emptyHint.visibility = View.GONE
        if (FreeServers.fetching) {
            freeProgress.text = freeProgressMsg
            if (freeProgress.parent == null) listContainer.addView(freeProgress)
            freeProgress.visibility = View.VISIBLE
            return
        }
        freeProgress.visibility = View.GONE
        if (free.isEmpty()) {
            emptyHint.text = "هنوز سرور رایگانی دریافت نشده.\nروی دکمه‌ی کره‌ی زمین بالای صفحه بزن تا به‌صورت خودکار\nاز منابع عمومی گرفته و پینگ شود."
            if (emptyHint.parent == null) listContainer.addView(emptyHint)
            emptyHint.visibility = View.VISIBLE
        } else {
            for (s in free) listContainer.addView(freeRow(s))
        }
    }

    /** آخرین پیام پیشرفت دریافت سرورهای رایگان (از MainActivity پست می‌شود) */
    var freeProgressMsg: String = "در حال دریافت…"
        set(value) { field = value; if (::freeProgress.isInitialized) freeProgress.text = value }

    // ---------------------------------------------------------- ردیف سرور کاربر

    /** دکمه‌ی گرد کوچک با آیکون، برای ردیف عملیات هر سرور */
    private fun circAction(icon: String, tint: Int, desc: String, onClick: () -> Unit): View {
        val ctx = act
        val b = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8))
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        // مداد و سطل آشغال: آیکون وکتور (SVG) واقعی با رنگ‌آمیزی تم
        if (icon == "pencil" || icon == "trash") {
            val iv = ImageView(ctx).apply {
                setImageResource(if (icon == "pencil") R.drawable.ic_pencil else R.drawable.ic_trash)
                setColorFilter(tint)
            }
            b.addView(iv, FrameLayout.LayoutParams(UI.dp(ctx, 19), UI.dp(ctx, 19), Gravity.CENTER))
        } else {
            val ic = IconView(ctx, icon).apply { iconColor = tint; bold = true }
            b.addView(ic, FrameLayout.LayoutParams(UI.dp(ctx, 19), UI.dp(ctx, 19), Gravity.CENTER))
        }
        b.background = UI.card(ctx, p, 17, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        return b
    }

    private fun serverRow(s: Server): View {
        val ctx = act
        val selected = act.store.selectedId == s.id
        val card = UI.row(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 12), UI.dp(ctx, 12), UI.dp(ctx, 12))
        card.background = UI.card(ctx, p, 16,
            stroke = if (selected) p.accent else p.line,
            fillColor = if (selected) UI.alpha(p.accent, 0.08f) else p.card)

        val top = UI.row(ctx)
        val cb = CheckBox(ctx)
        cb.isChecked = s.id in checked
        cb.setOnCheckedChangeListener { _, now ->
            if (now) checked.add(s.id) else checked.remove(s.id)
            syncSelectionMode()
        }
        top.addView(cb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 4)
        })

        val col = UI.col(ctx)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val name = UI.label(ctx, s.name, 15f, p.text, bold = true)
        name.maxLines = 1
        col.addView(name)

        val subRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 3), 0, 0) }
        val badge = UI.pill(ctx, UI.protoLabel(s.protocol),
            UI.alpha(p.accent, 0.16f), p.accent)
        subRow.addView(badge)
        val inf = try { LinkParser.info(s.raw) } catch (_: Throwable) { null }
        val hp = inf?.host?.let { h -> inf.port?.let { po -> "$h:$po" } ?: h } ?: "—"
        val addr = UI.label(ctx, hp, 12f, p.muted)
        addr.textDirection = View.TEXT_DIRECTION_LTR
        addr.setPadding(UI.dp(ctx, 8), 0, 0, 0)
        addr.maxLines = 1
        subRow.addView(addr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(subRow)

        top.addView(col)

        val ping = UI.pill(ctx, UI.pingText(s.ping),
            UI.alpha(UI.pingColor(p, s.ping), 0.18f), UI.pingColor(p, s.ping))
        top.addView(ping, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 8)
        })

        card.addView(top)

        // ---- ردیف عملیات: پینگ (زمان‌سنج) / ویرایش (مداد) / حذف (سطل) + نشان فعال
        val actions = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }

        val activeTag = UI.label(ctx, if (selected) "● فعال" else "", 11f, p.accent, bold = true)
        activeTag.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        actions.addView(activeTag)

        fun actionLp(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(UI.dp(ctx, 35), UI.dp(ctx, 35)).apply { marginStart = UI.dp(ctx, 6) }

        // پینگ همین سرور
        actions.addView(circAction("timer", p.accent, "پینگ سرور") {
            act.pingServers(listOf(s.id))
        }, actionLp())
        // ویرایش اطلاعات سرور
        actions.addView(circAction("pencil", p.text, "ویرایش سرور") {
            act.showEditServer(s)
        }, actionLp())
        // حذف سرور
        actions.addView(circAction("trash", p.bad, "حذف سرور") {
            act.confirmDelete(setOf(s.id)) { act.renderAll() }
        }, actionLp())

        card.addView(actions)

        // انتخاب به‌عنوان سرور فعال + منوی نگه‌داشتن
        card.setOnClickListener { act.selectServer(s) }
        card.setOnLongClickListener {
            act.showServerMenu(s)
            true
        }

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = UI.dp(ctx, 10)
        card.layoutParams = lp
        return card
    }

    // ---------------------------------------------------------- ردیف سرور رایگان

    /**
     * ردیف ساده‌ی سرور رایگان:
     *  فقط نام یکسان «Vex Free Server N» + نشان پروتکل + پینگ + دکمه‌ی پینگ تکی.
     *  بدون آدرس/مشخصات، بدون ویرایش/حذف، بدون منوی نگه‌داشتن.
     */
    private fun freeRow(s: Server): View {
        val ctx = act
        val selected = act.store.selectedId == s.id
        val card = UI.row(ctx)
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 11), UI.dp(ctx, 12), UI.dp(ctx, 11))
        card.background = UI.card(ctx, p, 16,
            stroke = if (selected) p.accent else p.line,
            fillColor = if (selected) UI.alpha(p.accent, 0.08f) else p.card)

        // نشان پروتکل
        val badge = UI.pill(ctx, UI.protoLabel(s.protocol),
            UI.alpha(p.accent, 0.16f), p.accent)
        card.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 10)
        })

        // نام + پینگ (ستون وسط)
        val col = UI.col(ctx)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val name = UI.label(ctx, s.name, 14.5f, p.text, bold = true)
        name.maxLines = 1
        col.addView(name)
        val pingLine = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 3), 0, 0) }
        val ping = UI.label(ctx, UI.pingText(s.ping), 12f, UI.pingColor(p, s.ping), bold = true).apply {
            textDirection = View.TEXT_DIRECTION_LTR
        }
        pingLine.addView(ping)
        val active = UI.label(ctx, if (selected) "  ·  ● فعال" else "", 11f, p.accent, bold = true)
        pingLine.addView(active)
        col.addView(pingLine)
        card.addView(col)

        // دکمه‌ی پینگ تکی
        val pb = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8))
            contentDescription = "پینگ سرور رایگان"
            background = UI.card(ctx, p, 17, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        }
        val pi = IconView(ctx, "timer").apply { iconColor = p.accent; bold = true }
        pb.addView(pi, FrameLayout.LayoutParams(UI.dp(ctx, 17), UI.dp(ctx, 17), Gravity.CENTER))
        pb.setOnClickListener { act.pingServers(listOf(s.id)) }
        card.addView(pb, LinearLayout.LayoutParams(
            UI.dp(ctx, 33), UI.dp(ctx, 33)).apply { marginStart = UI.dp(ctx, 8) })

        // لمس = انتخاب و اتصال — هیچ منوی دیگری وجود ندارد
        card.setOnClickListener { act.selectServer(s) }

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = UI.dp(ctx, 10)
        card.layoutParams = lp
        return card
    }

    fun clearChecks() = checked.clear()
}
