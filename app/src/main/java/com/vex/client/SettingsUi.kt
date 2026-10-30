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

/**
 * صفحه‌ی تنظیمات VEX — ساختار دقیق و مرتب مثل V2RayNG:
 * هر بخش یک کارت مستقل با تیتر رنگی و آیکون است و ردیف‌ها با خط جداکننده از هم جدا می‌شوند.
 * بخش‌ها: اتصال / تونل / برنامه‌ها (per-app) / هسته / ابزارها / درباره
 * (بخش «ظاهر» حذف شد — ظاهر برنامه همان قالب سلطنتی شیر و خورشید می‌ماند)
 * همه‌ی ردیف‌ها کاملاً کلیک‌پذیرند (کل ردیف سوییچ را می‌زند).
 */
class SettingsUi(private val act: MainActivity) {

    lateinit var root: LinearLayout
        private set

    private lateinit var mtuEdit: EditText
    private lateinit var dnsEdit: EditText
    private val allSwitches = ArrayList<VexSwitch>(10)
    private lateinit var logSpinner: Spinner
    private lateinit var pingSpinner: Spinner
    private lateinit var perAppChips: LinearLayout
    private lateinit var perAppPickBtn: TextView
    private lateinit var perAppCount: TextView
    private val themedButtons = ArrayList<TextView>(4)
    private lateinit var domainSpinner: Spinner

    private var p: Palette = Themes.dark()
    private var building = false

    fun build(): View {
        val ctx = act
        building = true
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = UI.col(ctx)
        root.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 14))
        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }

        // ================= اتصال =================
        val connCard = sectionCard("اتصال", "signal")
        connCard.addView(switchRow("اتصال خودکار هنگام باز شدن برنامه",
            "وارد برنامه که شوی، خودکار به سرور انتخابی وصل می‌شود", act.store.settings.autoConnect) { v ->
            act.store.settings.autoConnect = v; act.store.saveSettings()
        })
        connCard.addView(divider())
        connCard.addView(switchRow("اتصال مجدد خودکار پس از قطعی",
            "اگر سرویس در پس‌زمینه قطع شود دوباره وصل می‌شود", act.store.settings.autoReconnect) { v ->
            act.store.settings.autoReconnect = v; act.store.saveSettings()
        })
        connCard.addView(divider())
        connCard.addView(switchRow("تست خودکار اینترنت پس از اتصال",
            "بعد از وصل شدن، واقعی بودن اینترنت بررسی می‌شود", act.store.settings.autoNetTest) { v ->
            act.store.settings.autoNetTest = v; act.store.saveSettings()
        })
        connCard.addView(divider())
        connCard.addView(switchRow("لرزش هنگام اتصال موفق",
            "با برقراری اتصال، بازخورد لرزشی کوتاه داده می‌شود", act.store.settings.hapticOnConnect) { v ->
            act.store.settings.hapticOnConnect = v; act.store.saveSettings()
        })
        connCard.addView(divider())
        connCard.addView(switchRow("نمایش سرعت زنده در اعلان",
            "سرعت دانلود/آپلود هر ثانیه در نوتیفیکیشن نشان داده می‌شود", act.store.settings.showNotifSpeed) { v ->
            act.store.settings.showNotifSpeed = v; act.store.saveSettings()
        })
        connCard.addView(divider())
        connCard.addView(switchRow("نمایش آی‌پی خروجی در صفحه‌ی خانه",
            "پس از اتصال، آی‌پی عمومی سرور + کشور + پرچم نشان داده می‌شود", act.store.settings.showExitIp) { v ->
            act.store.settings.showExitIp = v; act.store.saveSettings()
        })
        connCard.addView(divider())
        connCard.addView(switchRow("صفحه‌ی ورود (اسپلش)",
            "نمایش انیمیشن شیرهای طلایی هنگام باز شدن برنامه", act.store.settings.showIntro) { v ->
            act.store.settings.showIntro = v; act.store.saveSettings()
        })
        connCard.addView(divider())
        connCard.addView(switchRow("روشن ماندن صفحه در حین اتصال",
            "وقتی متصل است، صفحه‌ی گوشی خاموش نمی‌شود", act.store.settings.keepScreenOn) { v ->
            act.store.settings.keepScreenOn = v; act.store.saveSettings(); act.applyKeepScreenOn()
        })
        addCard(connCard)

        // ================= امنیت =================
        val secCard = sectionCard("امنیت", "check")
        secCard.addView(switchRow("قفل برنامه با رمز (PIN)",
            "با هر بار ورود، برنامه با کیبورد اختصاصی خودش رمز می‌خواهد",
            act.store.settings.pinHash.isNotEmpty()) { v ->
            if (v) {
                LockScreen.showSetup(act,
                    onDone = { hash, len ->
                        act.store.settings.pinHash = hash
                        act.store.settings.pinLen = len
                        act.store.saveSettings()
                        act.toast("قفل برنامه فعال شد 🎉")
                        act.rebuildSettings()
                    },
                    onCancel = { act.rebuildSettings() })
            } else {
                LockScreen.showVerify(act, "برای برداشتن قفل، رمز را وارد کن",
                    onOk = {
                        act.store.settings.pinHash = ""
                        act.store.settings.pinLen = 0
                        act.store.saveSettings()
                        act.toast("قفل برنامه برداشته شد")
                        act.rebuildSettings()
                    },
                    onCancel = { act.rebuildSettings() })
            }
        })
        secCard.addView(divider())
        val chPinBtn = softRowButton("تغییر رمز برنامه")
        chPinBtn.setOnClickListener {
            if (act.store.settings.pinHash.isEmpty()) {
                act.toast("اول قفل برنامه را فعال کن")
            } else {
                LockScreen.showVerify(act, "رمز فعلی را وارد کن",
                    onOk = {
                        LockScreen.showSetup(act,
                            onDone = { hash, len ->
                                act.store.settings.pinHash = hash
                                act.store.settings.pinLen = len
                                act.store.saveSettings()
                                act.toast("رمز برنامه عوض شد")
                                act.rebuildSettings()
                            },
                            onCancel = { act.rebuildSettings() })
                    },
                    onCancel = { act.rebuildSettings() })
            }
        }
        secCard.addView(chPinBtn)
        secCard.addView(divider())
        secCard.addView(switchRow("قفل دوباره پس از ترک برنامه",
            "اگر بیش از چند ثانیه از برنامه بیرون بروی، برگشتن دوباره رمز می‌خواهد",
            act.store.settings.relockOnExit) { v ->
            act.store.settings.relockOnExit = v
            act.store.saveSettings()
        })
        addCard(secCard)

        // ================= تونل =================
        val netCard = sectionCard("تونل", "globe")

        netCard.addView(fieldLabel(ctx, "MTU (۱۲۸۰ تا ۹۰۰۰)"))
        mtuEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(act.store.settings.mtu.toString())
            textSize = 14f
            typeface = Fonts.bold(ctx)
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(UI.dp(ctx, 12), UI.dp(ctx, 8), UI.dp(ctx, 12), UI.dp(ctx, 8))
        }
        mtuEdit.setOnFocusChangeListener { _, has -> if (!has) commitMtu() }
        netCard.addView(mtuEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UI.dp(ctx, 5)
        })

        netCard.addView(divider())
        netCard.addView(fieldLabel(ctx, "DNS (مثل 1.1.1.1)"))
        dnsEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(act.store.settings.dns)
            textSize = 14f
            typeface = Fonts.bold(ctx)
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(UI.dp(ctx, 12), UI.dp(ctx, 8), UI.dp(ctx, 12), UI.dp(ctx, 8))
        }
        dnsEdit.setOnFocusChangeListener { _, has -> if (!has) commitDns() }
        netCard.addView(dnsEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UI.dp(ctx, 5)
        })

        netCard.addView(divider())
        netCard.addView(fieldLabel(ctx, "DNSهای آماده"))
        val dnsScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val dnsChipsRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 6), 0, 0) }
        for (d in arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "178.22.122.100", "10.202.10.10")) {
            dnsChipsRow.addView(dnsChip(d))
        }
        dnsScroll.addView(dnsChipsRow)
        netCard.addView(dnsScroll)

        netCard.addView(divider())
        netCard.addView(switchRow("زیرکی ترافیک (Sniffing)",
            "شناسایی نوع ترافیک برای روتینگ دقیق‌تر — مثل v2rayNG", act.store.settings.sniffing) { v ->
            act.store.settings.sniffing = v; act.store.saveSettings()
        })
        netCard.addView(divider())
        netCard.addView(switchRow("پروتکل IPv6", "فعال‌سازی اینترنت IPv6 از طریق تونل", act.store.settings.ipv6) { v ->
            act.store.settings.ipv6 = v; act.store.saveSettings()
        })
        netCard.addView(divider())
        netCard.addView(switchRow("دور زدن شبکه‌ی محلی", "سایت‌های داخلی و LAN بدون VPN باز می‌شوند", act.store.settings.bypassLan) { v ->
            act.store.settings.bypassLan = v; act.store.saveSettings()
        })
        netCard.addView(divider())
        netCard.addView(switchRow("Mux (چندگانه‌سازی)", "چند اتصال روی یک لینک — کاهش تأخیر", act.store.settings.mux) { v ->
            act.store.settings.mux = v; act.store.saveSettings()
        })
        netCard.addView(divider())
        netCard.addView(switchRow("پدینگ Mux",
            "پوشاندن الگوی ترافیک داخل تونل Mux (فقط وقتی Mux روشن است)", act.store.settings.muxPadding) { v ->
            act.store.settings.muxPadding = v; act.store.saveSettings()
        })
        netCard.addView(divider())
        netCard.addView(switchRow("TCP Fast Open",
            "باز شدن سریع اتصال TCP — روی بعضی شبکه‌ها سرعت اولین اتصال بیشتر می‌شود", act.store.settings.tcpFastOpen) { v ->
            act.store.settings.tcpFastOpen = v; act.store.saveSettings()
        })
        netCard.addView(divider())
        netCard.addView(switchRow("رد کردن تأیید گواهی TLS", "allowInsecure — برای سرورهای با گواهی خودامضا", act.store.settings.allowInsecure) { v ->
            act.store.settings.allowInsecure = v; act.store.saveSettings()
        })
        netCard.addView(divider())
        netCard.addView(fieldLabel(ctx, "استراتژی دامنه (مثل v2rayNG)"))
        domainSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("ترجیح IPv4", "ترجیح IPv6", "فقط IPv4", "فقط IPv6"))
            setSelection(when (act.store.settings.domainStrategy) {
                "prefer_ipv6" -> 1
                "ipv4_only" -> 2
                "ipv6_only" -> 3
                else -> 0
            })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (building) return
                    act.store.settings.domainStrategy = when (pos) {
                        1 -> "prefer_ipv6"; 2 -> "ipv4_only"; 3 -> "ipv6_only"; else -> "prefer_ipv4"
                    }
                    act.store.saveSettings()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        netCard.addView(domainSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 44)).apply { topMargin = UI.dp(ctx, 4) })
        addCard(netCard)

        // ================= برنامه‌ها (per-app) =================
        val appCard = sectionCard("برنامه‌ها (Per-app)", "list")
        appCard.addView(subLabel("مثل v2rayNG: تعیین کن تونل برای کدام برنامه‌ها فعال باشد"))
        appCard.addView(divider(6))
        perAppChips = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, UI.dp(ctx, 2)) }
        perAppChips.addView(perAppChip("همه", 0))
        perAppChips.addView(perAppChip("فقط انتخابی‌ها", 1))
        perAppChips.addView(perAppChip("همه به‌جز انتخابی‌ها", 2))
        appCard.addView(perAppChips)

        perAppCount = UI.label(ctx, "", 12.5f, p.muted).apply {
            tag = "sub"
            setPadding(0, UI.dp(ctx, 8), 0, 0)
        }
        appCard.addView(perAppCount)

        appCard.addView(divider())
        perAppPickBtn = softRowButton("انتخاب برنامه‌ها…")
        perAppPickBtn.setOnClickListener {
            if (act.store.settings.perAppMode == 0) {
                act.store.settings.perAppMode = 2
                act.store.saveSettings()
                renderPerAppMode()
            }
            act.dialogs.showPerAppPicker()
        }
        appCard.addView(perAppPickBtn)
        addCard(appCard)

        // ================= هسته =================
        val coreCard = sectionCard("هسته", "chart")

        coreCard.addView(fieldLabel(ctx, "سطح لاگ هسته (loglevel)"))
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
        coreCard.addView(logSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 44)).apply { topMargin = UI.dp(ctx, 4) })

        coreCard.addView(divider())
        coreCard.addView(fieldLabel(ctx, "تایم‌اوت پینگ TCP"))
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
        coreCard.addView(pingSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 44)).apply { topMargin = UI.dp(ctx, 4) })
        addCard(coreCard)

        // ================= ابزارها =================
        val toolsCard = sectionCard("ابزارها", "gear")
        val logBtn = softRowButton("مشاهده‌ی لاگ هسته (sing-box)")
        logBtn.setOnClickListener { act.showCoreLog() }
        toolsCard.addView(logBtn)
        toolsCard.addView(divider())
        val errBtn = softRowButton("جزئیات آخرین خطا")
        errBtn.setOnClickListener { act.showErrDetails() }
        toolsCard.addView(errBtn)
        toolsCard.addView(divider())
        val pingSelBtn = softRowButton("پینگ سرور فعال")
        pingSelBtn.setOnClickListener {
            val cur = act.store.selected()
            if (cur == null) UI.toast(act, "اول یک سرور انتخاب کن")
            else act.pingServers(listOf(cur.id))
        }
        toolsCard.addView(pingSelBtn)
        toolsCard.addView(divider())
        toolsCard.addView(fieldLabel(ctx, "به‌روزرسانی خودکار سرورهای رایگان"))
        val fsSpin = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("هر ۳۰ ثانیه", "هر ۱ دقیقه", "هر ۵ دقیقه", "فقط هنگام باز شدن"))
            setSelection(when (act.store.settings.freeRefreshSec) {
                30 -> 0; 300 -> 2; 0 -> 3; else -> 1
            })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (building) return
                    act.store.settings.freeRefreshSec = when (pos) { 0 -> 30; 2 -> 300; 3 -> 0; else -> 60 }
                    act.store.saveSettings()
                    act.restartFreeAutoRefresh()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        toolsCard.addView(fsSpin, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 44)).apply { topMargin = UI.dp(ctx, 4) })
        toolsCard.addView(divider())
        val clearLogBtn = softRowButton("پاک کردن لاگ هسته")
        clearLogBtn.setOnClickListener {
            VexVpnService.clearCoreLogs()
            UI.toast(act, "لاگ هسته پاک شد")
        }
        toolsCard.addView(clearLogBtn)
        toolsCard.addView(divider())
        val resetTrafficBtn = softRowButton("پاک کردن آمار مصرف کل")
        resetTrafficBtn.setOnClickListener {
            TrafficStore.resetAll()
            act.statsUi.refreshTotals()
            UI.toast(act, "آمار مصرف کل پاک شد")
        }
        toolsCard.addView(resetTrafficBtn)
        toolsCard.addView(divider())
        val resetBtn = softRowButton("بازنشانی همه‌ی تنظیمات", tag = "bad")
        resetBtn.setOnClickListener { act.resetSettings() }
        toolsCard.addView(resetBtn)
        addCard(toolsCard)

        // ================= درباره =================
        val aboutCard = sectionCard("درباره", "globe")
        aboutCard.addView(infoRow("نسخه‌ی برنامه", act.versionLabel()))
        aboutCard.addView(divider())
        aboutCard.addView(infoRow("هسته‌ی پراکسی", "sing-box v1.11.15"))
        aboutCard.addView(divider())
        aboutCard.addView(infoRow("آمار ترافیک", "Clash API"))
        aboutCard.addView(divider())
        val tgRow = infoRow("کانال تلگرام VEX", "@vex_vpnfree")
        tgRow.setOnClickListener { act.toast("از دکمه‌ی تلگرام بالای صفحه استفاده کن") }
        aboutCard.addView(tgRow)
        addCard(aboutCard)

        val note = subLabel("تغییرات تونل برای اتصال بعدی اعمال می‌شود.")
        note.gravity = Gravity.CENTER
        note.setPadding(0, UI.dp(ctx, 12), 0, UI.dp(ctx, 4))
        root.addView(note)

        scroll.addView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.isFillViewport = true

        building = false
        renderPerAppMode()
        applyTheme(p)
        return scroll
    }

    // ---------------------------------------------------------- per-app

    /** چیدمان چیپ‌های حالت per-app + متن تعداد انتخاب‌شده‌ها */
    private fun renderPerAppMode() {
        val mode = act.store.settings.perAppMode
        for (i in 0 until perAppChips.childCount) {
            val chip = perAppChips.getChildAt(i) as TextView
            val active = chip.tag == "mode$mode"
            chip.background = UI.card(act, p, 20,
                fillColor = if (active) UI.alpha(p.accent, 0.2f) else p.card2,
                stroke = if (active) p.accent else p.line)
            chip.setTextColor(if (active) p.accent else p.text)
        }
        perAppCount.text = when {
            mode == 0 -> "تونل برای همه‌ی برنامه‌ها فعال است."
            act.store.settings.perAppPackages.isEmpty() ->
                if (mode == 1) "هیچ برنامه‌ای انتخاب نشده — برای اتصال اول انتخاب کن!"
                else "هنوز برنامه‌ای برای استثنا انتخاب نشده."
            else -> "تعداد ${act.store.settings.perAppPackages.size} برنامه انتخاب شده است."
        }
    }

    private fun perAppChip(text: String, mode: Int): View {
        val ctx = act
        val chip = UI.pill(ctx, text, p.card2, p.text)
        chip.setPadding(UI.dp(ctx, 12), UI.dp(ctx, 8), UI.dp(ctx, 12), UI.dp(ctx, 8))
        chip.textSize = 12f
        chip.typeface = Fonts.bold(ctx)
        chip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 7)
        }
        chip.setOnClickListener {
            act.store.settings.perAppMode = mode
            act.store.saveSettings()
            renderPerAppMode()
        }
        chip.tag = "mode$mode"
        return chip
    }

    // ---------------------------------------------------------- سازنده‌ها

    /** کارت بخش: آیکون + تیتر رنگی + خط جداکننده زیر تیتر */
    private fun sectionCard(title: String, icon: String): LinearLayout {
        val ctx = act
        val card = UI.col(ctx)
        card.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 12), UI.dp(ctx, 16), UI.dp(ctx, 14))
        val head = UI.row(ctx)
        val ic = IconView(ctx, icon).apply { iconColor = p.accent; bold = true }
        head.addView(ic, LinearLayout.LayoutParams(UI.dp(ctx, 19), UI.dp(ctx, 19)))
        val t = UI.label(ctx, title, 15.5f, p.accent, bold = true).apply { tag = "accent" }
        t.setPadding(UI.dp(ctx, 8), 0, 0, 0)
        head.addView(t)
        head.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        card.addView(head)
        card.addView(divider(10))
        return card
    }

    private fun addCard(view: View) {
        val ctx = act
        val wrap = LinearLayout(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }
        view.background = UI.card(ctx, p)
        wrap.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(wrap)
    }

    private fun divider(topPad: Int = 0): View {
        val ctx = act
        val d = View(ctx)
        d.setBackgroundColor(UI.alpha(p.line, 0.65f))
        d.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 1)).apply {
            setMargins(0, UI.dp(ctx, topPad), 0, UI.dp(ctx, 3))
        }
        return d
    }

    private fun fieldLabel(ctx: Context, text: String): TextView =
        UI.label(ctx, text, 12.5f, p.muted).apply {
            tag = "sub"
            setPadding(0, UI.dp(ctx, 10), 0, UI.dp(ctx, 2))
        }

    private fun subLabel(text: String): TextView =
        UI.label(act, text, 12f, p.muted).apply {
            tag = "sub"
            setPadding(0, UI.dp(act, 5), 0, 0)
            setLineSpacing(UI.dp(act, 1.5f).toFloat(), 1f)
        }

    /**
     * ردیف تنظیم: عنوان + توضیح + سوییچ در انتهای ردیف.
     * کل ردیف کلیک‌پذیر است (لمس هر جای ردیف = زدن سوییچ).
     */
    private fun switchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val ctx = act
        val row = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 12), 0, UI.dp(ctx, 5)) }
        val textCol = UI.col(ctx)
        val tv = UI.label(ctx, title, 14.5f, p.text).apply {
            setLineSpacing(UI.dp(ctx, 1.5f).toFloat(), 1f)
        }
        val stv = subLabel(subtitle)
        textCol.addView(tv)
        textCol.addView(stv)
        textCol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UI.dp(ctx, 6)
        }
        val sw = VexSwitch(ctx).apply {
            isChecked = value
            onCheckedChange = { now -> onChange(now) }
        }
        // کل ردیف = دکمه (رفع مشکل کلیک نشدن)
        row.isClickable = true
        row.setOnClickListener { sw.performClick() }
        allSwitches.add(sw)
        row.addView(textCol)
        row.addView(sw, LinearLayout.LayoutParams(
            UI.dp(ctx, 52), UI.dp(ctx, 29)).apply { marginStart = UI.dp(ctx, 10) })
        return row
    }

    /** ردیف دکمه‌ای داخل بخش‌ها */
    private fun softRowButton(text: String, tag: String = ""): TextView {
        val ctx = act
        val t = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 12), 0, UI.dp(ctx, 12))
            this.tag = tag
        }
        themedButtons.add(t)
        return t
    }

    /** ردیف اطلاعاتی «درباره»: عنوان + مقدار */
    private fun infoRow(title: String, value: String): View {
        val ctx = act
        val row = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, UI.dp(ctx, 2)) }
        val tv = UI.label(ctx, title, 14f, p.text)
        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val vv = UI.label(ctx, value, 13f, p.muted)
        vv.tag = "sub"
        row.addView(tv)
        row.addView(vv)
        return row
    }

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
        // خطوط جداکننده
        recolorDividers(root, p)
        // آیکون‌های تیتر بخش‌ها
        recolorSectionIcons(root, p)
        renderPerAppMode()
        for (b in themedButtons) {
            b.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
            b.setTextColor(if (b.tag == "bad") p.bad else p.text)
        }
        // سوییچ‌های لوکس — هم‌رنگ تم فعلی
        for (sw in allSwitches) sw.palette = p
    }

    private fun recolor(v: View, p: Palette) {
        if (v is TextView) {
            if (v.background !is android.graphics.drawable.GradientDrawable)
                v.setTextColor(when (v.tag) {
                    "sub" -> p.muted
                    "accent" -> p.accent
                    "bad" -> p.bad
                    else -> p.text
                })
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) recolor(v.getChildAt(i), p)
        }
    }

    private fun recolorSectionIcons(v: View, p: Palette) {
        if (v is IconView) v.iconColor = p.accent
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) recolorSectionIcons(v.getChildAt(i), p)
        }
    }

    private fun recolorDividers(v: View, p: Palette) {
        if (v is View && v.layoutParams is LinearLayout.LayoutParams &&
            v.layoutParams.height == UI.dp(act, 1) && v !is TextView) {
            v.setBackgroundColor(UI.alpha(p.line, 0.65f))
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) recolorDividers(v.getChildAt(i), p)
        }
    }

    fun refresh() {
        applyTheme(Themes.resolve(act, act.store.settings.theme))
        renderPerAppMode()
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
