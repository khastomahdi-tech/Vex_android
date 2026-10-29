package com.vex.client

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * صفحه‌ی سرورها (نیتیو): لیست، انتخاب سرور فعال، پینگ، حذف، افزودن.
 */
class ServersUi(private val act: MainActivity) {

    lateinit var root: LinearLayout
        private set

    private lateinit var title: TextView
    private lateinit var addBtn: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var pingAllBtn: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var emptyHint: TextView
    private lateinit var bottomBar: LinearLayout

    private val checked = mutableSetOf<Int>()
    private var p: Palette = Themes.dark()

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = UI.col(ctx)
        root.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 10))

        // ---- نوار بالا
        val top = UI.row(ctx)
        title = UI.label(ctx, "سرورها", 19f, p.text, bold = true)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addBtn = UI.primaryButton(ctx, "+ افزودن", p)
        addBtn.setOnClickListener { act.showAddMenu() }
        top.addView(title)
        top.addView(addBtn)
        root.addView(top)

        val hint = UI.label(ctx, "برای فعال‌سازی روی سرور ضربه بزنید · نگه‌داشتن = منوی سرور", 11.5f, p.muted)
        hint.setPadding(0, UI.dp(ctx, 4), 0, UI.dp(ctx, 8))
        root.addView(hint)

        // ---- لیست
        listContainer = UI.col(ctx)
        listScroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listScroll.addView(listContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        listScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(listScroll)

        emptyHint = UI.label(ctx, "هنوز سروری اضافه نشده.\nاز دکمه‌ی «+ افزودن» شروع کن: پیست، QR، فایل، دستی یا اشتراک.", 14f, p.muted)
        emptyHint.gravity = Gravity.CENTER
        emptyHint.setPadding(UI.dp(ctx, 20), UI.dp(ctx, 60), UI.dp(ctx, 20), 0)

        // ---- نوار پایین
        bottomBar = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }
        pingAllBtn = UI.softButton(ctx, "پینگ همه", p)
        pingAllBtn.setOnClickListener { act.pingAll() }
        deleteBtn = UI.softButton(ctx, "حذف انتخاب‌شده", p)
        deleteBtn.setTextColor(p.bad)
        deleteBtn.setOnClickListener {
            if (checked.isEmpty()) UI.toast(ctx, "چیزی برای حذف انتخاب نشده")
            else act.confirmDelete(checked.toSet()) { checked.clear(); render() }
        }
        pingAllBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = UI.dp(ctx, 6) }
        deleteBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = UI.dp(ctx, 6) }
        bottomBar.addView(pingAllBtn)
        bottomBar.addView(deleteBtn)
        root.addView(bottomBar)

        applyTheme(p)
        return root
    }

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        title.setTextColor(p.text)
        addBtn.background = UI.primaryBg(ctx, p)
        addBtn.setTextColor(p.onAccent)
        pingAllBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        pingAllBtn.setTextColor(p.text)
        deleteBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        deleteBtn.setTextColor(p.bad)
        render()
    }

    // ---------------------------------------------------------- رندر لیست

    fun render() {
        listContainer.removeAllViews()
        val ctx = act
        val servers = act.store.servers
        title.text = "سرورها (${servers.size})"
        if (servers.isEmpty()) {
            if (emptyHint.parent == null) listContainer.addView(emptyHint)
            emptyHint.visibility = View.VISIBLE
            return
        } else {
            emptyHint.visibility = View.GONE
        }
        for (s in servers) listContainer.addView(serverRow(s))
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

        if (selected) {
            val active = UI.label(ctx, "● فعال", 11f, p.accent, bold = true)
            active.setPadding(0, UI.dp(ctx, 4), 0, 0)
            col.addView(active)
        }

        top.addView(col)

        val ping = UI.pill(ctx, UI.pingText(s.ping),
            UI.alpha(UI.pingColor(p, s.ping), 0.18f), UI.pingColor(p, s.ping))
        top.addView(ping, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 8)
        })

        card.addView(top)

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
