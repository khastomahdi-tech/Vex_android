package com.vex.client

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * کاشی «VPN» در کشوی Quick Settings (جای دکمه‌های حالت پرواز/اینترنت):
 *  • آیکون: حرف بزرگ V — زیرش برچسب «VPN»
 *  • لمس → برنامه باز می‌شود و بلافاصله به آخرین سرورِ انتخاب‌شده (یا پیش‌فرضِ
 *    بهترین‌پینگ) وصل می‌شود — همان مسیر عادی برنامه: دروازه چک می‌شود،
 *    اجازه‌ی VPN بار اول گرفته می‌شود و بعد تونل بالا می‌آید.
 */
class VexTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        VexVpnService.tileListener = { _ -> updateTile() }
        updateTile()
    }

    override fun onStopListening() {
        if (VexVpnService.tileListener != null) VexVpnService.tileListener = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val launch = Runnable {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val pi = PendingIntent.getActivity(
                        this, 4101, i,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(i)
                }
            } catch (_: Throwable) {}
        }
        try {
            if (Build.VERSION.SDK_INT >= 22 && isLocked) unlockAndRun(launch) else launch.run()
        } catch (_: Throwable) {
            try { launch.run() } catch (_: Throwable) {}
        }
    }

    /** وضعیت کاشی: متصل = سبز روشن، در حال اتصال = روشن، قطع = خاموش */
    private fun updateTile() {
        val t = qsTile ?: return
        try {
            t.label = "VPN"
            t.icon = Icon.createWithResource(this, R.drawable.ic_tile_v)
            when (VexVpnService.uiState) {
                "connected" -> { t.state = Tile.STATE_ACTIVE; t.subtitle = "متصل" }
                "connecting" -> { t.state = Tile.STATE_ACTIVE; t.subtitle = "در حال اتصال…" }
                else -> { t.state = Tile.STATE_INACTIVE; t.subtitle = "قطع" }
            }
            t.updateTile()
        } catch (_: Throwable) {}
    }
}
