package com.vex.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
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

    fun notifyState(st: String, msg: String) {
        web.evaluateJavascript("window.vexOnState && window.vexOnState('$st', ${JSONObject.quote(msg)})", null)
    }

    fun pushStats(dn: Long, up: Long, tdn: Long, tup: Long) {
        web.evaluateJavascript("window.vexOnStats && window.vexOnStats($dn,$up,$tdn,$tup)", null)
    }

    override fun onDestroy() {
        VexVpnService.stateListener = null
        VexVpnService.statsListener = null
        super.onDestroy()
    }
}
