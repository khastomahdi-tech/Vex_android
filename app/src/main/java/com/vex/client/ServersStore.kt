package com.vex.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** یک سرور ذخیره‌شده */
data class Server(
    val id: Int,
    var name: String,
    var protocol: String,
    var raw: String,
    var ping: Int = -1, // -1 = تست نشده، -2 = ناموفق
    var label: String = "" // پیشوند مدیر (ایموجی/برچسب) — از ربات تنظیم می‌شود
)

/** تنظیمات برنامه */
data class VexSettings(
    var theme: String = "light",   // light | dark | shir | royal | royal_light | system — پیش‌فرض: روشن تمیز
    var mtu: Int = 1500,
    var dns: String = "1.1.1.1",
    var bypassLan: Boolean = true,
    var ipv6: Boolean = false,
    var mux: Boolean = false,
    var logLevel: String = "info",  // info | warning | debug
    var autoConnect: Boolean = false,
    var allowInsecure: Boolean = true,  // رد کردن تأیید گواهی TLS — پیش‌فرض روشن برای سرورهای CDN/self-signed
    var autoReconnect: Boolean = true,  // اتصال مجدد خودکار پس از کشته‌شدن سرویس
    var autoNetTest: Boolean = true,    // تست خودکار اینترنت پس از اتصال
    var pingTimeout: Int = 4,           // تایم‌اوت پینگ TCP (ثانیه: 2/4/8)
    // حالت per-app مثل v2rayNG: 0 = همه‌ی برنامه‌ها، 1 = فقط برنامه‌های انتخابی، 2 = همه به‌جز انتخابی‌ها
    var perAppMode: Int = 0,
    var perAppPackages: MutableSet<String> = mutableSetOf(), // پکیج‌های انتخاب‌شده
    // --- تنظیمات پیشرفته‌تر (مثل v2rayNG) ---
    var domainStrategy: String = "prefer_ipv4", // prefer_ipv4 | prefer_ipv6 | ipv4_only | ipv6_only
    var tcpFastOpen: Boolean = false,   // TCP Fast Open روی outbound
    var muxPadding: Boolean = false,    // پدینگ Mux (وقتی Mux روشن است)
    var showNotifSpeed: Boolean = true, // نمایش سرعت زنده در اعلان
    var hapticOnConnect: Boolean = true,// لرزش کوتاه هنگام اتصال موفق
    var showIntro: Boolean = true,      // نمایش اسپلش ورود (شیرهای سقوطی)
    var showExitIp: Boolean = true,     // نمایش کارت «آی‌پی خروجی» در صفحه‌ی خانه
    var sniffing: Boolean = true,       // زیرکی ترافیک (Sniffing) مثل v2rayNG
    // --- امنیت و رفتار جدید (v3.14) ---
    var pinHash: String = "",           // SHA-256 رمز قفل برنامه — خالی = بدون قفل
    var pinLen: Int = 0,                // طول رمز (برای ارسال خودکار کیبورد اختصاصی)
    var keepScreenOn: Boolean = false,  // روشن ماندن صفحه در حین اتصال
    var freeRefreshSec: Int = 60,       // بازه‌ی به‌روزرسانی خودکار سرورهای رایگان (۰ = فقط هنگام باز شدن)
    var relockOnExit: Boolean = true    // بعد از ترک برنامه (بیش از چند ثانیه) دوباره رمز بخواهد
)

/**
 * ذخیره‌سازی دائمی سرورها، سرور انتخاب‌شده و تنظیمات (SharedPreferences + JSON).
 * داده‌ها بعد از بستن برنامه می‌مانند.
 */
class ServersStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("vex_store", Context.MODE_PRIVATE)

    val servers = mutableListOf<Server>()

    /**
     * سرورهای رایگان — به‌صورت خودکار از منابع اشتراک عمومی گرفته می‌شوند.
     * فقط سرورهایی که پینگ موفق دارند اینجا ذخیره می‌شوند؛ کاربر نمی‌تواند
     * ویرایش/حذفشان کند یا مشخصاتشان را ببیند — فقط انتخاب و اتصال.
     */
    val freeServers = mutableListOf<Server>()

    /** آخرین زمان دریافت موفق سرورهای رایگان (ms epoch؛ صفر = هیچ‌وقت) */
    var freeLastFetch: Long = 0L
        private set

    var selectedId: Int = -1
        private set
    var settings = VexSettings()
        private set

    // ------------------------------------------------------------ بارگذاری

    init { load() }

    private fun load() {
        servers.clear()
        freeServers.clear()
        try {
            val arr = JSONArray(prefs.getString(KEY_SERVERS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("raw")
                if (raw.isEmpty()) continue
                servers.add(Server(
                    id = o.optInt("id"),
                    name = o.optString("name", "بدون نام"),
                    protocol = o.optString("protocol", "?").lowercase(),
                    raw = raw,
                    ping = o.optInt("ping", -1)
                ))
            }
        } catch (_: Throwable) {}
        try {
            val arr = JSONArray(prefs.getString(KEY_FREE, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("raw")
                if (raw.isEmpty()) continue
                freeServers.add(Server(
                    id = o.optInt("id"),
                    name = o.optString("name", "Vex Free Server"),
                    protocol = o.optString("protocol", "?").lowercase(),
                    raw = raw,
                    ping = o.optInt("ping", -1),
                    label = o.optString("label", "")
                ))
            }
        } catch (_: Throwable) {}
        freeLastFetch = prefs.getLong(KEY_FREE_TS, 0L)
        selectedId = prefs.getInt(KEY_SELECTED, -1)
        try {
            val o = JSONObject(prefs.getString(KEY_SETTINGS, "{}") ?: "{}")
            settings = VexSettings(
                theme = o.optString("theme", "light"),
                mtu = o.optInt("mtu", 1500).coerceIn(1280, 9000),
                dns = o.optString("dns", "1.1.1.1").ifEmpty { "1.1.1.1" },
                bypassLan = o.optBoolean("bypassLan", true),
                ipv6 = o.optBoolean("ipv6", false),
                mux = o.optBoolean("mux", false),
                logLevel = o.optString("logLevel", "info"),
                autoConnect = o.optBoolean("autoConnect", false),
                allowInsecure = o.optBoolean("allowInsecure", true),
                autoReconnect = o.optBoolean("autoReconnect", true),
                autoNetTest = o.optBoolean("autoNetTest", true),
                pingTimeout = o.optInt("pingTimeout", 4).coerceIn(2, 8),
                domainStrategy = o.optString("domainStrategy", "prefer_ipv4"),
                tcpFastOpen = o.optBoolean("tcpFastOpen", false),
                muxPadding = o.optBoolean("muxPadding", false),
                showNotifSpeed = o.optBoolean("showNotifSpeed", true),
                hapticOnConnect = o.optBoolean("hapticOnConnect", true),
                showIntro = o.optBoolean("showIntro", true),
                showExitIp = o.optBoolean("showExitIp", true),
                sniffing = o.optBoolean("sniffing", true),
                pinHash = o.optString("pinHash", ""),
                pinLen = o.optInt("pinLen", 0).coerceIn(0, 8),
                keepScreenOn = o.optBoolean("keepScreenOn", false),
                freeRefreshSec = when (o.optInt("freeRefreshSec", 60)) { 30 -> 30; 300 -> 300; 0 -> 0; else -> 60 },
                relockOnExit = o.optBoolean("relockOnExit", true),
                perAppMode = o.optInt("perAppMode", 0).coerceIn(0, 2),
                perAppPackages = try {
                    val arr = o.optJSONArray("perAppPackages")
                    val set = mutableSetOf<String>()
                    if (arr != null) for (i in 0 until arr.length()) set.add(arr.optString(i))
                    set
                } catch (_: Throwable) { mutableSetOf() }
            )
        } catch (_: Throwable) { settings = VexSettings() }
        // مهاجرت یک‌باره‌ی تم: پیش‌فرض قدیمی «شب سلطنتی» بود؛ از v3.16 پیش‌فرض «روشن سلطنتی» است.
        // فقط اگر کاربر همچنان روی همان پیش‌فرض قدیمی باشد، یک بار به روشن منتقل می‌شود.
        if (!prefs.getBoolean("theme_migrated_v316", false)) {
            if (settings.theme == "royal") {
                settings.theme = "royal_light"
                saveSettings()
            }
            prefs.edit().putBoolean("theme_migrated_v316", true).apply()
        }
        // مهاجرت یک‌باره‌ی تم از v3.19: ظاهر «AI‌مانند» طلایی حذف شد؛ نصب‌های روی تم‌های
        // سلطنتی/شیر یک بار به تم روشن تمیز منتقل می‌شوند (تیره دستی دست‌نخورده می‌ماند).
        if (!prefs.getBoolean("theme_migrated_v319", false)) {
            if (settings.theme == "royal" || settings.theme == "royal_light" || settings.theme == "shir") {
                settings.theme = "light"
                saveSettings()
            }
            prefs.edit().putBoolean("theme_migrated_v319", true).apply()
        }
    }

    // ------------------------------------------------------------ ذخیره

    private fun persistServers() {
        val arr = JSONArray()
        for (s in servers) arr.put(JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("protocol", s.protocol)
            .put("raw", s.raw)
            .put("ping", s.ping))
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun persistFree() {
        val arr = JSONArray()
        for (s in freeServers) arr.put(JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("protocol", s.protocol)
            .put("raw", s.raw)
            .put("ping", s.ping)
            .put("label", s.label))
        prefs.edit().putString(KEY_FREE, arr.toString())
            .putLong(KEY_FREE_TS, freeLastFetch).apply()
    }

    fun saveSettings() {
        prefs.edit().putString(KEY_SETTINGS, JSONObject()
            .put("theme", settings.theme)
            .put("mtu", settings.mtu)
            .put("dns", settings.dns)
            .put("bypassLan", settings.bypassLan)
            .put("ipv6", settings.ipv6)
            .put("mux", settings.mux)
            .put("logLevel", settings.logLevel)
            .put("autoConnect", settings.autoConnect)
            .put("allowInsecure", settings.allowInsecure)
            .put("autoReconnect", settings.autoReconnect)
            .put("autoNetTest", settings.autoNetTest)
            .put("pingTimeout", settings.pingTimeout)
            .put("perAppMode", settings.perAppMode)
            .put("perAppPackages", JSONArray(settings.perAppPackages))
            .put("domainStrategy", settings.domainStrategy)
            .put("tcpFastOpen", settings.tcpFastOpen)
            .put("muxPadding", settings.muxPadding)
            .put("showNotifSpeed", settings.showNotifSpeed)
            .put("hapticOnConnect", settings.hapticOnConnect)
            .put("showIntro", settings.showIntro)
            .put("showExitIp", settings.showExitIp)
            .put("sniffing", settings.sniffing)
            .put("pinHash", settings.pinHash)
            .put("pinLen", settings.pinLen)
            .put("keepScreenOn", settings.keepScreenOn)
            .put("freeRefreshSec", settings.freeRefreshSec)
            .put("relockOnExit", settings.relockOnExit)
            .toString()).apply()
    }

    /** بازگرداندن تنظیمات به حالت پیش‌فرض — رمز قفل برنامه عمداً حفظ می‌شود */
    fun resetToDefaults() {
        val keepPin = settings.pinHash
        val keepLen = settings.pinLen
        settings = VexSettings()
        settings.pinHash = keepPin
        settings.pinLen = keepLen
        saveSettings()
    }

    // ------------------------------------------------------------ سرورها

    /** افزودن سرور جدید؛ اگر raw تکراری باشد همان سرور قبلی برگردانده می‌شود */
    fun add(raw: String, name: String, protocol: String): Server? {
        val cleanRaw = raw.trim()
        if (cleanRaw.isEmpty()) return null
        servers.firstOrNull { it.raw == cleanRaw }?.let { return it }
        val nextId = (servers.maxOfOrNull { it.id } ?: 0) + 1
        val s = Server(nextId, name.ifEmpty { "سرور $nextId" }, protocol.lowercase(), cleanRaw)
        servers.add(s)
        persistServers()
        return s
    }

    fun remove(ids: Collection<Int>) {
        servers.removeAll { it.id in ids }
        if (selectedId in ids) selectedId = -1
        persistServers()
    }

    fun remove(id: Int) = remove(listOf(id))

    fun setPing(id: Int, ms: Int) {
        (servers.firstOrNull { it.id == id } ?: freeServers.firstOrNull { it.id == id })?.let {
            it.ping = ms
            if (servers.any { s -> s.id == id }) persistServers() else persistFree()
        }
    }

    fun select(id: Int) {
        selectedId = id
        prefs.edit().putInt(KEY_SELECTED, selectedId).apply()
    }

    fun selected(): Server? =
        servers.firstOrNull { it.id == selectedId }
            ?: freeServers.firstOrNull { it.id == selectedId }

    /** نام/پروتکل سرور انتخاب‌شده برای کارت صفحه‌ی اصلی */
    fun selectedInfo(): Server? = selected()

    /** آیا سرور انتخاب‌شده از لیست رایگان است؟ */
    fun isFree(s: Server): Boolean = freeServers.any { it.id == s.id }

    fun updateName(id: Int, newName: String) {
        servers.firstOrNull { it.id == id }?.let {
            it.name = newName
            persistServers()
        }
    }

    /** ویرایش کامل سرور: نام + متن کانفیگ (پروتکل از لینک جدید بازخوانی می‌شود) */
    fun update(id: Int, newName: String, newRaw: String): Boolean {
        val s = servers.firstOrNull { it.id == id } ?: return false
        val raw = newRaw.trim()
        if (raw.isEmpty()) return false
        val proto = try { LinkParser.info(raw).protocol } catch (_: Throwable) { return false }
        // اگر لینک جدید با سرور دیگری یکسان است، اجازه نده
        if (servers.any { it.id != id && it.raw == raw }) return false
        s.name = newName.ifEmpty { s.name }
        s.raw = raw
        s.protocol = proto.lowercase()
        persistServers()
        return true
    }

    // ------------------------------------------------------------ سرورهای رایگان

    /** جایگزینی کامل لیست سرورهای رایگان (فقط سرورهای سالم/پینگ‌دار از قبل فیلتر شده‌اند) */
    fun replaceFreeServers(list: List<Server>) {
        freeServers.clear()
        freeServers.addAll(list)
        freeLastFetch = System.currentTimeMillis()
        persistFree()
    }

    // ------------------------------------------------------------ کانفیگ اتصال

    /** بار JSON کامل برای VexVpnService / ConfigBuilder.build */
    fun connectionPayload(s: Server): String = JSONObject()
        .put("protocol", s.protocol)
        .put("name", s.name)
        .put("raw", s.raw)
        .put("mtu", settings.mtu)
        .put("bypassLan", settings.bypassLan)
        .put("ipv6", settings.ipv6)
        .put("dns", settings.dns)
        .put("logLevel", settings.logLevel)
        .put("mux", settings.mux)
        .put("allowInsecure", settings.allowInsecure)
        .put("domainStrategy", settings.domainStrategy)
        .put("tcpFastOpen", settings.tcpFastOpen)
        .put("muxPadding", settings.muxPadding)
        .put("sniffing", settings.sniffing)
        .toString()

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED = "selected_id"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_FREE = "free_servers"
        private const val KEY_FREE_TS = "free_last_fetch"

        /** شناسه‌ی سرورهای رایگان از این عدد شروع می‌شود تا با سرورهای کاربر تداخل نکند */
        const val FREE_ID_BASE = 100000
    }
}
