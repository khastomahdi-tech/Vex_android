package com.vex.client

import android.util.Log
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/**
 * پل بین برنامه و هسته‌ی واقعی sing-box (libbox).
 *
 * هسته به‌صورت بومی (Go → gomobile) داخل APK قرار دارد و همه‌ی پروتکل‌های
 * VLESS / VMess / Trojan / Shadowsocks (با پشتیبانی Reality، WebSocket، gRPC و ...)
 * را به‌صورت واقعی اجرا می‌کند.
 *
 * جریان اتصال:
 *  1) VexVpnService کانفیگ کامل sing-box را از ConfigBuilder می‌گیرد.
 *  2) newService() یک BoxService با PlatformInterface برنامه می‌سازد.
 *  3) هنگام start() هسته، تابع openTun() صدا زده می‌شود و fd تونل اندروید به هسته داده می‌شود.
 *  4) از این پس تمام ترافیک گوشی از هسته‌ی واقعی عبور می‌کند.
 */
class CoreEngine(private val config: String, private val platform: PlatformInterface) {

    private var service: BoxService? = null

    fun start() {
        val svc = Libbox.newService(config, platform)
        // در حین start() تابع openTun سرویس صدا زده می‌شود و تونل ساخته می‌شود
        svc.start()
        service = svc
    }

    fun stop() {
        val svc = service
        service = null
        try {
            svc?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close core: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VexCore"

        @Volatile
        private var initialized = false

        /** راه‌اندازی یک‌باره‌ی مسیرهای کاری هسته */
        fun setup(baseDir: File, workingDir: File, tempDir: File) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                baseDir.mkdirs(); workingDir.mkdirs(); tempDir.mkdirs()
                Libbox.setup(SetupOptions().apply {
                    basePath = baseDir.absolutePath
                    workingPath = workingDir.absolutePath
                    tempPath = tempDir.absolutePath
                    // راه‌حل موقت بن‌بست گوروتین روی اندروید (golang/go#68760)
                    fixAndroidStack = true
                })
                initialized = true
            }
        }
    }
}
