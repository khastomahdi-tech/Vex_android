package com.vex.client

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Switch

/**
 * صفحه‌ی تنظیمات (نیتیو): تم، MTU، DNS، IPv6، دور زدن LAN، Mux،
 * اتصال خودکار، سطح لاگ، مشاهده‌ی لاگ هسته، بازنشانی.
 */
class SettingsUi(private val act: MainActivity) {

    lateinit var root: LinearLayout
        private set

    private lateinit var themeChips: LinearLayout
    private lateinit var mtuEdit: EditText
    private lateinit var dnsEdit: EditText
    private lateinit var swIpv6: Switch
    private lateinit var swLan: Switch
    private lateinit var swMux: Switch
    private lateinit var swAuto: Switch
    private lateinit var logSpinner: Spinner
    private lateinit var pingSpinner: Spinner
    private lateinit var logBtn: TextView
    private lateinit var resetBtn: TextView

    private var p: Palette = Themes.dark()
    private var building = false

    fun build(): View {
        val ctx = act
        building = true
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = UI.col(ctx)
        root.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 14))
        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }

        // ================= تم =================
        val themeCard = UI.col(ctx)
        themeCard.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 14))
        themeCard.addView(sectionLabel(ctx, "تم"))
        themeChips = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 8), 0, 0) }
        themeChips.addView(themeChip("سلطنتی VEX", "royal"))
        themeChips.addView(themeChip("سیستم", "system"))
        themeChips.addView(themeChip("تیره", "dark"))
        themeChips.addView(themeChip("روشن", "light"))
        themeChips.addView(themeChip("شیر و خورشید", "shir"))
        themeCard.addView(themeChips)
        addCard(themeCard)

        // ================= اتصال =================
        val connCard = UI.col(ctx)
        connCard.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 14))
        connCard.addView(sectionLabel(ctx, "اتصال"))

        connCard.addView(fieldLabel(ctx, "MTU (۱۲۸۰ تا ۹۰۰۰)"))
        mtuEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(act.store.settings.mtu.toString())
            textSize = 14f
            setSingleLine(true)
        }
        mtuEdit.setOnFocusChangeListener { _, has -> if (!has) commitMtu() }
        connCard.addView(mtuEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UI.dp(ctx, 4)
        })

        connCard.addView(fieldLabel(ctx, "DNS (مثل 1.1.1.1)"))
        dnsEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(act.store.settings.dns)
            textSize = 14f
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
        }
        dnsEdit.setOnFocusChangeListener { _, has -> if (!has) commitDns() }
        connCard.addView(dnsEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UI.dp(ctx, 4)
        })

        connCard.addView(switchRow("پروتکل IPv6", act.store.settings.ipv6) { v -> act.store.settings.ipv6 = v; act.store.saveSettings() })
        connCard.addView(switchRow("دور زدن شبکه‌ی محلی (LAN)", act.store.settings.bypassLan) { v -> act.store.settings.bypassLan = v; act.store.saveSettings() })
        connCard.addView(switchRow("Mux (چندگانه‌سازی)", act.store.settings.mux) { v -> act.store.settings.mux = v; act.store.saveSettings() })
        connCard.addView(switchRow("رد کردن تأیید گواهی TLS (allowInsecure)", act.store.settings.allowInsecure) { v -> act.store.settings.allowInsecure = v; act.store.saveSettings() })
        connCard.addView(switchRow("اتصال خودکار هنگام باز شدن برنامه", act.store.settings.autoConnect) { v -> act.store.settings.autoConnect = v; act.store.saveSettings() })
        connCard.addView(switchRow("اتصال مجدد خودکار پس از قطعی", act.store.settings.autoReconnect) { v -> act.store.settings.autoReconnect = v; act.store.saveSettings() })
        connCard.addView(switchRow("تست خودکار اینترنت پس از اتصال", act.store.settings.autoNetTest) { v -> act.store.settings.autoNetTest = v; act.store.saveSettings() })

        // DNSهای آماده (کلادفلر/گوگل/کوآد۹/شکن/رادار)
        connCard.addView(fieldLabel(ctx, "DNSهای آماده"))
        val dnsScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val dnsChipsRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 6), 0, 0) }
        for (d in arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "178.22.122.100", "10.202.10.10")) {
            dnsChipsRow.addView(dnsChip(d))
        }
        dnsScroll.addView(dnsChipsRow)
        connCard.addView(dnsScroll)
        addCard(connCard)

        // ================= پیشرفته =================
        val advCard = UI.col(ctx)
        advCard.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 14))
        advCard.addView(sectionLabel(ctx, "پیشرفته"))

        advCard.addView(fieldLabel(ctx, "سطح لاگ هسته"))
        logSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("info", "warning", "debug"))
            setSelection(when (act.store.settings.logLevel) {
                "warning" -> 1
                "debug" -> 2
                else -> 0
            })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (building) return
                    act.store.settings.logLevel = when (pos) { 1 -> "warning"; 2 -> "debug"; else -> "info" }
                    act.store.saveSettings()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        advCard.addView(logSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 44)).apply { topMargin = UI.dp(ctx, 4) })

        advCard.addView(fieldLabel(ctx, "تایم‌اوت پینگ TCP"))
        pingSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("۲ ثانیه", "۴ ثانیه", "۸ ثانیه"))
            setSelection(when (act.store.settings.pingTimeout) { 2 -> 0; 8 -> 2; else -> 1 })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (building) return
                    act.store.settings.pingTimeout = when (pos) { 0 -> 2; 2 -> 8; else -> 4 }
                    act.store.saveSettings()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        advCard.addView(pingSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 44)).apply { topMargin = UI.dp(ctx, 4) })

        logBtn = UI.softButton(ctx, "مشاهده‌ی لاگ هسته", p)
        logBtn.setOnClickListener { act.showCoreLog() }
        advCard.addView(logBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UI.dp(ctx, 12)
        })

        resetBtn = UI.softButton(ctx, "بازنشانی تنظیمات", p)
        resetBtn.setTextColor(p.bad)
        resetBtn.setOnClickListener { act.resetSettings() }
        advCard.addView(resetBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UI.dp(ctx, 8)
        })

        val note = UI.label(ctx, "تغییرات برای اتصال بعدی اعمال می‌شود.", 11.5f, p.muted)
        note.setPadding(0, UI.dp(ctx, 10), 0, 0)
        advCard.addView(note)
        addCard(advCard)

        // نسخه‌ی برنامه
        val ver = UI.label(ctx, "VEX CLIENT v3.3.0 · هسته sing-box v1.11.15 · آمار از Clash API", 11f, p.muted)
        ver.gravity = Gravity.CENTER
        ver.setPadding(0, UI.dp(ctx, 16), 0, UI.dp(ctx, 4))
        root.addView(ver)

        scroll.addView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.isFillViewport = true

        building = false
        applyTheme(p)
        return scroll
    }

    // ---------------------------------------------------------- سازنده‌ها

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

    private fun fieldLabel(ctx: Context, text: String): TextView =
        UI.label(ctx, text, 12.5f, p.muted).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }

    private fun switchRow(text: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val ctx = act
        val row = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 12), 0, 0) }
        val tv = UI.label(ctx, text, 14.5f, p.text)
        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val sw = Switch(ctx).apply {
            isChecked = value
            setOnCheckedChangeListener { _, now -> onChange(now) }
        }
        row.addView(tv)
        row.addView(sw)
        return row
    }

    private fun themeChip(text: String, key: String): View {
        val ctx = act
        val chip = UI.pill(ctx, text, p.card2, p.text)
        chip.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 9), UI.dp(ctx, 14), UI.dp(ctx, 9))
        chip.textSize = 13f
        chip.typeface = Fonts.bold(ctx)
        chip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 8)
        }
        chip.setOnClickListener { act.setTheme(key) }
        chip.tag = key
        return chip
    }

    /** چیپ انتخاب DNS آماده */
    private fun dnsChip(addr: String): View {
        val ctx = act
        val chip = UI.pill(ctx, addr, p.card2, p.text)
        chip.textSize = 11.5f
        chip.textDirection = View.TEXT_DIRECTION_LTR
        chip.setPadding(UI.dp(ctx, 10), UI.dp(ctx, 7), UI.dp(ctx, 10), UI.dp(ctx, 7))
        chip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 6)
        }
        chip.setOnClickListener {
            dnsEdit.setText(addr)
            act.store.settings.dns = addr
            act.store.saveSettings()
            UI.toast(act, "DNS روی $addr تنظیم شد")
        }
        chip.tag = "dns:$addr"
        return chip
    }

    // ---------------------------------------------------------- رندر / تم

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        for (i in 0 until root.childCount) {
            val wrap = root.getChildAt(i)
            if (wrap is LinearLayout && wrap.childCount == 1) {
                wrap.getChildAt(0).background = UI.card(ctx, p)
            }
        }
        // رنگ لیبل‌ها
        recolor(root, p)
        // چیپ‌های تم
        for (i in 0 until themeChips.childCount) {
            val chip = themeChips.getChildAt(i) as TextView
            val active = chip.tag == act.store.settings.theme
            chip.background = UI.card(ctx, p, 20,
                fillColor = if (active) UI.alpha(p.accent, 0.2f) else p.card2,
                stroke = if (active) p.accent else p.line)
            chip.setTextColor(if (active) p.accent else p.text)
        }
        logBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        logBtn.setTextColor(p.text)
        resetBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        resetBtn.setTextColor(p.bad)
    }

    private fun recolor(v: View, p: Palette) {
        if (v is TextView) {
            // فقط لیبل‌های متنی ساده رنگ بگیرند؛ دکمه‌ها بعداً ست می‌شوند
            if (v.background !is android.graphics.drawable.GradientDrawable) v.setTextColor(p.text)
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) recolor(v.getChildAt(i), p)
        }
    }

    fun refresh() {
        applyTheme(Themes.resolve(act, act.store.settings.theme))
    }

    private fun commitMtu() {
        val v = mtuEdit.text.toString().trim().toIntOrNull() ?: 1500
        act.store.settings.mtu = v.coerceIn(1280, 9000)
        act.store.saveSettings()
        if (mtuEdit.text.toString().trim().toIntOrNull() != act.store.settings.mtu)
            mtuEdit.setText(act.store.settings.mtu.toString())
    }

    private fun commitDns() {
        val d = dnsEdit.text.toString().trim()
        act.store.settings.dns = d.ifEmpty { "1.1.1.1" }
        act.store.saveSettings()
    }
}
