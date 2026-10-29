package com.vex.client

import android.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

/**
 * موتور ویرایش کانفیگ — جزئیات هر کانفیگ را به فیلدهای قابل ویرایش تبدیل می‌کند
 * و پس از ویرایش، کانفیگ را دوباره می‌سازد.
 *
 * پشتیبانی کامل: vless / vmess / trojan / ss / hysteria2 / tuic
 * حالت JSON (خروجی sing-box یا دستی) فقط کلیدهای ساده را ویرایش می‌کند.
 * وایرگارد و موارد ناشناخته → حالت متن (fallback).
 */
object ConfigEditor {

    class Fields(
        val kind: String,             // link | json
        val proto: String,            // vless | vmess | trojan | ss | hysteria2 | tuic | ...
        val rawOriginal: String,
        /** پارامترهای اضافه‌ی query که هنگام بازسازی حفظ می‌شوند */
        val extraQuery: MutableMap<String, String> = LinkedHashMap()
    ) {
        var name = ""
        var address = ""
        var port = ""
        var secret = ""      // uuid یا رمز
        var method = ""      // فقط ss
        var network = ""     // tcp / ws / grpc / ...
        var security = ""    // none / tls / reality
        var sni = ""
        var host = ""        // هدر Host
        var path = ""
        var serviceName = ""
        var fp = ""
        var pbk = ""
        var sid = ""
        var alpn = ""
        /** نام‌های نمایشی فیلدهای مخصوص هر پروتکل برای دیالوگ */
        val showTlsFields: Boolean
            get() = proto in setOf("vless", "vmess", "trojan")
        val showMethod: Boolean
            get() = proto == "ss"
    }

    // ------------------------------------------------------------ تجزیه

    /** null یعنی حالت متن (fallback) — ویرایش مستقیم متن کانفیگ */
    fun parse(raw: String): Fields? {
        val t = raw.trim()
        return try {
            when {
                t.startsWith("{") -> parseJson(t)
                LinkParser.isWgConf(t) -> null
                t.startsWith("vmess://", true) -> parseVmess(t)
                t.startsWith("ss://", true) -> parseSs(t)
                t.startsWith("hysteria2://", true) || t.startsWith("hy2://", true) ->
                    parseUserinfo(t, "hysteria2", secretInQuery = false)
                t.startsWith("tuic://", true) -> parseUserinfo(t, "tuic", secretInQuery = false)
                t.startsWith("vless://", true) -> parseUserinfo(t, "vless", secretInQuery = false)
                t.startsWith("trojan://", true) -> parseUserinfo(t, "trojan", secretInQuery = false)
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    /** لینک‌های با فرمت scheme://user:pass یا scheme://uuid @ host:port?query#name */
    private fun parseUserinfo(link: String, proto: String, @Suppress("UNUSED_PARAMETER") secretInQuery: Boolean): Fields {
        val scheme = link.substringBefore("://").lowercase()
        var body = link.substringAfter("://").substringBefore("#")
        val frag = try { URLDecoder.decode(link.substringAfter("#", ""), "UTF-8") } catch (_: Throwable) { link.substringAfter("#", "") }
        val queryStr = if (body.contains("?")) body.substringAfter("?") else ""
        if (body.contains("?")) body = body.substringBefore("?")
        if (body.endsWith("/")) body = body.trimEnd('/')

        val f = Fields("link", proto, link, parseQuery(queryStr))
        f.name = frag

        // userinfo
        val userinfo: String
        if (body.contains("@")) {
            userinfo = body.substringBeforeLast("@")
            body = body.substringAfterLast("@")
        } else userinfo = ""

        // host:port — تقسیم از آخرین «:» تا با IPv6 هم سازگار باشد
        f.address = body.substringBeforeLast(":").removeSurrounding("[", "]")
        f.port = body.substringAfterLast(":", "")

        // پارامترهای شناخته‌شده را از extras بردار
        val q = f.extraQuery
        when (proto) {
            "vless", "trojan" -> {
                f.secret = userinfo
                f.network = q.remove("type") ?: "tcp"
                f.security = q.remove("security") ?: if (proto == "trojan") "tls" else "none"
                f.sni = q.remove("sni") ?: ""
                f.host = q.remove("host") ?: ""
                f.path = q.remove("path") ?: ""
                f.serviceName = q.remove("serviceName") ?: ""
                f.fp = q.remove("fp") ?: ""
                f.pbk = q.remove("pbk") ?: ""
                f.sid = q.remove("sid") ?: ""
                f.alpn = q.remove("alpn") ?: ""
                q.remove("encryption")
            }
            "tuic" -> {
                f.secret = userinfo                                    // uuid:password
                f.sni = q.remove("sni") ?: ""
                f.alpn = q.remove("alpn") ?: ""
            }
            "hysteria2" -> {
                f.secret = userinfo.ifEmpty { q.remove("auth") ?: "" }
                f.sni = q.remove("sni") ?: ""
            }
        }
        return f
    }

    private fun parseVmess(link: String): Fields {
        val j = JSONObject(b64(link.substringAfter("://")))
        val f = Fields("link", "vmess", link)
        f.name = j.optString("ps")
        f.address = j.optString("add")
        f.port = j.optString("port")
        f.secret = j.optString("id")
        f.network = j.optString("net").ifEmpty { "tcp" }
        f.security = j.optString("tls").ifEmpty { "none" }
        f.sni = j.optString("sni")
        f.host = j.optString("host")
        f.path = j.optString("path")
        f.fp = j.optString("fp")
        f.alpn = j.optString("alpn")
        val aid = j.optString("aid")
        if (aid.isNotEmpty() && aid != "0") f.extraQuery["aid"] = aid
        val scy = j.optString("scy")
        if (scy.isNotEmpty()) f.extraQuery["scy"] = scy
        return f
    }

    private fun parseSs(link: String): Fields {
        val f = Fields("link", "ss", link)
        f.name = try { URLDecoder.decode(link.substringAfter("#", ""), "UTF-8") } catch (_: Throwable) { link.substringAfter("#", "") }
        var body = link.substringAfter("://").substringBefore("#").substringBefore("?")
        val userinfo: String
        if (body.contains("@")) {
            userinfo = body.substringBeforeLast("@")
            body = body.substringAfterLast("@")
        } else {
            // کل body به‌صورت base64 است: method:pass@host:port
            val decoded = b64(body)
            val dec = decoded ?: throw IllegalArgumentException("ss نامعتبر")
            val idx = dec.lastIndexOf('@')
            if (idx < 0) throw IllegalArgumentException("ss نامعتبر")
            userinfo = dec.substring(0, idx)
            body = dec.substring(idx + 1)
        }
        // userinfo ممکن است base64(method:pass) باشد
        val mp = if (userinfo.contains(":")) userinfo else b64(userinfo) ?: userinfo
        f.method = mp.substringBefore(":")
        f.secret = mp.substringAfter(":", "")
        f.address = body.substringBeforeLast(":").removeSurrounding("[", "]")
        f.port = body.substringAfterLast(":", "").substringBefore("/")
        return f
    }

    private fun parseJson(t: String): Fields {
        val j = JSONObject(t)
        val proto = (j.optString("protocol").ifBlank { j.optString("type") }).lowercase()
        val f = Fields("json", proto, t)
        f.name = j.optString("name").ifBlank { j.optString("tag").ifBlank { j.optString("remark").ifBlank { j.optString("wgName") } } }
        f.address = j.optString("server").ifBlank { j.optString("address") }
        f.port = when {
            j.optInt("server_port", 0) > 0 -> j.optInt("server_port").toString()
            else -> j.optString("port")
        }
        f.secret = j.optString("uuid").ifBlank { j.optString("password") }
        return f
    }

    // ------------------------------------------------------------ بازسازی

    /** با مقادیر ویرایش‌شده‌ی فیلدها، کانفیگ جدید می‌سازد؛ خطا → null */
    fun rebuild(f: Fields): String? {
        return try {
            when (f.kind) {
                "json" -> rebuildJson(f)
                else -> rebuildLink(f)
            }
        } catch (_: Throwable) { null }
    }

    private fun rebuildJson(f: Fields): String {
        val o = JSONObject(f.rawOriginal)
        // فقط کلیدهایی که در اصل وجود داشتند به‌روزرسانی می‌شوند
        fun setIfExist(key: String, value: String) { if (o.has(key) && value.isNotEmpty()) o.put(key, value) }
        if (o.has("name")) o.put("name", f.name)
        if (o.has("tag")) o.put("tag", f.name)
        if (o.has("remark")) o.put("remark", f.name)
        if (o.has("wgName")) o.put("wgName", f.name)
        if (o.has("server")) o.put("server", f.address)
        if (o.has("address")) o.put("address", f.address)
        if (o.has("server_port")) f.port.toIntOrNull()?.let { o.put("server_port", it) }
        if (o.has("port")) f.port.toIntOrNull()?.let { o.put("port", it.toString()) }
        if (o.has("uuid") && f.secret.isNotEmpty()) o.put("uuid", f.secret)
        if (o.has("password") && f.secret.isNotEmpty()) o.put("password", f.secret)
        setIfExist("sni", f.sni)
        return o.toString()
    }

    private fun rebuildLink(f: Fields): String {
        if (f.address.isEmpty()) throw IllegalArgumentException("آدرس خالی است")
        val port = f.port.toIntOrNull() ?: throw IllegalArgumentException("پورت نامعتبر است")

        // پارامترهای query بر اساس پروتکل
        val q = LinkedHashMap<String, String>()
        when (f.proto) {
            "vless" -> {
                q["encryption"] = "none"
                q["security"] = f.security.ifEmpty { "none" }
                q["type"] = f.network.ifEmpty { "tcp" }
            }
            "trojan" -> {
                q["security"] = f.security.ifEmpty { "tls" }
                q["type"] = f.network.ifEmpty { "tcp" }
            }
        }
        if (f.showTlsFields) {
            if (f.sni.isNotEmpty()) q["sni"] = f.sni
            if (f.host.isNotEmpty()) q["host"] = f.host
            if (f.path.isNotEmpty()) q["path"] = f.path
            if (f.serviceName.isNotEmpty()) q["serviceName"] = f.serviceName
            if (f.fp.isNotEmpty()) q["fp"] = f.fp
            if (f.pbk.isNotEmpty()) q["pbk"] = f.pbk
            if (f.sid.isNotEmpty()) q["sid"] = f.sid
            if (f.alpn.isNotEmpty()) q["alpn"] = f.alpn
        }
        if (f.proto == "tuic") {
            if (f.sni.isNotEmpty()) q["sni"] = f.sni
            if (f.alpn.isNotEmpty()) q["alpn"] = f.alpn
        }
        if (f.proto == "hysteria2") {
            if (f.sni.isNotEmpty()) q["sni"] = f.sni
        }
        // پارامترهای اضافه‌ی اصلی حفظ می‌شوند (obfs، insecure، flow، congesion_control و…)
        for ((k, v) in f.extraQuery) if (v.isNotEmpty()) q[k] = v

        val hostPart = f.address
        val qs = if (q.isEmpty()) "" else "?" + q.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val frag = if (f.name.isEmpty()) "" else "#${enc(f.name)}"

        return when (f.proto) {
            "vmess" -> {
                val j = JSONObject()
                    .put("v", "2")
                    .put("ps", f.name)
                    .put("add", f.address)
                    .put("port", port.toString())
                    .put("id", f.secret)
                    .put("aid", f.extraQuery["aid"] ?: "0")
                    .put("net", f.network.ifEmpty { "tcp" })
                    .put("type", "none")
                    .put("host", f.host)
                    .put("path", f.path)
                    .put("tls", f.security.ifEmpty { "none" })
                    .put("sni", f.sni)
                    .put("fp", f.fp)
                    .put("alpn", f.alpn)
                if (f.extraQuery.containsKey("scy")) j.put("scy", f.extraQuery["scy"] ?: "auto")
                "vmess://" + b64e(j.toString())
            }
            "ss" -> "ss://" + b64e("${f.method.ifEmpty { "aes-256-gcm" }}:${f.secret}") +
                    "@$hostPart:$port$frag"
            "tuic" -> "tuic://${enc(f.secret)}@$hostPart:$port$qs$frag"
            "hysteria2" -> "hysteria2://${enc(f.secret)}@$hostPart:$port$qs$frag"
            "trojan" -> "trojan://${enc(f.secret)}@$hostPart:$port$qs$frag"
            else -> "vless://${enc(f.secret)}@$hostPart:$port$qs$frag"
        }
    }

    // ------------------------------------------------------------ ابزار

    private fun parseQuery(qs: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        if (qs.isEmpty()) return m
        for (pair in qs.split("&")) {
            if (pair.isEmpty()) continue
            val k = try { URLDecoder.decode(pair.substringBefore("="), "UTF-8") } catch (_: Throwable) { pair.substringBefore("=") }
            val v = try { URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8") } catch (_: Throwable) { pair.substringAfter("=", "") }
            m[k] = v
        }
        return m
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun b64(s: String): String? = try {
        var t = s.trim().replace('-', '+').replace('_', '/').trim()
        while (t.length % 4 != 0) t += "="
        String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Throwable) { null }

    private fun b64e(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
