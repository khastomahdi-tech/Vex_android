package com.vex.client

import android.webkit.JavascriptInterface

/** پلی بین صفحه‌ی HTML و کد اندروید: window.VexNative */
class VexBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun start(configJson: String) {
        activity.runOnUiThread { activity.requestStart(configJson) }
    }

    @JavascriptInterface
    fun stop() {
        activity.runOnUiThread { activity.requestStop() }
    }

    @JavascriptInterface
    fun version(): String = "0.6.0"
}
