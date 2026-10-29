package com.vex.client

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * تحلیل متن کانفیگ‌ها برای نمایش (نام/پروتکل/آدرس/پورت) و جداسازی کانفیگ‌های معتبر
 * از متن کلیپ‌بورد/فایل/اشتراک. هسته‌ی ساختن کانفیگ sing-box در ConfigBuilder است.
 */
object LinkParser {

    data class Info(
        val name: String?,
        val protocol: String,   // vless | vmess | trojan | ss | hysteria2 | tuic | wireguard
        val host: String?,
        val port: Int?
    )

    private val SCHEMES = listOf(
        "vless://", "vmess://", "trojan://", "ss://",
        "hysteria2://", "hy2://", "tuic://"
    )

    // ------------------------------------------------------------ تشخیص متن

    fun isWgConf(t: String?): Boolean =
        t != null && Regex("^\\s*\\[Interface]", RegexOption.MULTILINE).containsMatchIn(t) &&
                Regex("\\[Peer]", RegexOption.IGNORE_CASE).containsMatchIn(t)

    fun isJsonText(t: String): Boolean = t.trimStart().startsWith("{") || t.trimStart().startsWith("[")

    fun looksLikeConfig(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return false
        if (isWgConf(s)) return true
        if (s.startsWith("{")) return true
        return SCHEMES.any { s.startsWith(it, true) }
    }

    // ------------------------------------------------------------ جداسازی از متن آزاد

    /**
     * از متن آزاد (کلیپ‌بورد / فایل / بدنه‌ی اشتراک) همه‌ی کانفیگ‌های معتبر را بیرون می‌کشد.
     * خروجی: لیست raw — لینک‌ها همان‌طور، وایرگارد به JSON دستی تبدیل می‌شود.
     * خطاهای هر خط نادیده گرفته می‌شود.
     */
    fun parseAll(text: String): List<String> {
        var t = text.trim()
        if (t.isEmpty()) return emptyList()

        // بدنه‌ی اشتراک اغلب base64 کامل است
        if (!looksLikeConfig(t) && !t.contains("\n") || !SCHEMES.any { t.contains(it, true) }) {
            b64decode(t)?.let { decoded ->
                val d = decoded.trim()
                if (looksLikeConfig(d)) t = d
            }
        }

        val out = mutableListOf<String>()
        if (isWgConf(t)) {
            val manual = wgConfToManual(t)
            if (manual != null) out.add(manual)
            return out
        }
        if (t.trimStart().startsWith("[")) {
            // آرایه‌ی JSON از outbound های sing-box
            try {
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("server").isNotEmpty() && o.optInt("server_port", 0) > 0) {
                        out.add(o.toString())
                    }
                }
                return out
            } catch (_: Throwable) {}
        }
        if (t.trimStart().startsWith("{")) {
            try {
                val o = JSONObject(t)
                val ok = o.optString("server").isNotEmpty() &&
                        (o.optInt("server_port", 0) > 0 || o.optString("address").isNotEmpty())
                if (ok) out.add(t.trim())
                return out
            } catch (_: Throwable) {}
        }
        for (line in t.lines()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            if (!SCHEMES.any { s.startsWith(it, true) }) continue
            try { info(s) } catch (_: Throwable) { continue }
            out.add(s)
        }
        return out
    }

    // ------------------------------------------------------------ اطلاعات نمایشی

    /** اطلاعات نمایشی هر کانفیگ؛ برای لینک‌های خراب استثنا می‌دهد */
    fun info(raw: String): Info {
        val t = raw.trim()
        return when {
            t.trimStart().startsWith("{") -> infoJson(t)
            isWgConf(t) -> infoWg(t)
            t.startsWith("vmess://", true) -> infoVmess(t)
            t.startsWith("ss://", true) -> infoSs(t)
            t.startsWith("hysteria2://", true) || t.startsWith("hy2://", true) ->
                infoUri("hysteria2", "hysteria2://" + t.substringAfter("://"))
            else -> infoUri(t.substringBefore("://").lowercase(), t)
        }
    }

    private fun infoUri(proto: String, link: String): Info {
        val clean = link.substringBefore("#")
        val uri = URI(clean)
        val host = uri.host?.removeSurrounding("[", "]")
        val port = if (uri.port > 0) uri.port else null
        val frag = try { URLDecoder.decode(link.substringAfter("#", ""), "UTF-8") } catch (_: Throwable) { link.substringAfter("#", "") }
        return Info(
            name = frag.ifBlank { null },
            protocol = proto,
            host = host,
            port = port
        )
    }

    private fun infoVmess(link: String): Info {
        val j = JSONObject(b64Require(link.substringAfter("://")))
        val port = j.optString("port").toIntOrNull()
        return Info(
            name = j.optString("ps").ifBlank { null },
            protocol = "vmess",
            host = j.optString("add").ifBlank { null },
            port = port
        )
    }

    private fun infoSs(link: String): Info {
        val name = try { URLDecoder.decode(link.substringAfter("#", ""), "UTF-8") } catch (_: Throwable) { link.substringAfter("#", "") }
        val body = link.substringAfter("://").substringBefore("#").substringBefore("?")
        val hostPart: String
        if (body.contains("@")) {
            hostPart = body.substringAfterLast("@")
        } else {
            val decoded = b64Require(body)
            hostPart = decoded.substringAfterLast("@")
        }
        val host = hostPart.substringBeforeLast(":").removeSurrounding("[", "]")
        val port = hostPart.substringAfterLast(":").substringBefore("/").toIntOrNull()
        return Info(name.ifBlank { null }, "ss", host.ifBlank { null }, port)
    }

    private fun infoWg(text: String): Info {
        val m = wgConfToManual(text) ?: throw IllegalArgumentException("فایل وایرگارد نامعتبر است")
        val j = JSONObject(m)
        val name = j.optString("wgName").ifBlank { "WireGuard" }
        return Info(name, "wireguard", j.optString("server").ifBlank { null },
            j.optString("port").toIntOrNull())
    }

    private fun infoJson(t: String): Info {
        val j = JSONObject(t)
        val proto = (j.optString("protocol").ifBlank { j.optString("type") }).lowercase()
        val host = j.optString("server").ifBlank { j.optString("address") }
        val port = when {
            j.optInt("server_port", 0) > 0 -> j.optInt("server_port")
            j.optString("port").toIntOrNull() != null -> j.optString("port").toIntOrNull()
            else -> null
        }
        val name = j.optString("name").ifBlank { j.optString("tag").ifBlank { j.optString("remark").ifBlank { proto.uppercase() } } }
        return Info(name, proto, host.ifBlank { null }, port)
    }

    // ------------------------------------------------------------ وایرگارد → JSON دستی

    /**
     * تبدیل فایل conf وایرگارد به قالب دستی برنامه (ورودی ConfigBuilder.wireguardOutbound).
     * خروجی null یعنی conf معتبر نیست.
     */
    fun wgConfToManual(text: String): String? {
        try {
            var section = ""
            var priv = ""; var pub = ""; var psk = ""; var endpoint = ""; var dnsLine = ""
            val addresses = mutableListOf<String>()
            var mtu = ""
            var reserved = ""
            var name = ""

            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    val d = line.removePrefix("#").trim()
                    if (name.isEmpty() && d.length > 2) name = d
                    continue
                }
                if (line.startsWith("[")) { section = line.removeSurrounding("[", "]").lowercase(); continue }
                val idx = line.indexOf('=')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (section) {
                    "interface" -> when (key) {
                        "privatekey" -> priv = value
                        "address" -> value.split(",").forEach { a -> if (a.isNotBlank()) addresses.add(a.trim()) }
                        "mtu" -> mtu = value
                        "dns" -> dnsLine = value
                    }
                    "peer" -> when (key) {
                        "publickey" -> pub = value
                        "presharedkey" -> psk = value
                        "endpoint" -> endpoint = value
                        "reserved" -> reserved = value
                    }
                }
            }
            if (priv.isEmpty() || pub.isEmpty() || endpoint.isEmpty()) return null

            val host = endpoint.substringBeforeLast(":").removeSurrounding("[", "]")
            val port = endpoint.substringAfterLast(":").toIntOrNull() ?: 51820

            val o = JSONObject()
                .put("protocol", "wireguard")
                .put("wgName", name)
                .put("privateKey", priv)
                .put("publicKey", pub)
                .put("server", host)
                .put("port", port)
                .put("addresses_csv", addresses.joinToString(","))
            if (psk.isNotEmpty()) o.put("presharedKey", psk)
            if (mtu.isNotEmpty()) o.put("mtu", mtu.toIntOrNull() ?: 1408)
            if (reserved.isNotEmpty()) o.put("reserved", reserved)
            if (dnsLine.isNotEmpty()) o.put("dns", dnsLine.split(",").first().trim())
            return o.toString()
        } catch (_: Throwable) {
            return null
        }
    }

    // ------------------------------------------------------------ ابزار

    fun b64decode(s: String): String? = try {
        var t = s.trim().replace('-', '+').replace('_', '/').trim()
        while (t.length % 4 != 0) t += "="
        String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Throwable) { null }

    private fun b64Require(s: String): String =
        b64decode(s) ?: throw IllegalArgumentException("base64 نامعتبر است")
}
