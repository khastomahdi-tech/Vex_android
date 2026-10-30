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
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    companion object {
        /** لینک کانال تلگرام — دکمه‌ی تلگرام بالای صفحه به همین کانال می‌رود */
        const val TELEGRAM_CHANNEL_URL = "https://t.me/vex_vpnfree"

        /** فاصله‌ی بازبینی دوره‌ای وضعیت ربات (۵ دقیقه) */
        private const val GATE_WATCH_INTERVAL_MS = 5 * 60_000L
    }

    lateinit var store: ServersStore
        private set
    lateinit var homeUi: HomeUi
        private set
    lateinit var serversUi: ServersUi
        private set
    lateinit var settingsUi: SettingsUi
        private set
    lateinit var statsUi: StatsUi
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
    private lateinit var screenStats: View

    // ---- هدر (داخل ستون اسکرول هر صفحه سوار می‌شود) ----
    private lateinit var headerView: View

    // ---- دروازه‌ی ورود ----
    private lateinit var gateRoot: LinearLayout
    private var gateBuilt = false
    /** دلیل بسته‌بودن برنامه (از ربات مدیر) */
    private var gateReason: String? = null

    /** آیا قفل PIN این نشست باز شده است؟ */
    private var pinUnlocked = false

    /** سرور اتصال فعلی رایگان است یا شخصی؟ (برای تفکیک آمار مصرف) */
    private var sessionIsFree = false

    // ---- اجزای هدر ----
    private lateinit var tgBtn: FrameLayout
    private lateinit var tgIcImg: ImageView
    private lateinit var gearBtn: FrameLayout
    private lateinit var gearIc: IconView
    private lateinit var sunBtn: FrameLayout
    private lateinit var sunIc: IconView
    private lateinit var vexTv: TextView
    private lateinit var clientTv: TextView

    // ---- اجزای نوار پایین ----
    private class NavItem(val item: LinearLayout, val icon: IconView, val label: TextView)
    private val navItems = ArrayList<NavItem>(4)
    private var currentTab = 0

    /** آیا در حال دریافت سرورهای رایگان هستیم؟ (برای رندر صفحه‌ی سرورها) */
    var freeFetching = false
        private set

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
        // دروازه‌ی ورود: اول اینترنت (با تایم‌اوت) — بدون اینترنت وارد برنامه نمی‌شود
        setContentView(buildGateView())
        setGateUi("checking")
        runGate()
    }

    /**
     * اجرای دروازه:
     *  ۱) حداکثر ۱۲ ثانیه برای اینترنت صبر می‌کند؛ اگر نشد → قفل «دسترسی به اینترنت نداری».
     *  ۲) با اینترنت، برنامه بلافاصله باز می‌شود (بدون انتظار تلگرام).
     *  ۳) هم‌زمان کلید خاموش/روشنِ ربات چک می‌شود؛ اگر مدیر /close داده باشد،
     *     برنامه وسط کار هم قفل می‌شود. و نصب جدید به مدیر اطلاع داده می‌شود.
     */
    private fun runGate() {
        setGateUi("checking")
        Thread {
            val deadline = System.currentTimeMillis() + 12_000L
            var online = false
            while (System.currentTimeMillis() < deadline) {
                if (TelegramGate.hasInternet()) { online = true; break }
                try { Thread.sleep(1300) } catch (_: InterruptedException) { return@Thread }
            }
            if (!online) {
                main.post { if (!isFinishing) setGateUi("no-internet") }
                return@Thread
            }
            // اینترنت هست → برنامه بلافاصله باز می‌شود
            main.post { if (!isFinishing) enterApp() }
            // بررسی وضعیت ربات (کلادفلر) + ثبت نصب/ورود — پاسخ report هم وضعیت را می‌دهد
            val info = TelegramGate.notifyInstall(this) ?: TelegramGate.gateInfo()
            TelegramGate.ackIfChanged(info.gate, this)
            // مصرف جمع‌شده‌ی قبلی (اگر مانده) به بک‌اند ربات گزارش می‌شود
            TrafficStore.flushAsync(this)
            if (info.gate == TelegramGate.CLOSED) {
                gateReason = info.reason
                main.post {
                    if (!isFinishing) {
                        requestStop() // برنامه بسته شد → تونل هم قطع می‌شود
                        setGateUi("closed")
                    }
                }
            }
            startGateWatch()
        }.start()
    }

    /**
     * بازبینی دوره‌ای وضعیت ربات (هر ۵ دقیقه) تا دستور /close حتی وسط کار
     * هم برنامه را قفل کند؛ پس از قفل، تماشا متوقف می‌شود.
     */
    private fun startGateWatch() {
        main.postDelayed({
            Thread {
                // گزارش دوره‌ای مصرف به بک‌اند ربات (وقتی متصل است)
                if (connectionState == "connected") TrafficStore.flushAsync(this@MainActivity)
                val info = TelegramGate.gateInfo()
                if (info.gate == TelegramGate.CLOSED) {
                    gateReason = info.reason
                    TelegramGate.ackIfChanged(info.gate, this)
                    main.post {
                        if (!isFinishing) {
                            requestStop()
                            setGateUi("closed")
                        }
                    }
                } else {
                    main.post { startGateWatch() }
                }
            }.start()
        }, GATE_WATCH_INTERVAL_MS)
    }

    /** بازکردن رابط اصلی — بعد از قفل هم بدون ساخت مجدد برمی‌گردد */
    private fun enterApp() {
        if (!::root.isInitialized) {
            store = ServersStore(this)
            dialogs = DialogsHelper(this)
            // شمارنده‌ی مصرف باید قبل از هر statsListener آماده باشد وگرنه دلتاها می‌افتند
            TrafficStore.init(this)

            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            // هدر دیگر ثابت بالای صفحه نیست — داخل ستون اسکرول هر صفحه می‌نشیند
            // تا با بالا/پایین کردن صفحه، هدر هم حرکت کند.
            headerView = buildHeader()

            val body = FrameLayout(this)
            homeUi = HomeUi(this)
            serversUi = ServersUi(this)
            settingsUi = SettingsUi(this)
            statsUi = StatsUi(this)
            screenHome = homeUi.build()
            screenServers = serversUi.build()
            screenSettings = settingsUi.build()
            screenStats = statsUi.build()
            body.addView(screenHome, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            body.addView(screenServers, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            body.addView(screenStats, FrameLayout.LayoutParams(
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
            if (connectionState == "connected") {
                sessionIsFree = store.selected()?.let { store.isFree(it) } ?: false
            }

            VexVpnService.stateListener = { st, msg -> main.post { onServiceState(st, msg) } }
            VexVpnService.statsListener = { dn, up, tdn, tup -> main.post {
                TrafficStore.addSession(tdn, tup, sessionIsFree)
                homeUi.onStats(dn, up, tdn, tup)
                statsUi.onStats(dn, up, tdn, tup)
            } }
            VexVpnService.netListener = { ok, ms, msg -> main.post { onNetResult(ok, ms, msg) } }

            applyThemeAll()
            renderAll()
            homeUi.restoreStats()

            // به‌روزرسانی خودکار سرورهای رایگان
            scheduleFreeAutoRefresh()
            Thread { refreshFreeServers(silent = true) }.start()

            // اتصال خودکار
            if (store.settings.autoConnect && connectionState == "stopped") {
                store.selected()?.let { s ->
                    main.postDelayed({ if (connectionState == "stopped") requestStart(s) }, 350)
                }
            }
        }

        // قفل PIN — اگر رمز گذاشته شده باشد، قبل از ورود کیبورد اختصاصی می‌آید
        if (store.settings.pinHash.isNotEmpty() && !pinUnlocked) {
            LockScreen.showLock(this) {
                pinUnlocked = true
                setContentView(root)
                applyThemeAll()
                renderAll()
                homeUi.restoreStats()
                if (store.settings.showIntro) IntroSplash.show(this)
            }
            return
        }
        setContentView(root)
        applyThemeAll()
        // پیش‌نمایش ورود: دو شیر بالدار از بالا می‌افتند (لمس = رد کردن) — قابل خاموش کردن از تنظیمات
        if (store.settings.showIntro) IntroSplash.show(this)
    }

    override fun onDestroy() {
        VexVpnService.stateListener = null
        VexVpnService.statsListener = null
        VexVpnService.netListener = null
        super.onDestroy()
    }

    // ---------------- هدر / ناوبری ----------------

    /** هدر سلطنتی: دکمه‌ی تلگرام + «Vex Client» طلایی + دکمه‌ی گیر — بدون هیچ شیر/آیکونی پشت متن */
    private fun buildHeader(): View {
        val ctx = this
        val header = FrameLayout(ctx)
        header.tag = "header"
        header.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 6))

        // عنوان مرکزی: فقط «Vex Client» انگلیسی — شیر پشت عنوان حذف شد؛ فقط متن
        val titleRow = UI.row(ctx).apply { gravity = Gravity.CENTER }
        vexTv = TextView(ctx).apply {
            text = "Vex"
            textSize = 21f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            includeFontPadding = false
            letterSpacing = 0.02f
        }
        clientTv = TextView(ctx).apply {
            text = "Client" // انگلیسی — طبق درخواست
            textSize = 16f
            typeface = Fonts.bold(ctx)
            includeFontPadding = false
        }
        titleRow.addView(vexTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        titleRow.addView(clientTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 6)
            gravity = Gravity.CENTER_VERTICAL
        })
        header.addView(titleRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))

        // دکمه‌ی گیر تنظیمات + دکمه‌ی خورشید (روز/شب) — سمت راست هدر، بدون هیچ قاب مربع
        gearBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11))
        }
        gearIc = IconView(ctx, "gear").apply { bold = true }
        gearBtn.addView(gearIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 24), UI.dp(ctx, 24), Gravity.CENTER))
        gearBtn.setOnClickListener { gotoSettings() }

        sunBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11))
        }
        sunIc = IconView(ctx, "sun").apply { bold = true }
        sunBtn.addView(sunIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 24), UI.dp(ctx, 24), Gravity.CENTER))
        sunBtn.setOnClickListener { toggleDayNight() }

        val rightBox = UI.row(ctx)
        // در RTL اولین فرزند سمت راست است: اول گیر، بعد خورشید کنارش
        rightBox.addView(gearBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 46), UI.dp(ctx, 46)))
        rightBox.addView(sunBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 46), UI.dp(ctx, 46)))
        header.addView(rightBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.RIGHT or Gravity.CENTER_VERTICAL))

        // دکمه‌ی تلگرام (سمت چپ) — آیکون رسمی SVG تلگرام؛ رفتن به کانال تلگرام
        tgBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 10), UI.dp(ctx, 10), UI.dp(ctx, 10), UI.dp(ctx, 10))
        }
        tgIcImg = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_telegram)
        }
        tgBtn.addView(tgIcImg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // لمس = بازکردن کانال تلگرام (اول اپلیکیشن تلگرام، بعد مرورگر)
        tgBtn.setOnClickListener { openTelegramChannel() }
        header.addView(tgBtn, FrameLayout.LayoutParams(
            UI.dp(ctx, 46), UI.dp(ctx, 46), Gravity.LEFT or Gravity.CENTER_VERTICAL))

        return header
    }

    /** نوار پایین سه‌تیکه با آیکون: خانه / سرورها / آمار */
    private fun buildBottomNav(): View {
        val ctx = this
        val bar = UI.row(ctx)
        bar.tag = "nav"
        bar.setPadding(UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6), UI.dp(ctx, 6))
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(UI.dp(ctx, 12), UI.dp(ctx, 6), UI.dp(ctx, 12), UI.dp(ctx, 10))
        }
        navItems.clear()
        // در RTL اولین فرزند سمت راست است — تنظیمات از دکمه‌ی گیر بالای صفحه باز می‌شود
        val defs = listOf(
            Triple("خانه", "home", 0),
            Triple("سرورها", "list", 1),
            Triple("آمار", "chart", 2)
        )
        for ((lbl, ic, idx) in defs) {
            val item = UI.col(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(UI.dp(ctx, 10), UI.dp(ctx, 7), UI.dp(ctx, 10), UI.dp(ctx, 7))
            }
            val iv = IconView(ctx, ic)
            val tv = UI.label(ctx, lbl, 11f, Themes.goldMuted, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, UI.dp(ctx, 3), 0, 0)
            }
            item.addView(iv, LinearLayout.LayoutParams(UI.dp(ctx, 22), UI.dp(ctx, 22)))
            item.addView(tv)
            item.setOnClickListener { switchTab(idx) }
            item.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(UI.dp(ctx, 2), 0, UI.dp(ctx, 2), 0)
            }
            bar.addView(item)
            navItems.add(NavItem(item, iv, tv))
        }
        return bar
    }
    fun switchTab(idx: Int) {
        currentTab = idx
        attachHeader(idx)
        screenHome.visibility = if (idx == 0) View.VISIBLE else View.GONE
        screenServers.visibility = if (idx == 1) View.VISIBLE else View.GONE
        screenStats.visibility = if (idx == 2) View.VISIBLE else View.GONE
        screenSettings.visibility = if (idx == 3) View.VISIBLE else View.GONE
        for ((i, n) in navItems.withIndex()) {
            val active = i == idx
            // قرص طلایی گرادیانی برای آیتم فعال (مطابق طرح مرجع)
            n.item.background = if (active) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = UI.dp(this@MainActivity, 16).toFloat()
                colors = intArrayOf(0xFFF0C64B.toInt(), 0xFFB9861B.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            } else null
            // روی قرص طلایی همیشه متن تیره — در هر تمی خوانا می‌ماند
            n.icon.iconColor = if (active) 0xFF1A1304.toInt() else Themes.goldMuted
            n.label.setTextColor(if (active) 0xFF1A1304.toInt() else Themes.goldMuted)
        }
        if (idx == 1) serversUi.render()
        if (idx == 2) statsUi.render()
        if (idx == 3) settingsUi.refresh()
    }

    fun gotoServers() = switchTab(1)

    /** بازکردن تنظیمات از دکمه‌ی گیر بالای صفحه */
    fun gotoSettings() = switchTab(3)

    // ---------------- هدر متحرک (همراه اسکرول) ----------------

    /**
     * هدر داخل ستون اسکرول صفحه‌ی فعال سوار می‌شود تا با بالا/پایین کردن محتوا،
     * هدر هم (مثل بقیه‌ی نوشته‌ها) حرکت کند — نه اینکه ثابت بالا وایسد.
     */
    private fun attachHeader(idx: Int) {
        if (!::headerView.isInitialized) return
        val target = when (idx) {
            0 -> if (::screenHome.isInitialized) screenHome else return
            1 -> if (::screenServers.isInitialized) screenServers else return
            2 -> if (::screenStats.isInitialized) screenStats else return
            else -> if (::screenSettings.isInitialized) screenSettings else return
        }
        val sv = firstScrollView(target) ?: return
        val col = sv.getChildAt(0) as? LinearLayout ?: return
        if (headerView.parent === col) return
        (headerView.parent as? android.view.ViewGroup)?.removeView(headerView)
        col.addView(headerView, 0)
    }

    private fun firstScrollView(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) firstScrollView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** بازکردن کانال تلگرام: اول اپ تلگرام (tg://) و اگر نبود مرورگر */
    private fun openTelegramChannel() {
        val url = TELEGRAM_CHANNEL_URL
        val domain = url.substringAfter("t.me/").trimStart('/').substringBefore('?').substringBefore('/')
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$domain")))
            return
        } catch (_: Throwable) {}
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            UI.toast(this, "تلگرام در دسترس نیست")
        }
    }

    // ---------------- تم / رندر ----------------

    fun applyThemeAll() {
        val p = Themes.resolve(this, store.settings.theme)
        // اول هدر داخل ستون اسکرول صفحه‌ی فعال سوار شود، بعد تم روی همه اعمال شود
        attachHeader(currentTab)
        root.setBackgroundColor(p.bg)
        // هدر سلطنتی — شفاف روی پس‌زمینه؛ دکمه‌ها بدون قاب مربع (درخواست کاربر)
        val header = root.findViewWithTag<FrameLayout>("header")
        header?.let {
            tgBtn.background = null
            gearBtn.background = null
            sunBtn.background = null
            gearIc.iconColor = p.accent
            // خورشید: در حالت روز پررنگ، در حالت شب کمی آرام‌تر
            sunIc.iconColor = if (Themes.isLightBg(p)) p.accent else UI.alpha(p.accent, 0.85f)
            // متن Vex (تیره) و Client (آبی/طلایی)
            vexTv.setTextColor(if (Themes.isLightBg(p)) 0xFF111111.toInt() else 0xFFF5F7FF.toInt())
            clientTv.setTextColor(p.accent)
        }
        // نوار پایین طلایی
        val nav = root.findViewWithTag<LinearLayout>("nav")
        nav?.background = UI.card(this, p, 24, stroke = p.line, fillColor = p.bg2)
        switchTab(currentTab)
        homeUi.applyTheme(p)
        serversUi.applyTheme(p)
        settingsUi.applyTheme(p)
        statsUi.applyTheme(p)
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

    /**
     * دکمه‌ی خورشید هدر: یک لمس = روز، لمس دوباره = شب.
     * بین تم سلطنتیِ شب (پیش‌فرض) و سلطنتیِ روز جابه‌جا می‌شود؛
     * اگر کاربر تم دیگری انتخاب کرده باشد، جفتِ روشن/تیره‌ی همان استفاده می‌شود.
     */
    fun toggleDayNight() {
        val next = when (store.settings.theme) {
            "royal_light" -> "royal"
            "royal" -> "royal_light"
            "light" -> "dark"
            "dark" -> "light"
            "shir" -> "royal_light"
            else ->
                if (Themes.isLightBg(Themes.resolve(this, store.settings.theme))) "royal"
                else "royal_light"
        }
        store.settings.theme = next
        store.saveSettings()
        applyThemeAll()
        UI.toast(this, if (Themes.isLightBg(Themes.resolve(this, next)))
            "☀️ حالت روز فعال شد" else "🌙 حالت شب فعال شد")
    }

    // ---------------- وضعیت سرویس ----------------

    private fun onServiceState(st: String, msg: String) {
        when (st) {
            "connected" -> {
                connectionState = "connected"
                // بازخورد لرزشی کوتاه هنگام اتصال موفق (اگر در تنظیمات روشن باشد)
                if (store.settings.hapticOnConnect) vibrateConnected()
                // گرفتن «آی‌پی خروجی» از داخل تونل
                fetchExitIp()
                applyKeepScreenOn()
            }
            "stopped" -> {
                connectionState = "stopped"
                // آخرین دلتای جلسه به بک‌اند ربات گزارش می‌شود، بعد صفری می‌شود
                TrafficStore.flushAsync(this)
                resetLiveStats()
                applyKeepScreenOn()
            }
            "error" -> {
                connectionState = "stopped"
                lastError = msg
                TrafficStore.flushAsync(this)
                resetLiveStats()
                applyKeepScreenOn()
                dialogs.showError(msg)
            }
        }
        renderAll()
    }

    /** روشن ماندن صفحه در حین اتصال (تنظیمات کاربر) */
    fun applyKeepScreenOn() {
        val on = store.settings.keepScreenOn && connectionState == "connected"
        if (::homeUi.isInitialized) homeUi.root.keepScreenOn = on
    }

    /** خاموش‌کردن VPN → سرعت‌های لحظه‌ای و مجموع جلسه صفر می‌شوند + آی‌پی خروجی خالی */
    private fun resetLiveStats() {
        VexVpnService.snapDn = 0; VexVpnService.snapUp = 0
        VexVpnService.snapTDn = 0; VexVpnService.snapTUp = 0
        homeUi.onStats(0, 0, 0, 0)
        statsUi.onStats(0, 0, 0, 0)
        homeUi.clearExitIp()
    }

    private var exitIpThread: Thread? = null

    /** گرفتن آی‌پی خروجی سرور — درخواست از داخل تونل می‌رود (فقط وقتی متصل است) */
    private fun fetchExitIp() {
        if (!store.settings.showExitIp) { homeUi.clearExitIp(); return }
        exitIpThread?.interrupt()
        exitIpThread = Thread {
            // درخواست از inbound محلی هسته رد می‌شود تا واقعاً از تونل خارج شود؛
            // در نتیجه آی‌پی برگشتی همان خروجی سروری است که به آن وصل شده‌ایم.
            val info = try { NetUtil.fetchExitInfo(throughTunnel = true) } catch (_: Throwable) { null }
            if (Thread.currentThread().isInterrupted) return@Thread
            main.post {
                if (connectionState == "connected") homeUi.setExitIp(info)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** لرزش کوتاه اتصال موفق */
    private fun vibrateConnected() {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(60)
            }
        } catch (_: Throwable) {}
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
        // حالت per-app: اگر «فقط برنامه‌های انتخابی» خالی باشد اجازه‌ی اتصال نده
        if (store.settings.perAppMode == 1 && store.settings.perAppPackages.isEmpty()) {
            UI.toast(this, "اول برنامه‌های موردنظر را در تنظیمات انتخاب کن")
            gotoSettings()
            return
        }
        val payload = store.connectionPayload(s)
        // تنظیمات رفتاری سرویس (پیش از شروع)
        VexVpnService.autoReconnect = store.settings.autoReconnect
        VexVpnService.autoNetTest = store.settings.autoNetTest
        VexVpnService.showNotifSpeed = store.settings.showNotifSpeed
        VexVpnService.perAppMode = store.settings.perAppMode
        VexVpnService.perAppPackages = store.settings.perAppPackages.toSet()
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
        // تفکیک آمار مصرف: این جلسه روی سرور رایگان است یا شخصی؟
        sessionIsFree = store.selected()?.let { store.isFree(it) } ?: false
        TrafficStore.resetSession()
        statsUi.resetSession() // آمار اوج/میانگین جلسه‌ی جدید از صفر
        homeUi.onStats(0, 0, 0, 0)
        homeUi.clearExitIp()
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
            dialogs.dismissAddMenu() // بستن خودکار منوی افزودن پس از ذخیره
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

    /** پینگ همه‌ی سرورهای رایگان */
    fun pingFreeAll(silent: Boolean = false) {
        pingServers(store.freeServers.map { it.id }, silent)
    }

    fun pingServers(ids: List<Int>, silent: Boolean = false) {
        if (pingRunning) { if (!silent) UI.toast(this, "پینگ در حال اجراست"); return }
        if (ids.isEmpty()) { if (!silent) UI.toast(this, "سروری برای پینگ نیست"); return }
        pingRunning = true
        if (!silent) UI.toast(this, "پینگ ${ids.size} سرور…")
        val pingTimeout = store.settings.pingTimeout
        Thread {
            for (id in ids) {
                val s = store.servers.firstOrNull { it.id == id }
                    ?: store.freeServers.firstOrNull { it.id == id }
                    ?: continue
                val ms = FreeServers.pingOne(s, pingTimeout)
                store.setPing(id, ms)
                main.post { serversUi.render(); homeUi.renderServerCard() }
            }
            pingRunning = false
            main.post { if (!silent) UI.toast(this, "پینگ تمام شد") }
        }.start()
    }

    // ---------------- سرورهای رایگان ----------------

    /**
     * دریافت سرورهای رایگان از بک‌اند ربات (فقط مدیر از ربات اضافه/حذف می‌کند):
     * دریافت → پارس → پینگ برای مرتب‌سازی → جایگزینی کامل لیست.
     *
     * v3.15:
     *  • شبکه نبود (null) → لیست فعلی دست‌نخورده می‌ماند (هیچ‌وقت پاک نمی‌شود)؛
     *  • سرورِ اتصال‌فعلی حذف شده باشد → اتصال فوراً قطع می‌شود؛
     *  • پینگ نشدن یک سرور دیگر آن را از لیست حذف نمی‌کند (ته لیست می‌نشیند).
     *
     *  silent = true → بی‌صدا و بدون پیام؛ برای به‌روزرسانی خودکار هر دقیقه.
     *  silent = false → با پیام پیشرفت و toast.
     */
    fun refreshFreeServers(silent: Boolean = false) {
        if (FreeServers.fetching) { if (!silent) UI.toast(this, "دریافت سرورهای رایگان در حال اجراست"); return }
        freeFetching = true
        if (silent) {
            serversUi.freeProgressMsg = "به‌روزرسانی خودکار…"
        } else {
            serversUi.freeProgressMsg = "در حال دریافت از سرور مدیریت…"
            if (serversUi.tab == 1) serversUi.render()
        }
        Thread {
            val dtos = TelegramGate.fetchFreeServers() // null = شبکه/سرور در دسترس نبود
            var alive: List<Server> = emptyList()
            val fetchOk = dtos != null
            val list = dtos ?: emptyList()
            if (list.isNotEmpty()) {
                alive = FreeServers.fromBot(list) { msg ->
                    if (!silent) main.post {
                        serversUi.freeProgressMsg = msg
                        if (serversUi.tab == 1) serversUi.render()
                    }
                }
            } else if (dtos != null && !silent) {
                main.post {
                    serversUi.freeProgressMsg = "هنوز سرور رایگانی از سمت مدیریت اضافه نشده است"
                    if (serversUi.tab == 1) serversUi.render()
                }
            }
            freeFetching = false
            main.post {
                if (fetchOk) {
                    store.replaceFreeServers(alive)
                    // اگر به سرور رایگانی وصل بودیم که از پنل حذف شده → قطع فوری اتصال
                    if (connectionState == "connected" && sessionIsFree) {
                        val cur = store.selected()
                        if (cur != null && alive.none { it.raw == cur.raw }) {
                            UI.toast(this, "این سرور رایگان حذف شد — اتصال قطع شد")
                            requestStop()
                        }
                    }
                    if (!silent) {
                        UI.toast(this, if (alive.isEmpty()) "سرور رایگانی ثبت نشده است" else "${alive.size} سرور رایگان آماده شد")
                    }
                } else if (!silent) {
                    UI.toast(this, "دسترسی به سرور مدیریت نبود — لیست فعلی حفظ شد")
                }
                if (!silent || serversUi.tab == 1) serversUi.render()
                homeUi.renderServerCard()
                applyKeepScreenOn()
            }
        }.start()
    }

    /** حلقه‌ی به‌روزرسانی خودکار سرورهای رایگان — کاربر نیازی به دکمه ندارد */
    private val freeRefreshTask = object : Runnable {
        override fun run() {
            Thread { refreshFreeServers(silent = true) }.start()
            scheduleFreeAutoRefresh()
        }
    }

    private fun scheduleFreeAutoRefresh() {
        val sec = store.settings.freeRefreshSec
        if (sec <= 0) return // «فقط هنگام باز شدن»
        main.postDelayed(freeRefreshTask, sec * 1000L)
    }

    /** بعد از تغییر بازه در تنظیمات، حلقه از نو چیده می‌شود */
    fun restartFreeAutoRefresh() {
        main.removeCallbacks(freeRefreshTask)
        scheduleFreeAutoRefresh()
    }

    // ---------------- ابزار ----------------

    fun toast(msg: String) = UI.toast(this, msg)

    /** دسترسی به Handler اصلی برای کلاس‌های کمکی */
    fun mainHandler(): Handler = main

    fun resetSettings() {
        store.resetToDefaults()
        UI.toast(this, "تنظیمات بازنشانی شد")
        rebuildSettings()
    }

    /** ساخت دوباره‌ی کامل صفحه‌ی تنظیمات (بعد از تغییر رمز/ریست/…) */
    fun rebuildSettings() {
        if (!::root.isInitialized) return
        setContentView(root)
        val parent = screenSettings.parent as? FrameLayout ?: return
        parent.removeView(screenSettings)
        settingsUi = SettingsUi(this)
        screenSettings = settingsUi.build()
        parent.addView(screenSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        applyThemeAll()
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
    fun showEditServer(s: Server) = dialogs.showEditServer(s)

    /**
     * حذف فوری بدون هیچ دیالوگ تأیید — همان لحظه حذف می‌شود.
     * فقط اگر همین سرورِ در حال اتصال باشد، پیام می‌دهد که اول اتصال را قطع کن.
     */
    fun deleteServers(ids: Set<Int>) {
        if (ids.isEmpty()) return
        if (connectionState != "stopped" && store.selectedId in ids) {
            UI.toast(this, "اول اتصال را از این سرور قطع کن، بعد سرور را حذف کن")
            return
        }
        store.remove(ids)
        serversUi.clearChecks()
        renderAll()
        UI.toast(this, if (ids.size == 1) "سرور حذف شد" else "${ids.size} سرور حذف شد")
    }

    // ---------------- دروازه‌ی ورود (قفل صفحه) ----------------

    /** صفحه‌ی قفل ورود — چیدمان سلطنتی تیره با لوگوی طلایی */
    private fun buildGateView(): View {
        val ctx = this
        gateRoot = UI.col(ctx).apply { gravity = Gravity.CENTER }
        gateBuilt = true
        return gateRoot
    }

    /** حالت‌های قفل: checking (اسپینر) / no-internet / closed */
    private fun setGateUi(state: String) {
        if (!gateBuilt) return
        val ctx = this
        val p = Themes.dark()
        // اگر برنامه باز بوده (قفل وسط کار) → صفحه‌ی قفل جایش را می‌گیرد
        setContentView(gateRoot)
        gateRoot.setBackgroundColor(p.bg)
        gateRoot.removeAllViews()

        // لوگو: دایره‌ی طلایی + آیکون
        val icBox = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFFF0C64B.toInt(), 0xFFB9861B.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(UI.dp(ctx, 1), UI.alpha(0xFFF7DE8A.toInt(), 0.7f))
            }
        }
        val ic = IconView(ctx, if (state == "closed") "gear" else "globe").apply {
            iconColor = 0xFF1A1304.toInt(); bold = true
        }
        icBox.addView(ic, FrameLayout.LayoutParams(UI.dp(ctx, 34), UI.dp(ctx, 34), Gravity.CENTER))
        gateRoot.addView(icBox, LinearLayout.LayoutParams(
            UI.dp(ctx, 86), UI.dp(ctx, 86)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // نام برنامه: Vex (روشن) + Client (آبی)
        val nameRow = UI.row(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 16), 0, 0)
        }
        nameRow.addView(TextView(ctx).apply {
            text = "Vex"
            textSize = 24f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(0xFFF5F7FF.toInt())
        })
        nameRow.addView(TextView(ctx).apply {
            text = " Client"
            textSize = 18f
            typeface = Fonts.bold(ctx)
            setTextColor(p.accent)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        gateRoot.addView(nameRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // عنوان و پیام هر حالت
        val title = TextView(ctx).apply {
            textSize = 17f; typeface = Fonts.bold(ctx); gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 26), 0, 0)
            when (state) {
                "no-internet" -> { text = "دسترسی به اینترنت نداری"; setTextColor(p.bad) }
                "closed" -> { text = "دسترسی برنامه بسته شده است"; setTextColor(p.bad) }
                else -> { text = "در حال بررسی اتصال…"; setTextColor(p.text) }
            }
        }
        gateRoot.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val msg = TextView(ctx).apply {
            textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(p.muted)
            setPadding(0, UI.dp(ctx, 8), 0, 0)
            setLineSpacing(UI.dp(ctx, 3).toFloat(), 1f)
            when (state) {
                "no-internet" -> text = "برای ورود به Vex باید اینترنت گوشی روشن باشد.\nبعد از وصل‌شدن، دوباره تلاش کن."
                "closed" -> text = gateReason?.takeIf { it.isNotBlank() }
                        ?: "یا برنامه در حال به‌روزرسانی است یا مشکل فنی موقت پیش آمده است.\nلطفاً کمی بعد دوباره تلاش کن؛ خبرها در کانال تلگرام منتشر می‌شود."
                else -> text = "چند لحظه صبر کن…"
            }
        }
        gateRoot.addView(msg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // اسپینر حالت بررسی
        if (state == "checking") {
            val sp = android.widget.ProgressBar(ctx)
            sp.indeterminateTintList = android.content.res.ColorStateList.valueOf(p.accent)
            gateRoot.addView(sp, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = UI.dp(ctx, 24)
            })
        }

        // دکمه‌ها
        val btns = UI.row(ctx).apply { gravity = Gravity.CENTER; setPadding(0, UI.dp(ctx, 26), 0, 0) }
        if (state == "no-internet" || state == "closed") {
            val retry = UI.softButton(ctx, "تلاش دوباره", p)
            retry.setOnClickListener { runGate() }
            btns.addView(retry, LinearLayout.LayoutParams(
                UI.dp(ctx, 148), UI.dp(ctx, 46)).apply { marginEnd = UI.dp(ctx, 10) })
        }
        if (state == "closed") {
            val tg = UI.softButton(ctx, "کانال تلگرام", p)
            tg.setOnClickListener { openTelegramChannel() }
            btns.addView(tg, LinearLayout.LayoutParams(
                UI.dp(ctx, 148), UI.dp(ctx, 46)))
        }
        if (btns.childCount > 0) {
            gateRoot.addView(btns, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onBackPressed() {
        if (LockScreen.isActive) return // صفحه‌ی قفل/رمز: Back بی‌اثر
        if (currentTab != 0) switchTab(0) else super.onBackPressed()
    }
}
