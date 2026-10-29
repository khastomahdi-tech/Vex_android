package com.vex.client

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.animation.OvershootInterpolator

/**
 * صفحه‌ی سرورها (نیتیو):
 *  - نوار بالا: عنوان + دکمه‌ی زمان‌سنج (پینگ همه) — دکمه‌ی «افزودن» حذف شد
 *  - دکمه‌ی شناور «+» پایین سمت راست: منوی روش‌های واردکردن کانفیگ را باز می‌کند
 *  - کارت هر سرور: چک‌باکس، نام، آدرس، نشان پروتکل، پینگ +
 *    سه دکمه‌ی گرد: پینگ (زمان‌سنج)، ویرایش (مداد)، حذف (سطل آشغال)
 *  - لمس کارت = فعال‌سازی سرور، نگه‌داشتن = منوی کامل سرور
 */
class ServersUi(private val act: MainActivity) {

    lateinit var root: FrameLayout
        private set

    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var timerBtn: FrameLayout
    private lateinit var timerIc: IconView
    private lateinit var listContainer: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var deleteBtn: TextView
    private lateinit var emptyHint: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var fab: FrameLayout

    private val checked = mutableSetOf<Int>()
    private var p: Palette = Themes.dark()

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = FrameLayout(ctx)

        // ---- ستون اصلی
        content = UI.col(ctx)
        content.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 10))

        // ---- نوار بالا (فقط عنوان + پینگ همه)
        val top = UI.row(ctx)
        title = UI.label(ctx, "کانفیگها", 19f, p.text, bold = true)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(title)

        // دکمه‌ی زمان‌سنج — پینگ همه‌ی سرورها
        timerBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11))
            setOnClickListener { act.pingAll() }
        }
        timerIc = IconView(ctx, "timer").apply { bold = true }
        timerBtn.addView(timerIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 22), UI.dp(ctx, 22), Gravity.CENTER))
        top.addView(timerBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 46), UI.dp(ctx, 46)).apply { marginStart = UI.dp(ctx, 8) })
        content.addView(top)

        val hint = UI.label(ctx, "برای فعال‌سازی روی سرور ضربه بزنید · نگه‌داشتن = منوی سرور", 11.5f, p.muted)
        hint.setPadding(0, UI.dp(ctx, 4), 0, UI.dp(ctx, 8))
        content.addView(hint)

        // ---- لیست
        listContainer = UI.col(ctx)
        listScroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listScroll.addView(listContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        listScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        content.addView(listScroll)

        emptyHint = UI.label(ctx, "هنوز سروری اضافه نشده.\nاز دکمه‌ی «+» پایین صفحه شروع کن:\nپیست، QR، فایل، دستی یا اشتراک.", 14f, p.muted)
        emptyHint.gravity = Gravity.CENTER
        emptyHint.setPadding(UI.dp(ctx, 20), UI.dp(ctx, 60), UI.dp(ctx, 20), 0)

        // ---- نوار پایین (فقط وقتی چک‌باکسی انتخاب شده باشد ظاهر می‌شود)
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
        content.addView(bottomBar)

        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ---- دکمه‌ی شناور «+» — پایین سمت راست (منوی روش‌های وارد کردن)
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

    /** دکمه‌ی شناور گرد طلایی با «+» — با جهش نرم هنگام لمس */
    private fun buildFab(): FrameLayout {
        val ctx = act
        val f = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 15), UI.dp(ctx, 15), UI.dp(ctx, 15), UI.dp(ctx, 15))
            elevation = UI.dp(ctx, 8).toFloat()
            contentDescription = "افزودن کانفیگ"
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
        val selecting = checked.isNotEmpty()
        bottomBar.visibility = if (selecting) View.VISIBLE else View.GONE
        fab.visibility = if (selecting) View.GONE else View.VISIBLE
    }

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        title.setTextColor(p.text)
        // دکمه‌ی زمان‌سنج گرد طلایی — هم‌سبک دکمه‌ی کره‌ی زمین صفحه‌ی خانه
        timerBtn.background = UI.card(ctx, p, 15, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        timerIc.iconColor = p.accent
        deleteBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        deleteBtn.setTextColor(p.bad)
        render()
    }

    // ---------------------------------------------------------- رندر لیست

    fun render() {
        listContainer.removeAllViews()
        val ctx = act
        val servers = act.store.servers
        title.text = "کانفیگها (${servers.size})"
        if (servers.isEmpty()) {
            if (emptyHint.parent == null) listContainer.addView(emptyHint)
            emptyHint.visibility = View.VISIBLE
        } else {
            emptyHint.visibility = View.GONE
            for (s in servers) listContainer.addView(serverRow(s))
        }
        syncSelectionMode()
    }

    /** دکمه‌ی گرد کوچک با آیکون، برای ردیف عملیات هر سرور */
    private fun circAction(icon: String, tint: Int, desc: String, onClick: () -> Unit): View {
        val ctx = act
        val b = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8))
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        val ic = IconView(ctx, icon).apply { iconColor = tint; bold = true }
        b.addView(ic, FrameLayout.LayoutParams(UI.dp(ctx, 19), UI.dp(ctx, 19), Gravity.CENTER))
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

    fun clearChecks() = checked.clear()
}
