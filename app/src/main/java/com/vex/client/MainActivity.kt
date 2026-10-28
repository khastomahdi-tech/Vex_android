package com.vex.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var pendingConfig: String? = null

    // ۱) اجازه‌ی نوتیفیکیشن (فقط وقتی کاربر اولین بار دکمه‌ی پاور را می‌زند)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // بعد از پاسخ کاربر، اتصال ادامه پیدا می‌کند (حتی اگر نوتیفیکیشن رد شود)
        pendingConfig?.let { cfg -> pendingConfig = null; continueStart(cfg) }
    }

    // ۲) اجازه‌ی VPN (پنجره‌ی سیستمی اندروید)
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cfg = pendingConfig
        pendingConfig = null
        if (res.resultCode == RESULT_OK && cfg != null) startVpnService(cfg)
        else notifyState("error", "دسترسی VPN داده نشد")
    }

    // ۳) اسکن QR با دوربین (اکتیویتی نیتیو CameraX + ML Kit)
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        else pushQrError("دسترسی دوربین داده نشد")
    }
    private val qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringExtra(QrScanActivity.EXTRA_TEXT)
        val err = res.data?.getStringExtra("error")
        when {
            !text.isNullOrBlank() -> pushQr(text)
            !err.isNullOrBlank() -> pushQrError(err)
            // لغو توسط کاربر: بدون پیام خطا
        }
    }

    // ۴) انتخاب عکس QR از گالری
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) decodeQrFromUri(uri) else pushQrError("عکسی انتخاب نشد")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(VexBridge(this), "VexNative")
        web.loadUrl("file:///android_asset/index.html")

        VexVpnService.stateListener = { st, msg -> runOnUiThread { notifyState(st, msg) } }
        VexVpnService.statsListener = { dn, up, tdn, tup -> runOnUiThread { pushStats(dn, up, tdn, tup) } }
    }

    /** وقتی کاربر دکمه‌ی پاور را می‌زند */
    fun requestStart(config: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingConfig = config
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueStart(config)
        }
    }

    private fun continueStart(config: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConfig = config
            vpnPermission.launch(intent)
        } else {
            startVpnService(config)
        }
    }

    fun requestStop() {
        startService(Intent(this, VexVpnService::class.java).setAction(VexVpnService.ACTION_STOP))
    }

    private fun startVpnService(config: String) {
        val i = Intent(this, VexVpnService::class.java)
            .setAction(VexVpnService.ACTION_START)
            .putExtra(VexVpnService.EXTRA_CONFIG, config)
        ContextCompat.startForegroundService(this, i)
    }

    // ------------------------------------------------ اسکن QR (دوربین + گالری)

    /** ورود به اسکنر دوربین (دسترسی دوربین گرفته می‌شود) */
    fun startQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** انتخاب عکس QR از گالری */
    fun startQrGallery() {
        try {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            pushQrError("گالری در دسترس نیست")
        }
    }

    /** خواندن و دیکود کردن QR از روی عکس گالری (پس‌زمینه + ML Kit) */
    private fun decodeQrFromUri(uri: Uri) {
        Thread {
            try {
                val bitmap = loadBitmapForQr(uri)
                if (bitmap == null) {
                    runOnUiThread { pushQrError("خواندن عکس ناموفق بود") }
                    return@Thread
                }
                val rotation = exifRotation(uri)
                runOnUiThread {
                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    scanner.process(InputImage.fromBitmap(bitmap, rotation))
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull { it.rawValue != null }?.rawValue
                            if (value != null) pushQr(value)
                            else pushQrError("QR کدی در این عکس پیدا نشد")
                        }
                        .addOnFailureListener { pushQrError("تحلیل عکس ناموفق بود") }
                }
            } catch (e: Exception) {
                runOnUiThread { pushQrError("خواندن عکس ناموفق بود") }
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

    private fun exifRotation(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
    }

    // ------------------------------------------------ پاسخ به صفحه‌ی وب

    fun notifyState(st: String, msg: String) {
        web.evaluateJavascript("window.vexOnState && window.vexOnState('$st', ${JSONObject.quote(msg)})", null)
    }

    fun pushStats(dn: Long, up: Long, tdn: Long, tup: Long) {
        web.evaluateJavascript("window.vexOnStats && window.vexOnStats($dn,$up,$tdn,$tup)", null)
    }

    /** نتیجه‌ی پینگ واقعی یک سرور */
    fun pushPing(id: Int, ms: Int) {
        runOnUiThread {
            web.evaluateJavascript("window.vexOnPing && window.vexOnPing($id, $ms)", null)
        }
    }

    /** نتیجه‌ی موفق اسکن QR */
    fun pushQr(text: String) {
        runOnUiThread {
            web.evaluateJavascript("window.vexOnQr && window.vexOnQr(${JSONObject.quote(text)})", null)
        }
    }

    /** خطای اسکن QR */
    fun pushQrError(msg: String) {
        runOnUiThread {
            web.evaluateJavascript("window.vexOnQrError && window.vexOnQrError(${JSONObject.quote(msg)})", null)
        }
    }

    override fun onDestroy() {
        VexVpnService.stateListener = null
        VexVpnService.statsListener = null
        super.onDestroy()
    }
}
