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

    // ------------------------------------------------------------ منوی افزودن

    fun showAddMenu() {
        val col = UI.col(ctx)
        col.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 8), UI.dp(ctx, 16), UI.dp(ctx, 16))
        col.addView(menuRow("پیست از کلیپ‌بورد") { act.pasteFromClipboard() })
        col.addView(menuRow("اسکن QR (دوربین)") { act.startQrScan() })
        col.addView(menuRow("QR از گالری") { act.startQrGallery() })
        col.addView(menuRow("فایل کانفیگ (txt/conf/json)") { act.pickFile() })
        col.addView(menuRow("افزودن دستی (vless/vmess/trojan/ss/hy2/tuic)") { showManualAdd() })
        col.addView(menuRow("وایرگارد (فایل conf / دستی)") { showWireGuardAdd() })
        col.addView(menuRow("دریافت اشتراک (subscription)") { showSubscription() })
        dialog("افزودن سرور", wrapForm(col, scrollable = false), null, null, "بستن")
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

    // ------------------------------------------------------------ منوی سرور

    fun showServerMenu(s: Server) {
        val col = UI.col(ctx)
        col.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 8), UI.dp(ctx, 16), UI.dp(ctx, 16))
        val title = UI.label(ctx, s.name, 15f, p.accent, bold = true)
        title.setPadding(UI.dp(ctx, 4), UI.dp(ctx, 4), UI.dp(ctx, 4), UI.dp(ctx, 8))
        col.addView(title)
        col.addView(menuRow("فعال‌سازی این سرور") { act.selectServer(s) })
        col.addView(menuRow("تست پینگ") { act.pingServers(listOf(s.id)) })
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
