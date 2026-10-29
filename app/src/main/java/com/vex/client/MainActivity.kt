package com.vex.client

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.exifinterface.media.ExifInterface

/**
 * VEX CLIENT — نسخه‌ی نیتیو (بدون WebView / HTML).
 * سه صفحه‌ی خانه / سرورها / تنظیمات در یک اکتیویتی.
 */
class MainActivity : ComponentActivity() {

    lateinit var store: ServersStore
        private set
    lateinit var homeUi: HomeUi
        private set
    lateinit var serversUi: ServersUi
        private set
    lateinit var settingsUi: SettingsUi
        private set
    lateinit var dialogs: DialogsHelper
        private set

    var connectionState: String = "stopped" // stopped | connecting | connected
        private set
    var netState: Int = 0          // 0 ناشناخته، 1 تأیید، 2 رد شد
    var netMsg: String = ""
    var netMs: Int = -1
    var lastError: String = ""

    private lateinit var root: LinearLayout
    private lateinit var screenHome: View
    private lateinit var screenServers: View
    private lateinit var screenSettings: View
    private lateinit var navHome: TextView
    private lateinit var navServers: TextView
    private lateinit var navSettings: TextView
    private var currentTab = 0

    private val main = Handler(Looper.getMainLooper())

    // ---------------- درخواست مجوزها ----------------

    private var pendingPayload: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val cfg = pendingPayload
        pendingPayload = null
        if (cfg != null) continueStart(cfg)
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cfg = pendingPayload
        pendingPayload = null
        if (res.resultCode == RESULT_OK && cfg != null) launchVpnService(cfg)
        else {
            connectionState = "stopped"
            renderAll()
            UI.toast(this, "دسترسی VPN داده نشد")
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        else UI.toast(this, "دسترسی دوربین داده نشد")
    }

    private val qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringExtra(QrScanActivity.EXTRA_TEXT)
        val err = res.data?.getStringExtra("error")
        when {
            !text.isNullOrBlank() -> addFromText(text)
            !err.isNullOrBlank() -> UI.toast(this, err)
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) decodeQrFromUri(uri) else UI.toast(this, "عکسی انتخاب نشد")
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFile(uri)
    }

    // ---------------- چرخه‌ی حیات ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ServersStore(this)
        dialogs = DialogsHelper(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(buildHeader())

        val body = FrameLayout(this)
        homeUi = HomeUi(this)
        serversUi = ServersUi(this)
        settingsUi = SettingsUi(this)
        screenHome = homeUi.build()
        screenServers = serversUi.build()
        screenSettings = settingsUi.build()
        body.addView(screenHome, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        body.addView(screenServers, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        body.addView(screenSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        body.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(body)

        root.addView(buildBottomNav())
        setContentView(root)

        // بازگردانی وضعیت فعلی سرویس (اتصال در پس‌زمینه)
        connectionState = VexVpnService.uiState
        netState = VexVpnService.netState
        netMsg = VexVpnService.netMsg
        netMs = VexVpnService.netMs

        VexVpnService.stateListener = { st, msg -> main.post { onServiceState(st, msg) } }
        VexVpnService.statsListener = { dn, up, tdn, tup -> main.post { homeUi.onStats(dn, up, tdn, tup) } }
        VexVpnService.netListener = { ok, ms, msg -> main.post { onNetResult(ok, ms, msg) } }

        applyThemeAll()
        renderAll()
        homeUi.restoreStats()

        // اتصال خودکار
        if (store.settings.autoConnect && connectionState == "stopped") {
            store.selected()?.let { s ->
                main.postDelayed({ if (connectionState == "stopped") requestStart(s) }, 350)
            }
        }
    }

    override fun onDestroy() {
        VexVpnService.stateListener = null
        VexVpnService.statsListener = null
        VexVpnService.netListener = null
        super.onDestroy()
    }

    // ---------------- هدر / ناوبری ----------------

    private fun buildHeader(): View {
        val ctx = this
        val header = UI.row(ctx)
        header.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 14), UI.dp(ctx, 18), UI.dp(ctx, 10))
        header.tag = "header"

        val logo = TextView(ctx).apply {
            text = "V"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val lp = LinearLayout.LayoutParams(UI.dp(ctx, 44), UI.dp(ctx, 44))
        header.addView(logo, lp)

        val col = UI.col(ctx)
        val t1 = UI.label(ctx, "VEX CLIENT", 16f, Color.WHITE, bold = true)
        val t2 = UI.label(ctx, "کلاینت VPN با هسته‌ی sing-box", 10.5f, Color.WHITE)
        t2.alpha = 0.75f
        col.addView(t1)
        col.addView(t2)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(ctx, 12)
        }
        header.addView(col)

        val stateChip = TextView(ctx).apply {
            id = View.generateViewId()
            text = "—"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(UI.dp(ctx, 12), UI.dp(ctx, 5), UI.dp(ctx, 12), UI.dp(ctx, 5))
            tag = "stateChip"
        }
        header.addView(stateChip)
        return header
    }

    private fun buildBottomNav(): View {
        val ctx = this
        val bar = UI.row(ctx)
        bar.setPadding(UI.dp(ctx, 10), UI.dp(ctx, 6), UI.dp(ctx, 10), UI.dp(ctx, 6))

        navHome = navItem("خانه")
        navServers = navItem("سرورها")
        navSettings = navItem("تنظیمات")
        for ((v, idx) in listOf(navHome to 0, navServers to 1, navSettings to 2)) {
            v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            v.setOnClickListener { switchTab(idx) }
            bar.addView(v)
        }
        bar.tag = "nav"
        return bar
    }

    private fun navItem(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, UI.dp(this@MainActivity, 8), 0, UI.dp(this@MainActivity, 8))
    }

    fun switchTab(idx: Int) {
        currentTab = idx
        screenHome.visibility = if (idx == 0) View.VISIBLE else View.GONE
        screenServers.visibility = if (idx == 1) View.VISIBLE else View.GONE
        screenSettings.visibility = if (idx == 2) View.VISIBLE else View.GONE
        val p = Themes.resolve(this, store.settings.theme)
        navHome.setTextColor(if (idx == 0) p.accent else p.muted)
        navServers.setTextColor(if (idx == 1) p.accent else p.muted)
        navSettings.setTextColor(if (idx == 2) p.accent else p.muted)
        if (idx == 1) serversUi.render()
        if (idx == 2) settingsUi.refresh()
    }

    fun gotoServers() = switchTab(1)

    // ---------------- تم / رندر ----------------

    fun applyThemeAll() {
        val p = Themes.resolve(this, store.settings.theme)
        root.setBackgroundColor(p.bg)
        // هدر
        val header = root.findViewWithTag<LinearLayout>("header")
        header?.setBackgroundColor(p.bg2)
        header?.findViewWithTag<TextView>("stateChip")?.let { chip ->
            chip.background = GradientDrawable().apply {
                cornerRadius = UI.dp(this@MainActivity, 20).toFloat()
                setColor(when (connectionState) {
                    "connected" -> UI.alpha(p.good, 0.16f)
                    "connecting" -> UI.alpha(p.mid, 0.16f)
                    else -> p.card
                })
            }
            chip.setTextColor(when (connectionState) {
                "connected" -> p.good
                "connecting" -> p.mid
                else -> p.muted
            })
            chip.text = when (connectionState) {
                "connected" -> "متصل"
                "connecting" -> "در حال اتصال"
                else -> "قطع"
            }
        }
        // لوگو و متن هدر
        if (header != null && header.childCount > 0) {
            val logo = header.getChildAt(0) as? TextView
            logo?.background = GradientDrawable().apply {
                cornerRadius = UI.dp(this@MainActivity, 12).toFloat()
                setColor(p.accent)
            }
            logo?.setTextColor(p.onAccent)
            val col = header?.getChildAt(1) as? LinearLayout
            if (col != null && col.childCount >= 2) {
                (col.getChildAt(0) as? TextView)?.setTextColor(p.text)
                (col.getChildAt(1) as? TextView)?.setTextColor(p.muted)
            }
        }
        // نوار پایین
        val nav = root.findViewWithTag<LinearLayout>("nav")
        nav?.setBackgroundColor(p.bg2)
        switchTab(currentTab)
        homeUi.applyTheme(p)
        serversUi.applyTheme(p)
        settingsUi.applyTheme(p)
    }

    fun renderAll() {
        homeUi.render()
        serversUi.render()
        applyThemeAll()
    }

    fun setTheme(key: String) {
        store.settings.theme = key
        store.saveSettings()
        applyThemeAll()
        UI.toast(this, "تم اعمال شد")
    }

    // ---------------- وضعیت سرویس ----------------

    private fun onServiceState(st: String, msg: String) {
        when (st) {
            "connected" -> connectionState = "connected"
            "stopped" -> connectionState = "stopped"
            "error" -> {
                connectionState = "stopped"
                lastError = msg
                dialogs.showError(msg)
            }
        }
        renderAll()
    }

    private fun onNetResult(ok: Boolean, ms: Int, msg: String) {
        netState = if (ok) 1 else 2
        netMs = if (ok) ms else -1
        netMsg = msg
        homeUi.render()
        applyThemeAll()
    }

    // ---------------- اتصال / قطع ----------------

    fun togglePower() {
        when (connectionState) {
            "connected", "connecting" -> requestStop()
            else -> {
                val s = store.selected()
                if (s == null) {
                    UI.toast(this, "اول یک سرور انتخاب کن")
                    switchTab(1)
                } else requestStart(s)
            }
        }
    }

    fun requestStart(s: Server) {
        if (connectionState != "stopped") return
        val payload = store.connectionPayload(s)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingPayload = payload
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueStart(payload)
        }
    }

    private fun continueStart(payload: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingPayload = payload
            vpnPermission.launch(intent)
        } else {
            launchVpnService(payload)
        }
    }

    private fun launchVpnService(payload: String) {
        connectionState = "connecting"
        lastError = ""
        netState = 0
        renderAll()
        val i = Intent(this, VexVpnService::class.java)
            .setAction(VexVpnService.ACTION_START)
            .putExtra(VexVpnService.EXTRA_CONFIG, payload)
        ContextCompat.startForegroundService(this, i)
    }

    fun requestStop() {
        startService(Intent(this, VexVpnService::class.java).setAction(VexVpnService.ACTION_STOP))
    }

    fun selectServer(s: Server) {
        store.select(s.id)
        serversUi.render()
        homeUi.renderServerCard()
        if (connectionState != "stopped") {
            UI.toast(this, "در حال اتصال به «${s.name}»…")
            requestStop()
            main.postDelayed({
                val cur = store.selected()
                if (cur != null && connectionState == "stopped") requestStart(cur)
            }, 1000)
        } else {
            UI.toast(this, "سرور «${s.name}» فعال شد")
        }
    }

    fun testInternetNow() {
        if (connectionState != "connected") {
            UI.toast(this, "اول وصل شو، بعد تست کن")
            return
        }
        VexVpnService.requestNetTest()
        netState = 0
        homeUi.render()
        UI.toast(this, "تست اینترنت در حال اجرا…")
    }

    // ---------------- افزودن سرور ----------------

    fun addFromText(text: String) {
        val list = LinkParser.parseAll(text)
        if (list.isEmpty()) {
            UI.toast(this, "کانفیگ معتبری پیدا نشد")
            return
        }
        var saved = 0
        var lastId = -1
        for (raw in list) {
            try {
                val inf = LinkParser.info(raw)
                val sv = store.add(raw, inf.name ?: "", inf.protocol)
                if (sv != null) { saved++; lastId = sv.id }
            } catch (_: Throwable) {}
        }
        if (saved > 0) {
            if (store.selectedId == -1 && lastId > 0) store.select(lastId)
            renderAll()
            switchTab(1)
            UI.toast(this, "$saved سرور ذخیره شد")
            pingAll(silent = true)
        } else {
            UI.toast(this, "کانفیگ تکراری یا نامعتبر بود")
        }
    }

    fun pasteFromClipboard() {
        val text = try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.primaryClip?.let { item ->
                if (item.itemCount > 0) item.getItemAt(0).coerceToText(this)?.toString() else null
            }
        } catch (_: Throwable) { null }
        if (text.isNullOrBlank()) UI.toast(this, "کلیپ‌بورد خالی است") else addFromText(text)
    }

    fun startQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun startQrGallery() {
        try {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Throwable) {
            UI.toast(this, "گالری در دسترس نیست")
        }
    }

    fun pickFile() {
        try { filePicker.launch("*/*") } catch (_: Throwable) { UI.toast(this, "انتخاب فایل ممکن نشد") }
    }

    private fun importFile(uri: Uri) {
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: ""
                if (text.isBlank()) { main.post { UI.toast(this, "فایل خالی است") }; return@Thread }
                main.post { addFromText(text) }
            } catch (e: Throwable) {
                main.post { UI.toast(this, "خواندن فایل ناموفق بود: ${e.message ?: ""}") }
            }
        }.start()
    }

    /** خواندن و دیکود کردن QR از عکس گالری (ML Kit) */
    private fun decodeQrFromUri(uri: Uri) {
        Thread {
            try {
                val bitmap = loadBitmapForQr(uri)
                if (bitmap == null) {
                    main.post { UI.toast(this, "خواندن عکس ناموفق بود") }
                    return@Thread
                }
                val rotation = exifRotation(uri)
                main.post {
                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    scanner.process(InputImage.fromBitmap(bitmap, rotation))
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull { it.rawValue != null }?.rawValue
                            if (value != null) addFromText(value)
                            else UI.toast(this, "QR کدی در این عکس پیدا نشد")
                        }
                        .addOnFailureListener { UI.toast(this, "تحلیل عکس ناموفق بود") }
                }
            } catch (_: Throwable) {
                main.post { UI.toast(this, "خواندن عکس ناموفق بود") }
            }
        }.start()
    }

    private fun loadBitmapForQr(uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        val bOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bOpts) }
    }

    private fun exifRotation(uri: Uri): Int = try {
        contentResolver.openInputStream(uri)?.use { ins ->
            when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Throwable) { 0 }

    // ---------------- پینگ ----------------

    private var pingRunning = false

    fun pingAll(silent: Boolean = false) {
        pingServers(store.servers.map { it.id }, silent)
    }

    fun pingServers(ids: List<Int>, silent: Boolean = false) {
        if (pingRunning) { if (!silent) UI.toast(this, "پینگ در حال اجراست"); return }
        if (ids.isEmpty()) { if (!silent) UI.toast(this, "سروری برای پینگ نیست"); return }
        pingRunning = true
        if (!silent) UI.toast(this, "پینگ ${ids.size} سرور…")
        Thread {
            for (id in ids) {
                val s = store.servers.firstOrNull { it.id == id } ?: continue
                val inf = try { LinkParser.info(s.raw) } catch (_: Throwable) { null }
                val ms = if (inf?.host.isNullOrBlank()) -2 else NetUtil.tcpPing(inf!!.host!!, inf.port ?: 443)
                store.setPing(id, ms)
                main.post { serversUi.render(); homeUi.renderServerCard() }
            }
            pingRunning = false
            main.post { if (!silent) UI.toast(this, "پینگ تمام شد") }
        }.start()
    }

    // ---------------- ابزار ----------------

    fun toast(msg: String) = UI.toast(this, msg)

    /** دسترسی به Handler اصلی برای کلاس‌های کمکی */
    fun mainHandler(): Handler = main

    fun resetSettings() {
        store.resetToDefaults()
        // بازسازی کامل صفحه‌ی تنظیمات تا فیلدها (MTU/DNS) هم ریست شوند
        val parent = screenSettings.parent as? FrameLayout ?: return
        parent.removeView(screenSettings)
        settingsUi = SettingsUi(this)
        screenSettings = settingsUi.build()
        parent.addView(screenSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        applyThemeAll()
        UI.toast(this, "تنظیمات بازنشانی شد")
    }

    fun showErrDetails() {
        if (connectionState == "stopped" && lastError.isEmpty() && VexVpnService.coreLogAll().isBlank()) {
            UI.toast(this, "خطایی ثبت نشده")
            return
        }
        dialogs.showError(if (lastError.isNotEmpty()) lastError else VexVpnService.coreLogTail(6))
    }

    fun showCoreLog() = dialogs.showCoreLog()
    fun showAddMenu() = dialogs.showAddMenu()
    fun showServerMenu(s: Server) = dialogs.showServerMenu(s)
    fun confirmDelete(ids: Set<Int>, after: () -> Unit) = dialogs.confirmDelete(ids, after)

    override fun onBackPressed() {
        if (currentTab != 0) switchTab(0) else super.onBackPressed()
    }
}
