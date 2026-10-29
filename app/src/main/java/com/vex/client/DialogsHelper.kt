package com.vex.client

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast

/**
 * همه‌ی دیالوگ‌های برنامه (نیتیو، بدون HTML).
 */
class DialogsHelper(private val act: MainActivity) {

    private val ctx: Context get() = act
    private val p: Palette get() = Themes.resolve(act, act.store.settings.theme)

    private var addMenuDlg: AlertDialog? = null

    /** بستن خودکار منوی افزودن بعد از ذخیره‌ی موفق کانفیگ */
    fun dismissAddMenu() {
        try { addMenuDlg?.dismiss() } catch (_: Throwable) {}
        addMenuDlg = null
    }

    // ------------------------------------------------------------ پایه

    private fun wrapForm(content: LinearLayout, scrollable: Boolean = true): View {
        content.setPadding(UI.dp(ctx, 20), UI.dp(ctx, 12), UI.dp(ctx, 20), 0)
        content.layoutDirection = View.LAYOUT_DIRECTION_RTL
        return if (scrollable) ScrollView(ctx).also { sv ->
            sv.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else content
    }

    private fun field(label: String, hint: String = "", ltr: Boolean = true, number: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = if (hint.isEmpty()) label else hint
        e.textSize = 14f
        e.setSingleLine(true)
        if (ltr) e.textDirection = View.TEXT_DIRECTION_LTR
        if (number) e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 10))
        e.background = UI.card(ctx, p, 12, stroke = p.line, fillColor = p.card2)
        e.setTextColor(p.text)
        e.setHintTextColor(p.muted)
        return e
    }

    private fun formLabel(text: String): TextView =
        UI.label(ctx, text, 12f, p.muted).apply { setPadding(0, UI.dp(ctx, 10), 0, UI.dp(ctx, 2)) }

    private fun dialog(title: String, view: View, okText: String?, onOk: (() -> Unit)?, cancelText: String? = "انصراف"): AlertDialog {
        val titleView = UI.label(ctx, title, 17f, p.text, bold = true)
        titleView.setPadding(UI.dp(ctx, 22), UI.dp(ctx, 18), UI.dp(ctx, 22), UI.dp(ctx, 4))
        val b = AlertDialog.Builder(ctx)
            .setCustomTitle(titleView)
            .setView(view)
        if (onOk != null && okText != null) b.setPositiveButton(okText) { d, _ -> onOk(); d.dismiss() }
        if (cancelText != null) b.setNegativeButton(cancelText) { d, _ -> d.dismiss() }
        val dlg = b.create()
        dlg.show()
        try {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.accent)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.muted)
            dlg.window?.setBackgroundDrawable(UI.card(ctx, p, 20, stroke = p.line, fillColor = p.bg2))
        } catch (_: Throwable) {}
        return dlg
    }

    private fun menuRow(text: String, color: Int = 0, onClick: () -> Unit): TextView {
        val t = UI.label(ctx, text, 15f, if (color != 0) color else p.text, bold = true)
        t.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 14), UI.dp(ctx, 18), UI.dp(ctx, 14))
        t.background = UI.card(ctx, p, 12, fillColor = p.card)
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = UI.dp(ctx, 8)
        t.layoutParams = lp
        return t
    }

    // ------------------------------------------------------------ انتخاب برنامه‌ها (per-app)

    /**
     * دیالوگ انتخاب برنامه‌ها برای تونل (مثل v2rayNG):
     * لیست برنامه‌های نصب‌شده با چک‌باکس + جستجو؛ انتخاب‌ها در تنظیمات ذخیره می‌شوند.
     */
    fun showPerAppPicker() {
        val pm = act.packageManager
        val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        // جفت‌های (نام نمایشی، پکیج) از برنامه‌های نصب‌شده‌ی قابل اجرا
        val apps: List<Pair<String, String>> = try {
            pm.queryIntentActivities(launchIntent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == act.packageName) return@mapNotNull null
                    val label = try { ri.loadLabel(pm)?.toString() ?: pkg } catch (_: Throwable) { pkg }
                    label to pkg
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        } catch (_: Throwable) { emptyList() }

        if (apps.isEmpty()) {
            UI.toast(act, "برنامه‌ای پیدا نشد")
            return
        }

        val selected = act.store.settings.perAppPackages.toMutableSet()
        val col = UI.col(ctx)
        col.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 6), UI.dp(ctx, 16), UI.dp(ctx, 12))

        // جستجو
        val search = EditText(ctx).apply {
            hint = "جستجوی نام برنامه…"
            textSize = 14f
            setSingleLine(true)
            setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 10))
            background = UI.card(ctx, p, 12, stroke = p.line, fillColor = p.card2)
            setTextColor(p.text)
            setHintTextColor(p.muted)
        }
        col.addView(search)

        // ردیف شمارنده + انتخاب همه / هیچ
        val countTv = UI.label(ctx, "", 12.5f, p.accent, bold = true)
        fun updateCount() { countTv.text = "انتخاب‌شده: ${selected.size}" }
        fun toggle(pkg: String, v: Boolean) {
            if (v) selected.add(pkg) else selected.remove(pkg)
            updateCount()
        }

        // لیست اسکرول‌شونده‌ی برنامه‌ها
        val listHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        scroll.addView(listHost)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 300)).apply {
            topMargin = UI.dp(ctx, 8)
        }

        fun rebuildList(filter: String) {
            listHost.removeAllViews()
            val f = filter.trim().lowercase()
            val shown = apps.filter { f.isEmpty() || it.first.lowercase().contains(f) || it.second.lowercase().contains(f) }
            if (shown.isEmpty()) {
                val empty = UI.label(ctx, "چیزی پیدا نشد", 13f, p.muted)
                empty.gravity = Gravity.CENTER
                empty.setPadding(0, UI.dp(ctx, 20), 0, UI.dp(ctx, 20))
                listHost.addView(empty)
                return
            }
            for ((idx, app) in shown.withIndex()) {
                val row = UI.row(ctx).apply {
                    setPadding(UI.dp(ctx, 4), UI.dp(ctx, 9), UI.dp(ctx, 4), UI.dp(ctx, 9))
                }
                val cb = android.widget.CheckBox(ctx).apply {
                    isChecked = selected.contains(app.second)
                    setOnClickListener { toggle(app.second, isChecked) }
                }
                val textCol = UI.col(ctx)
                textCol.addView(UI.label(ctx, app.first, 14f, p.text))
                textCol.addView(UI.label(ctx, app.second, 10.5f, p.muted).apply {
                    textDirection = View.TEXT_DIRECTION_LTR
                    gravity = Gravity.RIGHT
                    setPadding(0, UI.dp(ctx, 1), 0, 0)
                })
                textCol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textCol.setOnClickListener { cb.isChecked = !cb.isChecked; toggle(app.second, cb.isChecked) }
                row.addView(cb)
                row.addView(textCol)
                listHost.addView(row)
                // جداکننده
                if (idx < shown.size - 1) {
                    val d = View(ctx)
                    d.setBackgroundColor(UI.alpha(p.line, 0.5f))
                    d.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 1))
                    listHost.addView(d)
                }
            }
            updateCount()
        }

        val actions = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 8), 0, 0) }
        val selAll = UI.pill(ctx, "انتخاب همه", p.card2, p.accent)
        selAll.setPadding(UI.dp(ctx, 12), UI.dp(ctx, 7), UI.dp(ctx, 12), UI.dp(ctx, 7))
        selAll.setOnClickListener { apps.forEach { selected.add(it.second) }; rebuildList(search.text?.toString() ?: "") }
        val selNone = UI.pill(ctx, "حذف همه", p.card2, p.muted)
        selNone.setPadding(UI.dp(ctx, 12), UI.dp(ctx, 7), UI.dp(ctx, 12), UI.dp(ctx, 7))
        selNone.setOnClickListener { selected.clear(); rebuildList(search.text?.toString() ?: "") }
        actions.addView(countTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(selAll)
        actions.addView(selNone, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 6)
        })
        col.addView(actions)
        col.addView(scroll)

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuildList(s?.toString() ?: "") }
        })
        rebuildList("")

        dialog("انتخاب برنامه‌ها", wrapForm(col, scrollable = false), "ذخیره", {
            act.store.settings.perAppPackages = selected
            act.store.saveSettings()
            UI.toast(act, "${selected.size} برنامه ذخیره شد")
        })
    }

    // ------------------------------------------------------------ منوی افزودن

    fun showAddMenu() {
        dismissAddMenu()
        val col = UI.col(ctx)
        col.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 8), UI.dp(ctx, 16), UI.dp(ctx, 16))
        // پرسش راهنما — «چطور کانفیگ را وارد می‌کنی؟»
        val q = UI.label(ctx, "چطور می‌خوای کانفیگ (سرور) را وارد کنی؟", 13.5f, p.muted)
        q.setPadding(UI.dp(ctx, 4), UI.dp(ctx, 2), UI.dp(ctx, 4), UI.dp(ctx, 10))
        col.addView(q)
        col.addView(menuRow("پیست از کلیپ‌بورد") { act.pasteFromClipboard() })
        col.addView(menuRow("اسکن QR (دوربین)") { act.startQrScan() })
        col.addView(menuRow("QR از گالری") { act.startQrGallery() })
        col.addView(menuRow("فایل کانفیگ (txt/conf/json)") { act.pickFile() })
        col.addView(menuRow("افزودن دستی (vless/vmess/trojan/ss/hy2/tuic)") { showManualAdd() })
        col.addView(menuRow("وایرگارد (فایل conf / دستی)") { showWireGuardAdd() })
        col.addView(menuRow("دریافت اشتراک (subscription)") { showSubscription() })
        addMenuDlg = dialog("افزودن سرور", wrapForm(col, scrollable = false), null, null, "بستن")
    }

    // ------------------------------------------------------------ افزودن دستی

    private fun showManualAdd() {
        val col = UI.col(ctx)

        col.addView(formLabel("نوع پروتکل"))
        val protoSpin = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("vless", "vmess", "trojan", "ss", "hysteria2", "tuic"))
        }
        col.addView(protoSpin)

        val name = field("نام (اختیاری)", "نام نمایشی")
        val addr = field("آدرس سرور", "مثلاً example.com یا 1.2.3.4")
        val port = field("پورت", "443", number = true)
        val secret = field("UUID / رمز", "uuid برای vless/vmess، رمز برای بقیه")
        val method = field("روش SS (فقط ss)", "مثل aes-256-gcm")

        col.addView(formLabel("نام نمایشی")); col.addView(name)
        col.addView(formLabel("آدرس سرور *")); col.addView(addr)
        col.addView(formLabel("پورت *")); col.addView(port)
        col.addView(formLabel("UUID / رمز *")); col.addView(secret)
        col.addView(formLabel("روش رمزنگاری (فقط SS)")); col.addView(method)

        col.addView(formLabel("شبکه (transport)"))
        val netSpin = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("tcp", "ws", "grpc", "httpupgrade", "http", "quic"))
        }
        col.addView(netSpin)

        col.addView(formLabel("امنیت"))
        val secSpin = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("none", "tls", "reality"))
        }
        col.addView(secSpin)

        val sni = field("SNI / Host TLS", "دامنه‌ی SNI")
        val host = field("Host (برای ws/http)", "هدر Host")
        val path = field("Path (برای ws/http)", "/")
        val svc = field("serviceName (برای grpc)", "نام سرویس gRPC")
        val fp = field("fingerprint", "chrome / firefox / ...")
        val pbk = field("Reality publicKey (pbk)", "کلید عمومی Reality")
        val sid = field("Reality shortId (sid)", "شناسه‌ی کوتاه Reality")
        val alpn = field("alpn", "مثلاً h2,http/1.1")
        for (f in arrayOf(sni, host, path, svc, fp, pbk, sid, alpn)) {
            col.addView(formLabel(f.hint?.toString() ?: "")); col.addView(f)
        }

        dialog("افزودن دستی", wrapForm(col), "ذخیره", {
            try {
                val proto = (protoSpin.selectedItem as String)
                val rawObj = org.json.JSONObject()
                    .put("protocol", proto)
                    .put("address", addr.text.toString().trim())
                    .put("port", port.text.toString().trim())
                    .put("secret", secret.text.toString().trim())
                    .put("network", netSpin.selectedItem as String)
                    .put("security", secSpin.selectedItem as String)
                if (name.text.isNotBlank()) rawObj.put("name", name.text.toString().trim())
                if (proto == "ss" && method.text.isNotBlank()) rawObj.put("method", method.text.toString().trim())
                if (sni.text.isNotBlank()) rawObj.put("sni", sni.text.toString().trim())
                if (host.text.isNotBlank()) rawObj.put("host", host.text.toString().trim())
                if (path.text.isNotBlank()) rawObj.put("path", path.text.toString().trim())
                if (svc.text.isNotBlank()) rawObj.put("serviceName", svc.text.toString().trim())
                if (fp.text.isNotBlank()) rawObj.put("fp", fp.text.toString().trim())
                if (pbk.text.isNotBlank()) rawObj.put("pbk", pbk.text.toString().trim())
                if (sid.text.isNotBlank()) rawObj.put("sid", sid.text.toString().trim())
                if (alpn.text.isNotBlank()) rawObj.put("alpn", alpn.text.toString().trim())
                if ((secSpin.selectedItem as String) != "none") rawObj.put("allowInsecure", false)
                val inf = LinkParser.info(rawObj.toString())
                val sv = act.store.add(rawObj.toString(),
                    name.text.toString().trim().ifEmpty { inf.name ?: proto.uppercase() }, inf.protocol)
                if (sv != null) {
                    if (act.store.selectedId == -1) act.store.select(sv.id)
                    act.renderAll(); act.switchTab(1)
                    act.toast("سرور ذخیره شد")
                    act.pingServers(listOf(sv.id), silent = true)
                    dismissAddMenu()
                }
            } catch (e: Throwable) {
                act.toast("خطا: ${e.message ?: "ورودی نامعتبر"}")
            }
        })
    }

    // ------------------------------------------------------------ وایرگارد

    private fun showWireGuardAdd() {
        val col = UI.col(ctx)
        val name = field("نام", "نام نمایشی", ltr = false)
        val priv = field("PrivateKey", "کلید خصوصی")
        val pub = field("PublicKey (Peer)", "کلید عمومی همتا")
        val psk = field("PresharedKey (اختیاری)")
        val server = field("Endpoint آدرس", "مثلاً engage.cloudflareclient.com")
        val port = field("Endpoint پورت", "51820", number = true)
        val addresses = field("Address ها", "10.2.0.2/32", ltr = true)
        val mtu = field("MTU (اختیاری)", "1408", number = true)
        val reserved = field("Reserved (اختیاری)", "1,2,3")

        col.addView(formLabel("نام نمایشی")); col.addView(name)
        col.addView(formLabel("PrivateKey *")); col.addView(priv)
        col.addView(formLabel("PublicKey *")); col.addView(pub)
        col.addView(formLabel("PresharedKey")); col.addView(psk)
        col.addView(formLabel("Endpoint *")); col.addView(server)
        col.addView(formLabel("پورت Endpoint *")); col.addView(port)
        col.addView(formLabel("Address ها * (با کاما)")); col.addView(addresses)
        col.addView(formLabel("MTU")); col.addView(mtu)
        col.addView(formLabel("Reserved (برای WARP)")); col.addView(reserved)

        val pasteBtn = UI.softButton(ctx, "خواندن از متن conf (پیست فایل وایرگارد)", p)
        pasteBtn.setOnClickListener {
            val t = try {
                val cm = act.getSystemService(android.content.ClipboardManager::class.java)
                cm?.primaryClip?.let { i -> if (i.itemCount > 0) i.getItemAt(0).coerceToText(act)?.toString() else null }
            } catch (_: Throwable) { null }
            if (t.isNullOrBlank()) { act.toast("کلیپ‌بورد خالی است"); return@setOnClickListener }
            val manual = LinkParser.wgConfToManual(t)
            if (manual == null) { act.toast("این متن conf وایرگارد معتبری نیست"); return@setOnClickListener }
            try {
                val j = org.json.JSONObject(manual)
                priv.setText(j.optString("privateKey"))
                pub.setText(j.optString("publicKey"))
                if (j.optString("presharedKey").isNotEmpty()) psk.setText(j.optString("presharedKey"))
                server.setText(j.optString("server"))
                port.setText(j.optString("port"))
                addresses.setText(j.optString("addresses_csv"))
                if (j.has("mtu")) mtu.setText(j.optString("mtu"))
                if (j.optString("reserved").isNotEmpty()) reserved.setText(j.optString("reserved"))
                if (name.text.isBlank() && j.optString("wgName").isNotEmpty()) name.setText(j.optString("wgName"))
                act.toast("فیلدها از conf خوانده شد")
            } catch (_: Throwable) { act.toast("خواندن conf ناموفق بود") }
        }
        col.addView(pasteBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UI.dp(ctx, 14)
        })

        dialog("افزودن وایرگارد", wrapForm(col), "ذخیره", {
            try {
                val rawObj = org.json.JSONObject()
                    .put("protocol", "wireguard")
                    .put("privateKey", priv.text.toString().trim())
                    .put("publicKey", pub.text.toString().trim())
                    .put("server", server.text.toString().trim())
                    .put("port", port.text.toString().trim())
                    .put("addresses_csv", addresses.text.toString().trim())
                if (psk.text.isNotBlank()) rawObj.put("presharedKey", psk.text.toString().trim())
                if (mtu.text.isNotBlank()) rawObj.put("mtu", mtu.text.toString().trim())
                if (reserved.text.isNotBlank()) rawObj.put("reserved", reserved.text.toString().trim())
                val dispName = name.text.toString().trim()
                if (dispName.isNotEmpty()) rawObj.put("wgName", dispName)
                val inf = LinkParser.info(rawObj.toString())
                val sv = act.store.add(rawObj.toString(),
                    dispName.ifEmpty { inf.name ?: "WireGuard" }, "wireguard")
                if (sv != null) {
                    if (act.store.selectedId == -1) act.store.select(sv.id)
                    act.renderAll(); act.switchTab(1)
                    act.toast("سرور وایرگارد ذخیره شد")
                    act.pingServers(listOf(sv.id), silent = true)
                    dismissAddMenu()
                }
            } catch (e: Throwable) {
                act.toast("خطا: ${e.message ?: "ورودی نامعتبر"}")
            }
        })
    }

    // ------------------------------------------------------------ اشتراک

    fun showSubscription() {
        val col = UI.col(ctx)
        val url = field("آدرس اشتراک", "https://example.com/sub", ltr = true)
        col.addView(formLabel("لینک اشتراک (http/https)"))
        col.addView(url)
        dialog("دریافت اشتراک", wrapForm(col, scrollable = false), "دریافت", {
            val u = url.text.toString().trim()
            if (u.isEmpty()) { act.toast("آدرس را وارد کن"); return@dialog }
            act.toast("در حال دریافت…")
            Thread {
                try {
                    val body = NetUtil.fetchSubscription(u)
                    act.mainHandler().post { act.addFromText(body) }
                } catch (e: Throwable) {
                    act.mainHandler().post { act.toast("دریافت اشتراک ناموفق بود: ${e.message ?: ""}") }
                }
            }.start()
        })
    }

    // ------------------------------------------------------------ ویرایش سرور

    /**
     * ویرایش کامل کانفیگ: نام + همه‌ی جزئیات (آدرس، پورت، رمز، TLS، SNI،
     * Host، Path، fingerprint، Reality و…) با دکمه‌ی ذخیره.
     * کانفیگ پس از ویرایش دوباره ساخته و ذخیره می‌شود؛ پینگ هم بلافاصله گرفته می‌شود.
     * وایرگارد / JSON ناشناخته → حالت متن کامل.
     */
    fun showEditServer(s: Server) {
        val fields = ConfigEditor.parse(s.raw)
        if (fields == null) { showEditServerRaw(s); return }

        val col = UI.col(ctx)

        // ردیف اطلاعات: نشان پروتکل + آدرس فعلی
        val infoRow = UI.row(ctx)
        val badge = UI.pill(ctx, UI.protoLabel(fields.proto), UI.alpha(p.accent, 0.18f), p.accent)
        infoRow.addView(badge)
        val addrInfo = UI.label(ctx, s.raw.substringBefore("#").take(60), 11f, p.muted)
        addrInfo.textDirection = View.TEXT_DIRECTION_LTR
        addrInfo.setPadding(UI.dp(ctx, 10), 0, 0, 0)
        infoRow.addView(addrInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(infoRow)

        val name = field("نام نمایشی", "نام نمایشی", ltr = false)
        val addr = field("آدرس سرور", "مثلاً example.com یا 1.2.3.4")
        val port = field("پورت", "443", number = true)
        val secret = field("UUID / رمز", "uuid برای vless/vmess، رمز برای بقیه")
        val method = field("روش رمزنگاری", "مثل aes-256-gcm")
        val network = field("شبکه (transport)", "tcp / ws / grpc / httpupgrade")
        val security = field("امنیت", "none / tls / reality")
        val sni = field("SNI", "دامنه‌ی SNI")
        val host = field("Host", "هدر Host برای ws/http")
        val path = field("Path", "برای ws/http مثل /path")
        val svc = field("serviceName", "نام سرویس gRPC")
        val fp = field("fingerprint", "chrome / firefox / ...")
        val pbk = field("Reality publicKey", "کلید عمومی Reality")
        val sid = field("Reality shortId", "شناسه‌ی کوتاه Reality")
        val alpn = field("alpn", "مثل h2,http/1.1")

        // پیش‌پر کردن از مقادیر فعلی کانفیگ
        name.setText(fields.name.ifEmpty { s.name })
        addr.setText(fields.address)
        port.setText(fields.port)
        secret.setText(fields.secret)
        method.setText(fields.method)
        network.setText(fields.network)
        security.setText(fields.security)
        sni.setText(fields.sni)
        host.setText(fields.host)
        path.setText(fields.path)
        svc.setText(fields.serviceName)
        fp.setText(fields.fp)
        pbk.setText(fields.pbk)
        sid.setText(fields.sid)
        alpn.setText(fields.alpn)

        fun addLabeled(label: String, e: EditText) {
            col.addView(formLabel(label)); col.addView(e)
        }

        addLabeled("نام نمایشی", name)
        addLabeled("آدرس سرور *", addr)
        addLabeled("پورت *", port)
        addLabeled("UUID / رمز *", secret)
        if (fields.showMethod) addLabeled("روش رمزنگاری (SS)", method)
        if (fields.showTlsFields) {
            addLabeled("شبکه (tcp / ws / grpc / httpupgrade)", network)
            addLabeled("امنیت (none / tls / reality)", security)
            addLabeled("SNI", sni)
            addLabeled("Host", host)
            addLabeled("Path", path)
            addLabeled("serviceName (gRPC)", svc)
            addLabeled("fingerprint", fp)
            addLabeled("Reality publicKey (pbk)", pbk)
            addLabeled("Reality shortId (sid)", sid)
            addLabeled("alpn", alpn)
        } else if (fields.proto == "tuic" || fields.proto == "hysteria2") {
            addLabeled("SNI", sni)
            if (fields.proto == "tuic") addLabeled("alpn", alpn)
        }

        dialog("ویرایش کانفیگ", wrapForm(col), "ذخیره", {
            try {
                // مقادیر ویرایش‌شده را روی فیلدها بنشین و کانفیگ را دوباره بساز
                fields.name = name.text.toString().trim()
                fields.address = addr.text.toString().trim()
                fields.port = port.text.toString().trim()
                fields.secret = secret.text.toString().trim()
                if (fields.showMethod) fields.method = method.text.toString().trim()
                if (fields.showTlsFields) {
                    fields.network = network.text.toString().trim()
                    fields.security = security.text.toString().trim()
                    fields.sni = sni.text.toString().trim()
                    fields.host = host.text.toString().trim()
                    fields.path = path.text.toString().trim()
                    fields.serviceName = svc.text.toString().trim()
                    fields.fp = fp.text.toString().trim()
                    fields.pbk = pbk.text.toString().trim()
                    fields.sid = sid.text.toString().trim()
                    fields.alpn = alpn.text.toString().trim()
                } else if (fields.proto == "tuic" || fields.proto == "hysteria2") {
                    fields.sni = sni.text.toString().trim()
                    if (fields.proto == "tuic") fields.alpn = alpn.text.toString().trim()
                }
                val newRaw = ConfigEditor.rebuild(fields)
                    ?: throw IllegalArgumentException("بازسازی کانفیگ ناموفق بود")
                val ok = act.store.update(s.id, fields.name, newRaw)
                if (ok) {
                    act.renderAll()
                    act.toast("کانفیگ ذخیره شد")
                    act.pingServers(listOf(s.id), silent = true)
                } else {
                    act.toast("ذخیره ناموفق بود (کانفیگ نامعتبر یا تکراری)")
                }
            } catch (e: Throwable) {
                act.toast("خطا: ${e.message ?: "ورودی نامعتبر"}")
            }
        })
    }

    /** حالت متن کامل — برای وایرگارد و JSONهای پیچیده */
    private fun showEditServerRaw(s: Server) {
        val col = UI.col(ctx)
        val name = EditText(ctx).apply {
            setText(s.name)
            textSize = 14f
            setSingleLine(true)
            setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 10))
            background = UI.card(ctx, p, 12, stroke = p.line, fillColor = p.card2)
            setTextColor(p.text)
            setHintTextColor(p.muted)
        }
        val raw = EditText(ctx).apply {
            setText(s.raw)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 8
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 10))
            background = UI.card(ctx, p, 12, stroke = p.line, fillColor = p.card2)
            setTextColor(p.text)
            setHintTextColor(p.muted)
        }
        col.addView(formLabel("نام نمایشی")); col.addView(name)
        col.addView(formLabel("متن کامل کانفیگ")); col.addView(raw)
        dialog("ویرایش سرور", wrapForm(col), "ذخیره", {
            val n = name.text.toString().trim()
            val r = raw.text.toString().trim()
            if (r.isEmpty()) { act.toast("متن کانفیگ خالی است"); return@dialog }
            val ok = act.store.update(s.id, n, r)
            if (ok) {
                act.renderAll()
                act.toast("سرور ویرایش شد")
                act.pingServers(listOf(s.id), silent = true)
            } else {
                act.toast("کانفیگ معتبر نیست یا تکراری است")
            }
        })
    }

    // ------------------------------------------------------------ منوی سرور

    fun showServerMenu(s: Server) {
        val col = UI.col(ctx)
        col.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 8), UI.dp(ctx, 16), UI.dp(ctx, 16))
        val title = UI.label(ctx, s.name, 15f, p.accent, bold = true)
        title.setPadding(UI.dp(ctx, 4), UI.dp(ctx, 4), UI.dp(ctx, 4), UI.dp(ctx, 8))
        col.addView(title)
        col.addView(menuRow("فعال‌سازی این سرور") { act.selectServer(s) })
        col.addView(menuRow("تست پینگ") { act.pingServers(listOf(s.id)) })
        col.addView(menuRow("ویرایش اطلاعات") { showEditServer(s) })
        col.addView(menuRow("تغییر نام") { showRename(s) })
        col.addView(menuRow("اشتراک‌گذاری") {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, s.raw)
                }
                act.startActivity(android.content.Intent.createChooser(i, "اشتراک‌گذاری کانفیگ"))
            } catch (_: Throwable) { act.toast("اشتراک‌گذاری ممکن نشد") }
        })
        col.addView(menuRow("مشاهده‌ی متن کانفیگ") { showRaw(s) })
        col.addView(menuRow("حذف سرور", p.bad) {
            act.confirmDelete(setOf(s.id)) { act.renderAll() }
        })
        dialog(s.name, wrapForm(col, scrollable = false), null, null, "بستن")
    }

    private fun showRename(s: Server) {
        val col = UI.col(ctx)
        val e = field("نام جدید", s.name, ltr = false)
        e.setText(s.name)
        col.addView(formLabel("نام جدید"))
        col.addView(e)
        dialog("تغییر نام", wrapForm(col, scrollable = false), "ذخیره", {
            val n = e.text.toString().trim()
            if (n.isNotEmpty()) {
                act.store.updateName(s.id, n)
                act.renderAll()
                act.toast("نام تغییر کرد")
            }
        })
    }

    private fun showRaw(s: Server) {
        val col = UI.col(ctx)
        val tv = TextView(ctx).apply {
            text = s.raw
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(p.text)
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(UI.dp(ctx, 4), UI.dp(ctx, 4), UI.dp(ctx, 4), UI.dp(ctx, 4))
        }
        col.addView(tv)
        dialog("متن کانفیگ", wrapForm(col), "کپی", {
            try {
                val cm = act.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("vex", s.raw))
                act.toast("کپی شد")
            } catch (_: Throwable) {}
        })
    }

    // ------------------------------------------------------------ حذف

    fun confirmDelete(ids: Set<Int>, after: () -> Unit) {
        val msg = UI.label(ctx, "${ids.size} سرور حذف شود؟ این کار قابل بازگشت نیست.", 14f, p.text)
        msg.setPadding(UI.dp(ctx, 22), UI.dp(ctx, 12), UI.dp(ctx, 22), 0)
        val b = AlertDialog.Builder(ctx)
            .setCustomTitle(UI.label(ctx, "حذف سرورها", 17f, p.text, bold = true))
            .setView(msg)
            .setPositiveButton("حذف") { d, _ ->
                act.store.remove(ids)
                act.toast("حذف شد")
                d.dismiss()
                after()
            }
            .setNegativeButton("انصراف") { d, _ -> d.dismiss() }
        val dlg = b.create()
        dlg.show()
        try {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.bad)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.muted)
            dlg.window?.setBackgroundDrawable(UI.card(ctx, p, 20, stroke = p.line, fillColor = p.bg2))
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ لاگ / خطا

    fun showCoreLog() {
        val log = VexVpnService.coreLogAll()
        showTextDialog("لاگ هسته (sing-box)", log.ifBlank { "لاگی ثبت نشده" })
    }

    fun showError(msg: String) {
        val log = VexVpnService.coreLogTail(10)
        val body = if (log.isBlank()) msg else "$msg\n\n--- آخرین لاگ هسته ---\n$log"
        showTextDialog("جزئیات خطا", body)
    }

    private fun showTextDialog(title: String, text: String) {
        val col = UI.col(ctx)
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(p.text)
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6))
        }
        val sv = ScrollView(ctx)
        sv.addView(tv)
        col.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 320)))
        col.setPadding(UI.dp(ctx, 8), 0, UI.dp(ctx, 8), 0)
        val view = wrapForm(col, scrollable = false)
        dialog(title, view, "کپی", {
            try {
                val cm = act.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("vex-log", text))
                Toast.makeText(ctx, "کپی شد", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }, "بستن")
    }
}
