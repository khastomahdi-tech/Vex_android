package com.vex.client

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * ورودی: JSON {protocol, name, raw, mtu, bypassLan, ipv6, dns, logLevel} که از صفحه‌ی وب می‌آید.
 * خروجی: کانفیگ کامل sing-box (tun inbound + DNS + قوانین مسیریابی + outbound).
 * پشتیبانی: vless، trojan، ss، vmess (لینک) و تنظیم دستی.
 *
 * نکته‌ی حیاتی (رفع «هسته متصل شد ولی دیتا رد و بدل نشد»):
 *  ۱) اگر آدرس سرور دامنه باشد، خودِ outbound برای شروع کار باید دامنه‌ی سرور را resolve کند.
 *     اگر تنها DNSِ موجود از مسیرِ خودِ پروکسی برود (detour=proxy)، حلقه‌ی مرگبار ساخته می‌شود:
 *     resolve دامنه‌ی سرور → پروکسی → resolve دامنه‌ی سرور → ... و هیچ داده‌ای رد و بدل نمی‌شود.
 *     راه‌حل: یک DNS محلی (مستقیم، همان resolver سیستم مثل بقیه کلاینت‌ها) + قانون DNS که
 *     دامنه‌ی سرور را از همان‌جا resolve کند.
 *  ۲) قانون hijack-dns بدون sniff کار نمی‌کند (protocol هرگز تشخیص داده نمی‌شود)؛ پس یک
 *     قانون sniff در ابتدای route rules اضافه می‌شود تا DNS واقعاً داخل هسته هندل شود.
 *  ۳) یک inbound «mixed» روی 127.0.0.1:19085 هم اضافه می‌شود تا برنامه بتواند بعد از بالا
 *     آمدن هسته، یک درخواست HTTP واقعی از مسیرِ سرور بفرستد. «متصل» فقط وقتی اعلام
 *     می‌شود که این تست واقعاً جواب بدهد (رفع مشکل دیتای فیک).
 */
object ConfigBuilder {

    /** پورت inbound محلی برای تست واقعی اتصال (فقط روی لوکال‌هاست گوش می‌دهد) */
    const val LOCAL_TEST_PORT = 19085

    fun build(input: String): String {
        val j = JSONObject(input)
        val raw = j.optString("raw").trim()
        val outbound = if (raw.startsWith("{")) fromManual(JSONObject(raw)) else fromLink(raw)
        outbound.put("tag", "proxy")

        val mtu = j.optInt("mtu", 1500).coerceIn(1280, 9000)
        val bypassLan = j.optBoolean("bypassLan", true)
        val ipv6 = j.optBoolean("ipv6", false)
        val dnsUser = j.optString("dns", "1.1.1.1").trim().ifEmpty { "1.1.1.1" }
        val dnsAddress = if (dnsUser.contains("://")) dnsUser else "tcp://$dnsUser"
        val logLevel = j.optString("logLevel", "warning").trim().ifEmpty { "warning" }

        val rules = JSONArray()
        // بدون sniff، قانون protocol=dns هیچ‌وقت match نمی‌شود و درخواست‌های DNS
        // به‌صورت UDP خام به مسیر final می‌روند؛ sniff باعث می‌شود DNS واقعاً hijack شود
        rules.put(JSONObject().put("action", "sniff"))
        // همه‌ی درخواست‌های DNS داخل تونل به هسته برمی‌گردند (hijack)
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        if (bypassLan) rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (!ipv6) rules.put(JSONObject().put("ip_version", 6).put("action", "reject"))

        val inbounds = JSONArray()
            .put(JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", JSONArray()
                    .put("172.19.0.1/30")
                    .put("fdfe:dcba:9876::1/126"))
                .put("mtu", mtu)
                .put("auto_route", true)
                .put("strict_route", false)
                .put("stack", "mixed"))
            // inbound محلی فقط برای تست واقعی مسیر (بدون ریسک؛ روی 127.0.0.1 گوش می‌دهد)
            .put(JSONObject()
                .put("type", "mixed")
                .put("tag", "local-test")
                .put("listen", "127.0.0.1")
                .put("listen_port", LOCAL_TEST_PORT))

        // ---- شکستن حلقه‌ی resolve دامنه‌ی سرور ----
        // dns-local = resolver سیستم (مستقیم، بدون پروکسی) — دقیقاً همان چیزی که
        // کلاینت‌های دیگر برای شروع کار استفاده می‌کنند.
        val dnsServers = JSONArray()
            .put(JSONObject()
                .put("tag", "dns-remote")
                .put("address", dnsAddress)
                .put("detour", "proxy"))
            .put(JSONObject()
                .put("tag", "dns-local")
                .put("address", "local")
                .put("detour", "direct"))

        val dnsRules = JSONArray()
        val serverHost = outbound.optString("server").trim()
        // اگر سرور دامنه است: resolve آن فقط از DNS محلی/مستقیم (حلقه‌ی مرگبار می‌شکند)
        if (serverHost.isNotEmpty() && !isIpAddress(serverHost)) {
            dnsRules.put(JSONObject()
                .put("domain", JSONArray().put(serverHost.lowercase()))
                .put("server", "dns-local"))
        }

        val config = JSONObject()
            .put("log", JSONObject().put("level", logLevel).put("timestamp", true))
            .put("dns", JSONObject()
                .put("servers", dnsServers)
                .put("rules", dnsRules)
                .put("final", "dns-remote")
                .put("strategy", if (ipv6) "prefer_ipv4" else "ipv4_only"))
            .put("inbounds", inbounds)
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject()
                .put("rules", rules)
                .put("final", "proxy")
                .put("auto_detect_interface", true))
        return config.toString()
    }

    private fun fromManual(c: JSONObject): JSONObject {
        val p = mapOf(
            "security" to c.optString("security", "none"),
            "sni" to c.optString("sni"),
            "type" to c.optString("network", "tcp"),
            "path" to c.optString("path", "/"),
            "host" to c.optString("host"),
            "serviceName" to c.optString("serviceName"),
            "fp" to c.optString("fp"),
            "pbk" to c.optString("pbk"),
            "sid" to c.optString("sid"),
            "alpn" to c.optString("alpn"),
            "insecure" to (if (c.optBoolean("allowInsecure", false)) "1" else "")
        )
        return buildOutbound(
            c.optString("protocol").lowercase(),
            c.optString("address"),
            c.optString("port").toIntOrNull() ?: 443,
            c.optString("secret"),
            p
        )
    }

    /** تشخیص IP (v4/v6) — برای تصمیم‌گیریِ اینکه آیا resolve دامنه لازم است */
    private fun isIpAddress(host: String): Boolean {
        // IPv4
        if (host.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$"))) return true
        // IPv6 (با یا بدون براکت)
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.contains(':')) {
            try {
                java.net.InetAddress.getByName(h)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun fromLink(link: String): JSONObject = when {
        link.startsWith("vless://", true) -> fromUri("vless", link)
        link.startsWith("trojan://", true) -> fromUri("trojan", link)
        link.startsWith("ss://", true) -> fromSs(link)
        link.startsWith("vmess://", true) -> fromVmess(link)
        else -> throw IllegalArgumentException("این نوع لینک پشتیبانی نمی‌شود")
    }

    private fun fromUri(proto: String, link: String): JSONObject {
        val uri = URI(link.substringBefore("#"))
        val user = URLDecoder.decode(uri.rawUserInfo ?: "", "UTF-8")
        return buildOutbound(proto, uri.host, uri.port, user, query(uri))
    }

    private fun query(uri: URI): Map<String, String> {
        val q = uri.rawQuery ?: return emptyMap()
        return q.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
    }

    private fun fromSs(link: String): JSONObject {
        val body = link.substringAfter("://").substringBefore("#")
        val creds: String
        val hostPart: String
        if (body.contains("@")) {
            val userPart = body.substringBeforeLast("@")
            creds = if (userPart.contains(":")) userPart else b64(userPart) // SIP002
            hostPart = body.substringAfterLast("@")
        } else {
            val decoded = b64(body) // نسخه‌ی قدیمی: base64(method:password@host:port)
            creds = decoded.substringBeforeLast("@")
            hostPart = decoded.substringAfterLast("@")
        }
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", hostPart.substringBeforeLast(":"))
            .put("server_port", hostPart.substringAfterLast(":").toIntOrNull() ?: 8388)
            .put("method", creds.substringBefore(":"))
            .put("password", creds.substringAfter(":"))
    }

    private fun fromVmess(link: String): JSONObject {
        val j = JSONObject(b64(link.substringAfter("://")))
        val p = mapOf(
            "security" to if (j.optString("tls") == "tls") "tls" else "none",
            "sni" to j.optString("sni"),
            "type" to j.optString("net", "tcp"),
            "path" to j.optString("path", "/"),
            "host" to j.optString("host")
        )
        return buildOutbound("vmess", j.optString("add"), j.optString("port").toIntOrNull() ?: 443, j.optString("id"), p)
    }

    private fun buildOutbound(proto: String, host: String?, port: Int, secret: String, p: Map<String, String>): JSONObject {
        require(!host.isNullOrBlank()) { "آدرس سرور مشخص نیست" }
        val o = JSONObject()
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", if (port > 0) port else 443)
        when (proto) {
            "vless" -> {
                o.put("type", "vless").put("uuid", secret)
                p["flow"]?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
            }
            "trojan" -> o.put("type", "trojan").put("password", secret)
            "vmess" -> o.put("type", "vmess").put("uuid", secret).put("security", "auto").put("alter_id", 0)
            "shadowsocks", "ss" -> o.put("type", "shadowsocks").put("method", "aes-256-gcm").put("password", secret)
            else -> throw IllegalArgumentException("پروتکل پشتیبانی‌نشده: $proto")
        }
        tls(p, host, defaultOn = proto == "trojan")?.let { o.put("tls", it) }
        transport(p)?.let { o.put("transport", it) }
        return o
    }

    private fun tls(p: Map<String, String>, host: String, defaultOn: Boolean): JSONObject? {
        val sec = p["security"] ?: if (defaultOn) "tls" else "none"
        if (sec.isEmpty() || sec == "none") return null
        val insecure = p["allowInsecure"] == "1" || p["insecure"] == "1"
                || p["allowInsecure"]?.equals("true", true) == true
                || p["insecure"]?.equals("true", true) == true
        val t = JSONObject()
            .put("enabled", true)
            .put("server_name", p["sni"]?.takeIf { it.isNotEmpty() } ?: host)
        if (insecure) t.put("insecure", true)
        p["alpn"]?.takeIf { it.isNotEmpty() }?.let { raw ->
            t.put("alpn", JSONArray().apply { raw.split(",").forEach { a -> put(a.trim()) } })
        }
        // اثر انگشت uTLS: برای Reality الزامی است و برای TLS معمولی هم اگر fp داده شده باشد اعمال می‌شود
        val fp = p["fp"]?.takeIf { it.isNotEmpty() }
        if (sec == "reality") {
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp ?: "chrome"))
            t.put("reality", JSONObject().put("enabled", true)
                .put("public_key", p["pbk"] ?: "").put("short_id", p["sid"] ?: ""))
        } else if (fp != null) {
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        return t
    }

    private fun transport(p: Map<String, String>): JSONObject? = when (p["type"] ?: "tcp") {
        "ws" -> JSONObject().put("type", "ws").put("path", p["path"]?.takeIf { it.isNotEmpty() } ?: "/").also { t ->
            p["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("headers", JSONObject().put("Host", it)) }
            // پشتیبانی WebSocket Early Data (پارامتر ed در لینک)
            val ed = p["ed"]?.toIntOrNull()
            if (ed != null && ed > 0) {
                t.put("max_early_data", ed)
                t.put("early_data_header_name", "Sec-WebSocket-Protocol")
            }
        }
        "grpc" -> JSONObject().put("type", "grpc").put("service_name", p["serviceName"] ?: "")
        "http", "h2", "h2c" -> JSONObject().put("type", "http").also { t ->
            t.put("path", p["path"]?.takeIf { it.isNotEmpty() } ?: "/")
            val hosts = p["host"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (!hosts.isNullOrEmpty()) t.put("host", JSONArray().apply { hosts.forEach { put(it) } })
        }
        else -> null
    }

    private fun b64(s: String): String {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    }
}
