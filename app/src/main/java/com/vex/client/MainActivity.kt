package com.vex.client

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
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

        /**
         * اکسترا «اتصال فوری» — از کاشی VPN در Quick Settings و اعلان «برنامه بسته شد»
         * می‌آید؛ برنامه بعد از گذر از دروازه، خودش به آخرین/پیش‌فرض سرور وصل می‌شود.
         */
        const val EXTRA_AUTO_CONNECT = "auto_connect"

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
    lateinit var accountUi: AccountUi
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
    private lateinit var screenAccount: View

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

    /** شناسه‌ی سرور رایگانِ اتصال فعلی در D1 ربات — برای گزارش مصرف تفکیکی هر سرور (۰ = شخصی/نامشخص) */
    private var sessionSrv = 0L

    /** اتصال خودکار پس از ورود با کاشی VPN یا اعلان «برنامه بسته شد» */
    private var pendingAutoConnect = false

    /** نسخه‌ی حداقل مدیر (min_ver) — اگر از نسخه‌ی فعلی بالاتر بود، بنر «نسخه‌ی جدید» می‌آید */
    private var pendingUpdateVer = 0

    /** دلیل قفلِ صفحه‌ی اشتراک (حذف شدن / منقضی شدن / کد نامعتبر) */
    private var subLockReason: String? = null

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
    private class NavItem(val item: LinearLayout, val icon: IconView, val label: TextView, val tab: Int)
    private val navItems = ArrayList<NavItem>(4)
    var currentTab = 0
        private set

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

    /** اجازه‌ی اعلان‌ها (اندروید ۱۳+) — تا سه بار در ورودهای مختلف پرسیده می‌شود */
    private fun askNotifPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return
            val prefs = getSharedPreferences("vex_gate", MODE_PRIVATE)
            val n = prefs.getInt("notif_ask_count", 0)
            if (n >= 3) return
            prefs.edit().putInt("notif_ask_count", n + 1).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Throwable) {}
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
        // دزدهای امنیتی: اگر APK دستکاری شده باشد (امضای متفاوت) اصلاً وارد نمی‌شود
        pendingAutoConnect = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) ?: false
        setContentView(buildGateView())
        if (!IntegrityGate.isOfficial(this)) {
            setGateUi("tampered")
            return
        }
        setGateUi("checking")
        runGate()
    }

    /** لمس کاشی VPN وقتی برنامه از قبل باز است */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) {
            pendingAutoConnect = true
            if (::root.isInitialized) maybeAutoConnect()
        }
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
            // اینترنت هست → اول وضعیت ربات (کلادفلر) چک می‌شود؛ اگر مدیر برنامه را
            // بسته باشد کاربر اصلاً وارد نمی‌شود و همین‌جا پیام انگلیسی می‌بیند.
            var info = TelegramGate.notifyInstall(this) ?: TelegramGate.gateInfo()
            TelegramGate.ackIfChanged(info.gate, this)
            // اعلام «نسخه‌ی جدید» — مدیر در پنل/ربات min_ver را بالاتر از این نسخه گذاشته باشد
            pendingUpdateVer = if (info.minVer > TelegramGate.selfVersionCode(this).toInt()) info.minVer else 0
            // نسخه‌های بسته‌شده توسط مدیر — اگر نسخه‌ی همین برنامه در لیست باشد، ورود قفل می‌شود
            if (info.gate == TelegramGate.OPEN && TelegramGate.selfVersionIsBlocked(info, this)) {
                info = TelegramGate.GateInfo(
                    TelegramGate.CLOSED,
                    "This version of Vex has been disabled. Please update to the latest version."
                )
            }
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
            } else {
                // ---- اشتراک: بدون توکنِ فعال (۳۰ حرف انگلیسی / ۲۷ حرفِ قدیمی)، برنامه وارد نمی‌شود ----
                val sc = SubStore.code(this)
                if (!SubStore.valid(sc)) {
                    subLockReason = null
                    main.post { if (!isFinishing) setGateUi("subscription") }
                    return@Thread
                }
                val sub = TelegramGate.subStatus(sc, TelegramGate.deviceId(this))
                if (sub.kicked || sub.expired || sub.notFound) {
                    // فقط ردِ صریح سرور بیرون می‌اندازد؛ خطای شبکه (error=null) باز است
                    SubStore.clear(this)
                    subLockReason = when {
                        sub.kicked -> "نشست شما توسط مدیریت حذف شده است. کد اشتراک را دوباره وارد کن."
                        sub.expired -> "کد اشتراک شما منقضی شده است. برای گرفتن کد جدید، وارد ربات شو."
                        else -> "این کد اشتراک دیگر معتبر نیست. یک کد معتبر وارد کن."
                    }
                    main.post { if (!isFinishing) setGateUi("subscription") }
                    return@Thread
                }
                if (sub.ok) {
                    // انقضای تازه از سرور ذخیره می‌شود تا زمان باقی‌مانده دقیق بماند
                    SubStore.save(this, sub.code, sub.createdAt, sub.expires,
                        sub.device.ifEmpty { SubStore.device(this) })
                }
                main.post { if (!isFinishing) enterApp() }
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
                val blockedInfo = blockedHere(info)
                if (info.gate == TelegramGate.CLOSED || blockedInfo != null) {
                    gateReason = blockedInfo?.reason ?: info.reason
                    TelegramGate.ackIfChanged(TelegramGate.CLOSED, this)
                    main.post {
                        if (!isFinishing) {
                            requestStop()
                            setGateUi("closed")
                        }
                    }
                } else {
                    // ---- چک دوره‌ای اشتراک: حذف/انقضا وسط کار هم بیرون می‌اندازد ----
                    val sc = SubStore.code(this@MainActivity)
                    if (SubStore.valid(sc)) {
                        val sub = TelegramGate.subStatus(sc, TelegramGate.deviceId(this@MainActivity))
                        if (sub.kicked || sub.expired || sub.notFound) {
                            SubStore.clear(this@MainActivity)
                            subLockReason = when {
                                sub.kicked -> "نشست شما توسط مدیریت حذف شده است. کد اشتراک را دوباره وارد کن."
                                sub.expired -> "کد اشتراک شما منقضی شده است. برای گرفتن کد جدید، وارد ربات شو."
                                else -> "این کد اشتراک دیگر معتبر نیست. یک کد معتبر وارد کن."
                            }
                            main.post {
                                if (!isFinishing) {
                                    requestStop()
                                    setGateUi("subscription")
                                }
                            }
                            return@Thread
                        }
                    }
                    main.post { startGateWatch() }
                }
            }.start()
        }, GATE_WATCH_INTERVAL_MS)
    }

    /** چک «نسخه‌ی این دستگاه بسته شده است» — هم در ورود و هم در تماشای دوره‌ای */
    private fun blockedHere(info: TelegramGate.GateInfo): TelegramGate.GateInfo? {
        if (info.gate == TelegramGate.OPEN && TelegramGate.selfVersionIsBlocked(info, this)) {
            return TelegramGate.GateInfo(
                TelegramGate.CLOSED,
                "This version of Vex has been disabled. Please update to the latest version."
            )
        }
        return null
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
            accountUi = AccountUi(this)
            screenAccount = accountUi.build()
            body.addView(screenAccount, FrameLayout.LayoutParams(
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
                val sel = store.selected()
                sessionIsFree = sel != null && store.isFree(sel)
                sessionSrv = if (sel != null && store.isFree(sel)) sel.remoteId else 0L
            }

            VexVpnService.stateListener = { st, msg -> main.post { onServiceState(st, msg) } }
            VexVpnService.statsListener = { dn, up, tdn, tup -> main.post {
                TrafficStore.addSession(tdn, tup, sessionIsFree, sessionSrv)
                homeUi.onStats(dn, up, tdn, tup)
                statsUi.onStats(dn, up, tdn, tup)
            } }
            VexVpnService.netListener = { ok, ms, msg -> main.post { onNetResult(ok, ms, msg) } }

            applyThemeAll()
            renderAll()
            homeUi.restoreStats()

            // بنر «نسخه‌ی جدید منتشر شده» — اگر مدیر حداقل نسخه را بالا برده باشد
            if (pendingUpdateVer > 0) homeUi.showUpdateBanner(pendingUpdateVer)

            // به‌روزرسانی خودکار سرورهای رایگان
            scheduleFreeAutoRefresh()
            Thread { refreshFreeServers(silent = true) }.start()

            // اعلان‌های تازه‌ی مدیر (اگر هنگام بسته‌بودن برنامه فرستاده شده باشند)
            Thread { Notifier.fetchAndShow(this) }.start()
            // ضربان پس‌زمینه‌ی اعلان‌ها — حتی وقتی برنامه کاملاً بسته است، سیستم هر ۱۵ دقیقه بیدار می‌کند
            NotifyWorker.ensure(this)
            // تیک مستقل اعلان‌ها — حتی اگر «به‌روزرسانی خودکار سرورها» خاموش باشد هم فعال است
            main.removeCallbacks(notifTick)
            main.post(notifTick)

            // دسترسی اعلان — بار اول می‌پرسد؛ اگر رد شد دفعه‌های بعدی ورود دوباره می‌پرسد
            askNotifPermissionOnce()

            // پیش‌فرض اولین ورود — بهترین پینگ بین سرورهای رایگان (اگر چیزی انتخاب نباشد)
            ensureDefaultServer()

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
                maybeAutoConnect()
            }
            return
        }
        setContentView(root)
        applyThemeAll()
        maybeAutoConnect()
    }

    override fun onDestroy() {
        main.removeCallbacks(notifTick)
        VexVpnService.stateListener = null
        VexVpnService.statsListener = null
        VexVpnService.netListener = null
        // وقتی کاربر برنامه را می‌بندد، اعلان دوستانه در شید می‌ماند تا یادش نرود
        if (isFinishing && !isChangingConfigurations && ::root.isInitialized) {
            val vpnActive = connectionState == "connected" || connectionState == "connecting" ||
                    VexVpnService.uiState == "connected" || VexVpnService.uiState == "connecting"
            Notifier.showAppClosed(this, vpnActive)
        }
        super.onDestroy()
    }

    // ---------------- قفل دوباره پس از ترک برنامه ----------------

    /** آخرین زمانی که برنامه از دید خارج شد (برای قفل دوباره) */
    private var pausedAt = 0L

    override fun onPause() {
        super.onPause()
        if (pinUnlocked) pausedAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        try {
            if (!::root.isInitialized || pausedAt == 0L) return
            if (store.settings.pinHash.isEmpty() || !pinUnlocked || !store.settings.relockOnExit) return
            if (LockScreen.isActive) return
            val away = android.os.SystemClock.elapsedRealtime() - pausedAt
            pausedAt = 0
            // دیالوگ‌های سریع (اجازه‌ها) قفل دوباره نمی‌گیرند — فقط رفتن واقعی از برنامه
            if (away <= 10_000L) return
            pinUnlocked = false
            LockScreen.showLock(this) {
                pinUnlocked = true
                setContentView(root)
                applyThemeAll()
                renderAll()
                homeUi.restoreStats()
            }
        } catch (_: Throwable) {}
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
            typeface = Fonts.bold(ctx)
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

        /** نوار پایین چهارتیکه با آیکون: خانه / سرورها / آمار / حساب */
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
            Triple("آمار", "chart", 2),
            Triple("حساب کاربری", "user", 4)
        )
        for ((lbl, ic, idx) in defs) {
            val item = UI.col(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(UI.dp(ctx, 10), UI.dp(ctx, 7), UI.dp(ctx, 10), UI.dp(ctx, 7))
            }
            val iv = IconView(ctx, ic)
            val tv = UI.label(ctx, lbl, 11f, 0xFF9AA3B2.toInt(), bold = true).apply {
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
            navItems.add(NavItem(item, iv, tv, idx))
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
        screenAccount.visibility = if (idx == 4) View.VISIBLE else View.GONE
        for (n in navItems) {
            val active = n.tab == idx
            val p = Themes.resolve(this, store.settings.theme)
            // قرص ملایم اکسنت برای آیتم فعال — تمیز و بدون گرادیان طلایی
            n.item.background = if (active) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = UI.dp(this@MainActivity, 16).toFloat()
                setColor(UI.alpha(p.accent, 0.14f))
            } else null
            n.icon.iconColor = if (active) p.accent else p.muted
            n.label.setTextColor(if (active) p.accent else p.muted)
        }
        if (idx == 1) serversUi.render()
        if (idx == 2) statsUi.render()
        if (idx == 3) settingsUi.refresh()
        if (idx == 4) accountUi.onOpen()
    }

    fun gotoServers() = switchTab(1)

    /** بازکردن تنظیمات از دکمه‌ی گیر بالای صفحه */
    fun gotoSettings() = switchTab(3)

    /** دانلود APK — اول دامنه‌ی شخصی (میرور کلادفلر)، بعد pages.dev */
    fun openApkDownload() {
        for (u in arrayOf("https://demonvpn.shop/apk", "https://vex-gate.pages.dev/apk")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                return
            } catch (_: Throwable) {}
        }
        UI.toast(this, "مرورگر در دسترس نیست")
    }

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
            4 -> if (::screenAccount.isInitialized) screenAccount else return
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
        openTelegramLink(TELEGRAM_CHANNEL_URL)
    }

    /** بازکردن هر لینک تلگرامی (ربات/کانال): اول اپ تلگرام (tg://) و اگر نبود مرورگر */
    private fun openTelegramLink(url: String) {
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

    /** دکمه‌ی «ورود به ربات» — رباتی که کد اشتراک می‌دهد (لینکش از سرور می‌آید) */
    fun openVexBot() {
        openTelegramLink(TelegramGate.lastBotUrl)
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
        accountUi.applyTheme(p)
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
     * بین تم روشن تمیز و تیره‌ی تمیز جابه‌جا می‌شود.
     */
    fun toggleDayNight() {
        val cur = store.settings.theme
        val next = when (cur) {
            "light" -> "dark"
            "dark" -> "light"
            else ->
                if (Themes.isLightBg(Themes.resolve(this, cur))) "dark" else "light"
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
            // چند دور تلاش — inbound هسته گاهی چند ثانیه بعد از اتصال آماده می‌شود.
            var info: ExitInfo? = null
            for (round in 1..3) {
                if (Thread.currentThread().isInterrupted) return@Thread
                info = try { NetUtil.fetchExitInfo(throughTunnel = true) } catch (_: Throwable) { null }
                if (info != null || connectionState != "connected") break
                if (round < 3) try { Thread.sleep(3500) } catch (_: InterruptedException) { return@Thread }
            }
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
                // موتور سایفون: به سرور نیاز نیست — کانفیگ شبه‌ساز می‌آید
                if (store.settings.engine == "psiphon") {
                    requestStart(Server(-99, "سایفون", "psiphon", "psiphon://engine"))
                    return
                }
                // هیچ سروری انتخاب نشده → بهترین سرور رایگان (پیش‌فرض) خودکار انتخاب می‌شود
                val s = store.selected() ?: ensureDefaultServer()
                if (s == null) {
                    UI.toast(this, "اول یک سرور انتخاب کن")
                    switchTab(1)
                } else requestStart(s)
            }
        }
    }

    /**
     * اتصال خودکارِ درخواستی از کاشی VPN / اعلان «برنامه بسته شد»:
     * به آخرین سرورِ انتخاب‌شده وصل می‌شود؛ اگر هیچ انتخابی نبود،
     * پیش‌فرضِ بهترین‌پینگ بین سرورهای رایگان ساخته می‌شود.
     */
    private fun maybeAutoConnect() {
        if (!pendingAutoConnect) return
        pendingAutoConnect = false
        if (!::root.isInitialized || connectionState != "stopped") return
        val s = store.selected() ?: ensureDefaultServer() ?: run {
            // موتور سایفون به سرور نیاز ندارد — کانفیگ شبه‌ساز
            if (store.settings.engine == "psiphon") Server(-99, "سایفون", "psiphon", "psiphon://engine") else null
        } ?: return
        main.postDelayed({
            if (connectionState == "stopped") requestStart(s)
        }, 350)
    }

    /**
     * پیش‌فرض اولین ورود: بهترین سرور رایگان (کم‌ترین پینگ پاسخ‌گو) انتخاب و ذخیره
     * می‌شود تا کاربر تازه‌وارد بدون هیچ تنظیمی فقط پاور را بزند و وصل شود.
     * اگر کاربر بعداً سرور دیگری انتخاب کند، انتخابش محترم است.
     */
    private fun ensureDefaultServer(): Server? {
        if (!::store.isInitialized) return null
        if (store.selected() != null) return store.selected()
        val alive = store.freeServers.filter { it.ping >= 0 }
        val pool = alive.ifEmpty { store.freeServers }
        val best = pool.minByOrNull { if (it.ping >= 0) it.ping else Int.MAX_VALUE } ?: return null
        store.select(best.id)
        main.post {
            if (::homeUi.isInitialized) {
                homeUi.renderServerCard()
                serversUi.render()
            }
        }
        UI.toast(this, "سرور پیش‌فرض فعال شد: «${best.name}»")
        return best
    }

    fun requestStart(s: Server) {
        if (connectionState != "stopped") return
        // حالت per-app: اگر «فقط برنامه‌های انتخابی» خالی باشد اجازه‌ی اتصال نده
        if (store.settings.perAppMode == 1 && store.settings.perAppPackages.isEmpty()) {
            UI.toast(this, "اول برنامه‌های موردنظر را در تنظیمات انتخاب کن")
            gotoSettings()
            return
        }
        val payload0 = store.connectionPayload(s)
        // موتور اتصال — «سایفون» ترافیک را به تونل رسمی Psiphon می‌فرستد، نه به سرور انتخابی
        // «ترکیبی» — همان سرور انتخابی، ولی اتصال به آن از داخل تونل سایفون عبور می‌کند (سرعت/پایداری بیشتر)
        val payload = when (store.settings.engine) {
            "psiphon" -> try { org.json.JSONObject(payload0).put("engine", "psiphon").toString() } catch (_: Throwable) { payload0 }
            "hybrid" -> try { org.json.JSONObject(payload0).put("engine", "hybrid").toString() } catch (_: Throwable) { payload0 }
            else -> payload0
        }
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
        // تفکیک آمار مصرف: این جلسه روی سرور رایگان است یا شخصی؟ (+ شناسه‌ی سرور رایگان برای گزارش مصرف هر سرور)
        val selSrv = store.selected()
        sessionIsFree = selSrv != null && store.isFree(selSrv)
        sessionSrv = if (selSrv != null && store.isFree(selSrv)) selSrv.remoteId else 0L
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
                    serversUi.freeProgressMsg = "هیچ سرور رایگانی ثبت نشده است"
                    if (serversUi.tab == 1) serversUi.render()
                }
            }
            freeFetching = false
            main.post {
                serversUi.freeNetFail = !fetchOk // به بخش رایگان بگو سرور مدیریت در دسترس بود یا نه
                if (fetchOk) {
                    store.replaceFreeServers(alive)
                    // اولین انتخاب پیش‌فرض: اگر کاربر هنوز هیچ سروری انتخاب نکرده باشد،
                    // بهترین پینگ بین سرورهای تازه‌ی رایگان خودکار فعال می‌شود
                    ensureDefaultServer()
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
            Thread {
                refreshFreeServers(silent = true)
                // اعلان‌های تازه‌ی مدیر هم در همین تیک ۶۰ ثانیه‌ای بررسی می‌شود
                Notifier.fetchAndShow(this@MainActivity)
            }.start()
            scheduleFreeAutoRefresh()
        }
    }

    /** حلقه‌ی همگانی اعلان‌های مدیر — مستقل از تنظیم به‌روزرسانی سرورها، هر ۴۵ ثانیه */
    private val notifTick = object : Runnable {
        override fun run() {
            Thread { Notifier.fetchAndShow(this@MainActivity) }.start()
            main.postDelayed(this, 45_000L)
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

    /** برچسب نسخه برای ردیف «نسخه‌ی برنامه» — واقعی از PackageInfo، نه هاردکد */
    fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "v${pi.versionName} ($code)"
    } catch (_: Throwable) { "v?" }

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

    /** صفحه‌ی قفل ورود — آیکون برنامه با گوشه‌های گرد + وضعیت اتصال */
    private fun buildGateView(): View {
        val ctx = this
        gateRoot = UI.col(ctx).apply { gravity = Gravity.CENTER }
        gateBuilt = true
        return gateRoot
    }

    /**
     * حالت‌های قفل: checking / no-internet / closed / tampered
     * حالت «checking» فقط آیکون برنامه را نشان می‌دهد — هیچ متنی زیرش نیست.
     */
    private fun setGateUi(state: String) {
        if (!gateBuilt) return
        val ctx = this
        // صفحه‌ی کد اشتراک: پس‌زمینه‌ی سفید (درخواست کاربر) — بقیه‌ی حالت‌ها تیره
        val sub = state == "subscription"
        val p = if (sub) Themes.light().copy(bg = 0xFFFFFFFF.toInt()) else Themes.dark()
        // اگر برنامه باز بوده (قفل وسط کار) → صفحه‌ی قفل جایش را می‌گیرد
        setContentView(gateRoot)
        gateRoot.setBackgroundColor(p.bg)
        gateRoot.removeAllViews()

        // صفحه‌ی «تأیید حساب کاربری» — طرح جدید مستقل مطابق مرجع (بدون آیکون و عنوان قدیمی)
        if (state == "subscription") {
            buildSubscriptionForm(ctx, p)
            return
        }

        // آیکون برنامه با گوشه‌های دایره‌مانند
        val ic = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
                }
            }
        }
        gateRoot.addView(ic, LinearLayout.LayoutParams(
            UI.dp(ctx, 112), UI.dp(ctx, 112)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // «در حال اتصال به برنامه» حذف شد — حالت checking فقط آیکون را نشان می‌دهد
        if (state == "checking") return

        // وضعیت / پیام زیر آیکون (فقط حالت‌های خطا)
        val title = TextView(ctx).apply {
            textSize = 16.5f; typeface = Fonts.bold(ctx); gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 28), 0, 0)
            when (state) {
                "no-internet" -> { text = "دسترسی به اینترنت نداری"; setTextColor(p.bad) }
                "closed" -> { text = "App Access Closed"; setTextColor(p.bad) }
                else -> { text = "این نسخه از Vex معتبر نیست"; setTextColor(p.bad) }
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
                        ?: "The admin has closed the app for now. Please try again later."
                "tampered" -> text = "فایل برنامه تغییر کرده یا امضای آن با نسخه‌ی رسمی نمی‌خواند.\nبرای امنیتت، این نسخه اجرا نمی‌شود.\nنسخه‌ی رسمی را فقط از کانال تلگرام یا لینک demonvpn.shop/apk یا vex-gate.pages.dev/apk نصب کن."
                else -> text = ""
            }
        }
        gateRoot.addView(msg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // دکمه‌ها
        val btns = UI.row(ctx).apply { gravity = Gravity.CENTER; setPadding(0, UI.dp(ctx, 26), 0, 0) }
        if (state == "no-internet" || state == "closed") {
            val retry = UI.softButton(ctx, if (state == "closed") "Try Again" else "تلاش دوباره", p)
            retry.setOnClickListener { runGate() }
            btns.addView(retry, LinearLayout.LayoutParams(
                UI.dp(ctx, 148), UI.dp(ctx, 46)).apply { marginEnd = UI.dp(ctx, 10) })
        }
        if (state == "closed" || state == "tampered") {
            val tg = UI.softButton(ctx, if (state == "closed") "Telegram Channel" else "کانال تلگرام", p)
            tg.setOnClickListener { openTelegramChannel() }
            btns.addView(tg, LinearLayout.LayoutParams(
                UI.dp(ctx, 148), UI.dp(ctx, 46)))
        }
        if (btns.childCount > 0) {
            gateRoot.addView(btns, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** صفحه‌ی «تأیید حساب کاربری» — دقیقاً مو‌به‌مو مطابق طرح مرجع:
     *  عنوان بالا، دایره‌ی روشن‌آبی با آیکون کلید، «تأیید از طریق تلگرام»،
     *  توضیح کوتاه، کادر کد با آیکون اثر انگشت، دکمه‌ی آبی «ورود» و
     *  دکمه‌ی «دریافت کد اشتراک» با آیکون تلگرام (ربات توکن @Appvexvpnbot).
     *  متن «برای تلگرام به پروکسی نیاز دارید؟» و دکمه‌ی کانال طبق درخواست حذف شدند. */
    private fun buildSubscriptionForm(ctx: MainActivity, p: Palette) {
        val navy = 0xFF17233D.toInt()
        val gray = 0xFF5F6B7A.toInt()
        val blue = 0xFF2F6BE4.toInt()
        val blueSoft = 0xFFE4EDFC.toInt()
        val hintGray = 0xFF98A2B3.toInt()
        val errRed = 0xFFD14343.toInt()
        val white = 0xFFFFFFFF.toInt()

        fun box(fill: Int, stroke: Int, strokeDp: Float, radiusDp: Float): GradientDrawable {
            val d = GradientDrawable()
            d.shape = GradientDrawable.RECTANGLE
            d.setColor(fill)
            if (strokeDp > 0f) d.setStroke(Math.max(1, UI.dp(ctx, strokeDp)), stroke)
            d.cornerRadius = UI.dp(ctx, radiusDp).toFloat()
            return d
        }

        val col = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 20), UI.dp(ctx, 22), UI.dp(ctx, 20), UI.dp(ctx, 16))
        }

        // ---- عنوان بالا ----
        col.addView(TextView(ctx).apply {
            text = "تأیید حساب کاربری"
            textSize = 20f; typeface = Fonts.bold(ctx); gravity = Gravity.CENTER
            setTextColor(navy)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- دایره‌ی روشن‌آبی با آیکون کلید ----
        val circle = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(blueSoft)
            }
        }
        circle.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_key_blue)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(UI.dp(ctx, 96), UI.dp(ctx, 96)))
        val circleWrap = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        circleWrap.addView(circle, LinearLayout.LayoutParams(UI.dp(ctx, 188), UI.dp(ctx, 188)))
        col.addView(circleWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 44), 0, 0)
        })

        // ---- «تأیید از طریق تلگرام» ----
        col.addView(TextView(ctx).apply {
            text = "تأیید از طریق تلگرام"
            textSize = 25f; typeface = Fonts.bold(ctx); gravity = Gravity.CENTER
            setTextColor(navy)
            setPadding(0, UI.dp(ctx, 28), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- توضیح ----
        col.addView(TextView(ctx).apply {
            text = "کد اشتراک را از ربات تلگرام دریافت کرده و در کادر زیر وارد کنید."
            textSize = 15f; gravity = Gravity.CENTER; setTextColor(gray)
            setLineSpacing(UI.dp(ctx, 3).toFloat(), 1f)
            setPadding(UI.dp(ctx, 6), UI.dp(ctx, 10), UI.dp(ctx, 6), 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- پیام خطا (کیک/انقضا/کد نامعتبر) — پیش‌فرض مخفی ----
        val err = TextView(ctx).apply {
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(errRed)
            setLineSpacing(UI.dp(ctx, 2).toFloat(), 1f)
            setPadding(UI.dp(ctx, 4), UI.dp(ctx, 12), UI.dp(ctx, 4), 0)
            visibility = View.GONE
        }
        col.addView(err, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        subLockReason?.takeIf { it.isNotBlank() }?.let {
            err.text = it
            err.visibility = View.VISIBLE
        }

        // ---- کادر کد: [اثر انگشت] [کادر ورود] — اثر انگشت سمت راست داخل کادر ----
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = box(white, 0xFFD8DEE9.toInt(), 1.2f, 16f)
            setPadding(UI.dp(ctx, 16), 0, UI.dp(ctx, 16), 0)
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_fingerprint)
            setColorFilter(blue)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(UI.dp(ctx, 26), UI.dp(ctx, 26)))
        val input = android.widget.EditText(ctx).apply {
            hint = "کد اشتراک را وارد کنید"
            textSize = 15f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER_VERTICAL
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(35))
            setTextColor(navy)
            setHintTextColor(hintGray)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setTextIsSelectable(false)
            setPadding(UI.dp(ctx, 12), 0, UI.dp(ctx, 4), 0)
        }
        row.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 52)))
        col.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 56)).apply {
            setMargins(0, UI.dp(ctx, 30), 0, 0)
        })

        // ---- دکمه‌ی آبی «ورود» ----
        val go = TextView(ctx).apply {
            text = "ورود"
            textSize = 16.5f; typeface = Fonts.bold(ctx); gravity = Gravity.CENTER
            setTextColor(white)
            background = box(blue, 0, 0f, 16f)
        }
        go.setOnClickListener {
            val code = SubStore.normalize(input.text.toString())
            if (!SubStore.valid(code)) {
                err.text = "کد اشتراک باید ۳۰ حرف انگلیسی باشد"
                err.visibility = View.VISIBLE
                return@setOnClickListener
            }
            go.text = "در حال بررسی…"
            go.isEnabled = false
            err.visibility = View.GONE
            Thread {
                val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val info = TelegramGate.subActivate(code, device, TelegramGate.deviceId(ctx),
                    TelegramGate.selfVersionName(ctx))
                main.post {
                    if (isFinishing) return@post
                    when {
                        info.ok -> {
                            SubStore.save(ctx, info.code, info.createdAt, info.expires,
                                info.device.ifEmpty { device })
                            subLockReason = null
                            UI.toast(ctx, "اشتراک فعال شد ✓")
                            enterApp()
                            startGateWatch()
                        }
                        info.deviceTaken -> {
                            err.text = "این کد قبلاً روی دستگاه دیگری فعال شده است؛\nهر کد فقط روی یک دستگاه کار می‌کند. از ربات «حذف تمام نشست‌ها» را بزن و دوباره تلاش کن"
                            err.visibility = View.VISIBLE
                        }
                        info.notFound -> {
                            err.text = "این کد پیدا نشد؛ از ربات کد معتبر بگیر و دوباره وارد کن"
                            err.visibility = View.VISIBLE
                        }
                        info.expired -> {
                            err.text = "کد اشتراک شما منقضی شده است؛ از ربات کد جدید بگیر"
                            err.visibility = View.VISIBLE
                        }
                        info.badCode -> {
                            err.text = "کد اشتراک باید ۳۰ حرف انگلیسی باشد"
                            err.visibility = View.VISIBLE
                        }
                        info.error != null -> {
                            err.text = "فعال‌سازی انجام نشد؛ کد را چک کن و دوباره تلاش کن"
                            err.visibility = View.VISIBLE
                        }
                        else -> {
                            err.text = "دسترسی به سرور مدیریت نبود؛ اتصال اینترنت را چک کن و دوباره تلاش کن"
                            err.visibility = View.VISIBLE
                        }
                    }
                    go.text = "ورود"
                    go.isEnabled = true
                }
            }.start()
        }
        col.addView(go, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 54)).apply {
            setMargins(0, UI.dp(ctx, 26), 0, 0)
        })

        // ---- دکمه‌ی «دریافت کد اشتراک» با آیکون رسمی تلگرام (دایره‌ی آبی + هواپیمای سفید، بدون tint)
        //      لینک به ربات توکن برنامه (@Appvexvpnbot) ----
        val tgBtn = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = box(white, 0xFFC9D9F4.toInt(), 1.2f, 16f)
        }
        tgBtn.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_telegram)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(UI.dp(ctx, 22), UI.dp(ctx, 22)))
        tgBtn.addView(TextView(ctx).apply {
            text = "دریافت کد اشتراک"
            textSize = 15f; typeface = Fonts.bold(ctx); setTextColor(navy)
            setPadding(UI.dp(ctx, 9), 0, 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tgBtn.setOnClickListener { openVexBot() }
        col.addView(tgBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 52)).apply {
            setMargins(0, UI.dp(ctx, 14), 0, 0)
        })

        gateRoot.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** خروج از حساب — نشست این دستگاه از سرور حذف می‌شود، توکن محلی پاک و صفحه‌ی ورود کد می‌آید.
     *  ربات توکن هم از طرف سرور به کاربر «یک نشانه از روی حسابت حذف شد» را اطلاع می‌دهد. */
    fun logoutFromAccount() {
        val code = SubStore.code(this)
        val did = TelegramGate.deviceId(this)
        val hadSub = SubStore.valid(code)
        val androidInfo = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        Thread {
            if (hadSub) try { TelegramGate.subLogout(code, did, androidInfo) } catch (_: Throwable) {}
            main.post {
                if (isFinishing) return@post
                subLockReason = null
                requestStop()
                SubStore.clear(this)
                UI.toast(this, "از حساب خارج شدی ✓")
                if (gateBuilt) setGateUi("subscription")
            }
        }.start()
    }

    /** اشتراک حذف/منقضی شد (از تب حساب یا چک دوره‌ای) → تونل قطع و صفحه‌ی ورود کد می‌آید */
    fun onSubscriptionLost(reason: String) {
        subLockReason = reason
        if (!::root.isInitialized || !gateBuilt) return
        requestStop()
        setGateUi("subscription")
    }

    override fun onBackPressed() {
        if (LockScreen.isActive) return // صفحه‌ی قفل/رمز: Back بی‌اثر
        if (currentTab != 0) switchTab(0) else super.onBackPressed()
    }
}
