package com.vex.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** یک سرور ذخیره‌شده */
data class Server(
    val id: Int,
    var name: String,
    val protocol: String,
    val raw: String,
    var ping: Int = -1 // -1 = تست نشده، -2 = ناموفق
)

/** تنظیمات برنامه */
data class VexSettings(
    var theme: String = "royal",   // royal | dark | light | shir | system
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
    var pingTimeout: Int = 4            // تایم‌اوت پینگ TCP (ثانیه: 2/4/8)
)

/**
 * ذخیره‌سازی دائمی سرورها، سرور انتخاب‌شده و تنظیمات (SharedPreferences + JSON).
 * داده‌ها بعد از بستن برنامه می‌مانند.
 */
class ServersStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("vex_store", Context.MODE_PRIVATE)

    val servers = mutableListOf<Server>()
    var selectedId: Int = -1
        private set
    var settings = VexSettings()
        private set

    // ------------------------------------------------------------ بارگذاری

    init { load() }

    private fun load() {
        servers.clear()
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
        selectedId = prefs.getInt(KEY_SELECTED, -1)
        try {
            val o = JSONObject(prefs.getString(KEY_SETTINGS, "{}") ?: "{}")
            settings = VexSettings(
                theme = o.optString("theme", "royal"),
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
                pingTimeout = o.optInt("pingTimeout", 4).coerceIn(2, 8)
            )
        } catch (_: Throwable) { settings = VexSettings() }
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
            .toString()).apply()
    }

    /** بازگرداندن تنظیمات به حالت پیش‌فرض */
    fun resetToDefaults() {
        settings = VexSettings()
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
        servers.firstOrNull { it.id == id }?.let {
            it.ping = ms
            persistServers()
        }
    }

    fun select(id: Int) {
        selectedId = id
        prefs.edit().putInt(KEY_SELECTED, selectedId).apply()
    }

    fun selected(): Server? = servers.firstOrNull { it.id == selectedId }

    /** نام/پروتکل سرور انتخاب‌شده برای کارت صفحه‌ی اصلی */
    fun selectedInfo(): Server? = selected()

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
        .toString()

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED = "selected_id"
        private const val KEY_SETTINGS = "settings"
    }
}
