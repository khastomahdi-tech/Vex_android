package com.vex.client

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * ورودی: JSON {protocol, name, raw} که از صفحه‌ی وب می‌آید.
 * خروجی: کانفیگ outbound به فرمت sing-box.
 * پشتیبانی: vless، trojan، ss، vmess (لینک) و تنظیم دستی.
 */
object ConfigBuilder {

    fun build(input: String): String {
        val raw = JSONObject(input).optString("raw").trim()
        val outbound = if (raw.startsWith("{")) fromManual(JSONObject(raw)) else fromLink(raw)
        return JSONObject().put("outbounds", JSONArray().put(outbound)).toString()
    }

    private fun fromManual(c: JSONObject): JSONObject {
        val p = mapOf(
            "security" to c.optString("security", "none"),
            "sni" to c.optString("sni"),
            "type" to c.optString("network", "tcp"),
            "path" to c.optString("path", "/")
        )
        return buildOutbound(
            c.optString("protocol").lowercase(),
            c.optString("address"),
            c.optString("port").toIntOrNull() ?: 443,
            c.optString("secret"),
            p
        )
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
        val t = JSONObject()
            .put("enabled", true)
            .put("server_name", p["sni"]?.takeIf { it.isNotEmpty() } ?: host)
        if (sec == "reality") {
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", p["fp"] ?: "chrome"))
            t.put("reality", JSONObject().put("enabled", true)
                .put("public_key", p["pbk"] ?: "").put("short_id", p["sid"] ?: ""))
        }
        return t
    }

    private fun transport(p: Map<String, String>): JSONObject? = when (p["type"] ?: "tcp") {
        "ws" -> JSONObject().put("type", "ws").put("path", p["path"]?.takeIf { it.isNotEmpty() } ?: "/").also { t ->
            p["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("headers", JSONObject().put("Host", it)) }
        }
        "grpc" -> JSONObject().put("type", "grpc").put("service_name", p["serviceName"] ?: "")
        else -> null
    }

    private fun b64(s: String): String {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    }
}
