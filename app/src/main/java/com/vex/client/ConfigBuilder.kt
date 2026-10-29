package com.vex.client

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * ورودی: JSON {protocol, name, raw, mtu, bypassLan, ipv6, dns, logLevel, mux} که از صفحه‌ی وب می‌آید.
 * خروجی: کانفیگ کامل sing-box (tun inbound + DNS + قوانین مسیریابی + outbound).
 *
 * پروتکل‌های ورودی لینک:
 *   vless://  vmess://  trojan://  ss://  hysteria2:// (hy2://)  tuic://
 * ورودی دستی/JSON: قالب برنامه‌ی خودمان یا یک outbound واقعی sing-box یا وایرگارد.
 *
 * نکات حیاتی (رفع «هسته متصل شد ولی دیتا رد و بدل نشد»):
 *  ۱) اگر آدرس سرور دامنه باشد، باید از DNS محلیِ مستقیم (بدون پروکسی) resolve شود وگرنه
 *     حلقه‌ی مرگبار ساخته می‌شود. قانون DNS برای دامنه‌ی سرور + dns-local نگه داشته شده.
 *  ۲) hijack-dns بدون sniff کار نمی‌کند؛ قانون sniff در ابتدای route rules است.
 *  ۳) یک inbound «mixed» روی 127.0.0.1:19085 برای تست واقعی اینترنت از مسیر سرور.
 *  ۴) وقتی IPv6 خاموش است، آدرس IPv6 به تونل داده نمی‌شود تا مسیر سیاه v6 ساخته نشود.
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
        val useMux = j.optBoolean("mux", false)
        val dnsUser = j.optString("dns", "1.1.1.1").trim().ifEmpty { "1.1.1.1" }
        val dnsAddress = if (dnsUser.contains("://")) dnsUser else "tcp://$dnsUser"
        val logLevel = j.optString("logLevel", "warning").trim().ifEmpty { "warning" }

        applyMux(outbound, useMux)

        val rules = JSONArray()
        // بدون sniff، قانون protocol=dns هیچ‌وقت match نمی‌شود؛ sniff اول می‌آید
        rules.put(JSONObject().put("action", "sniff"))
        // همه‌ی درخواست‌های DNS داخل تونل به هسته برمی‌گردند (hijack)
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        if (bypassLan) rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (!ipv6) rules.put(JSONObject().put("ip_version", 6).put("action", "reject"))

        // ---- تونل: آدرس v6 فقط وقتی IPv6 فعال است ----
        val tunAddr = JSONArray().put("172.19.0.1/30")
        if (ipv6) tunAddr.put("fdfe:dcba:9876::1/126")

        val inbounds = JSONArray()
            .put(JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", tunAddr)
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

    // ------------------------------------------------------------ mux

    /** Mux فقط برای vless/vmess/trojan و وقتی flow تنظیم نیست (Reality+Vision با mux سازگار نیست) */
    private fun applyMux(o: JSONObject, enabled: Boolean) {
        if (!enabled) return
        val type = o.optString("type")
        if (type !in setOf("vless", "vmess", "trojan")) return
        if (o.optString("flow").isNotEmpty()) return
        o.put("multiplex", JSONObject()
            .put("enabled", true)
            .put("protocol", "h2mux")
            .put("max_connections", 4)
            .put("min_streams", 4)
            .put("padding", false))
    }

    // ------------------------------------------------------------ ورودی دستی / JSON

    /**
     * دو قالب پذیرفته می‌شود:
     *  ۱) قالب برنامه: {protocol:"vless", address, port, secret, network, security, sni, ...}
     *  ۲) outbound واقعی sing-box: {type:"vless", server, server_port, uuid, tls:{...}, transport:{...}}
     *  ۳) وایرگارد: {protocol:"wireguard", privateKey, publicKey, server, port, addresses:[...], ...}
     */
    private fun fromManual(c: JSONObject): JSONObject {
        val proto = (c.optString("protocol").ifEmpty { c.optString("type") }).lowercase()
        require(proto.isNotEmpty()) { "نوع کانفیگ مشخص نیست" }

        if (proto == "wireguard") return wireguardOutbound(c)

        // قالب sing-box خام؟ (server/server_port یا tls/transport تو در تو)
        if (c.has("server") && (c.has("server_port") || c.has("uuid") || c.has("password"))) {
            return singboxOutboundToInternal(c)
        }

        val p = mutableMapOf(
            "security" to c.optString("security", if (proto == "trojan") "tls" else "none"),
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
        if (c.has("headerType")) p["headerType"] = c.optString("headerType")
        // روش رمزنگاری دستی برای Shadowsocks (لینک ss خودش method دارد)
        if (proto == "ss" || proto == "shadowsocks") p["ssMethod"] = c.optString("method")
        return buildOutbound(
            proto,
            c.optString("address"),
            c.optString("port").toIntOrNull() ?: 443,
            c.optString("secret"),
            p
        )
    }

    /** تبدیل یک outbound واقعی sing-box (از کانفیگ JSON چسبانده‌شده) به قالب داخلی */
    private fun singboxOutboundToInternal(c: JSONObject): JSONObject {
        val type = c.optString("type").lowercase()
        require(type !in setOf("direct", "block", "dns", "selector", "urltest", "block")) {
            "این بخش از کانفیگ قابل استفاده نیست (خروجی از نوع $type)"
        }
        val server = c.optString("server")
        val port = c.optInt("server_port", 0)
        require(server.isNotEmpty() && port > 0) { "آدرس/پورت سرور در JSON پیدا نشد" }

        val o = JSONObject()
            .put("type", type)
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
        when (type) {
            "vless" -> { o.put("uuid", c.optString("uuid")); if (c.optString("flow").isNotEmpty()) o.put("flow", c.optString("flow")) }
            "vmess" -> { o.put("uuid", c.optString("uuid")); o.put("security", c.optString("security", "auto").ifEmpty { "auto" }); o.put("alter_id", c.optInt("alter_id", 0)) }
            "trojan" -> o.put("password", c.optString("password"))
            "shadowsocks" -> { o.put("method", c.optString("method")); o.put("password", c.optString("password")); if (c.has("plugin")) c.optString("plugin")?.let { if (it.isNotEmpty()) o.put("plugin", it) }; if (c.has("plugin_opts")) c.optString("plugin_opts")?.let { if (it.isNotEmpty()) o.put("plugin_opts", it) } }
            "hysteria2" -> { o.put("password", c.optString("password")); if (c.has("obfs")) c.optJSONObject("obfs")?.let { o.put("obfs", it) } }
            "tuic" -> { o.put("uuid", c.optString("uuid")); o.put("password", c.optString("password")); if (c.optString("congestion_control").isNotEmpty()) o.put("congestion_control", c.optString("congestion_control")); if (c.optString("udp_relay_mode").isNotEmpty()) o.put("udp_relay_mode", c.optString("udp_relay_mode")) }
            "shadowtls", "http", "socks", "ssh", "hysteria" -> {
                if (c.has("password")) o.put("password", c.optString("password"))
                if (c.has("username")) o.put("username", c.optString("username"))
                if (c.has("version")) o.put("version", c.optInt("version"))
                if (c.has("up_mbps")) o.put("up_mbps", c.optInt("up_mbps"))
                if (c.has("down_mbps")) o.put("down_mbps", c.optInt("down_mbps"))
            }
            "wireguard" -> return wireguardOutbound(c)
            else -> throw IllegalArgumentException("پروتکل JSON پشتیبانی‌نشده: $type")
        }
        c.optJSONObject("tls")?.let { if (it.optBoolean("enabled", false)) o.put("tls", normalizeTls(it, server)) }
        c.optJSONObject("transport")?.let { t -> if (t.optString("type").isNotEmpty()) o.put("transport", t) }
        if (c.has("packet_encoding")) o.put("packet_encoding", c.optString("packet_encoding"))
        return o
    }

    /** فقط فیلدهای امن TLS را از TLS کانفیگ چسبانده‌شده کپی می‌کنیم (نام فیلدها همین‌هاست در 1.11) */
    private fun normalizeTls(t: JSONObject, server: String): JSONObject {
        val out = JSONObject().put("enabled", true)
        out.put("server_name", t.optString("server_name").ifEmpty { server })
        if (t.optBoolean("insecure")) out.put("insecure", true)
        t.optJSONArray("alpn")?.let { if (it.length() > 0) out.put("alpn", it) }
        t.optJSONObject("utls")?.let { u -> if (u.optBoolean("enabled")) out.put("utls", u) }
        t.optJSONObject("reality")?.let { r -> if (r.optBoolean("enabled")) out.put("reality", r) }
        return out
    }

    // ------------------------------------------------------------ وایرگارد

    /**
     * وایرگارد (قالب دستی یا استخراج‌شده از فایل .conf):
     * {protocol:"wireguard", privateKey, publicKey, presharedKey?, server, port,
     *  addresses:["10.2.0.2/32"], mtu?, reserved?:[1,2,3]}
     */
    private fun wireguardOutbound(c: JSONObject): JSONObject {
        // قالب دستی برنامه: privateKey/publicKey/server/port/addresses
        // قالب sing-box خام: private_key/local_address[]/peer_public_key یا peers[0]
        var server = c.optString("server").ifEmpty { c.optString("address") }
        var port = c.optInt("server_port", c.optString("port").toIntOrNull() ?: 0)
        var priv = c.optString("privateKey").ifEmpty { c.optString("private_key") }
        var pub = c.optString("publicKey").ifEmpty { c.optString("peer_public_key") }
        var psk = c.optString("presharedKey").ifEmpty { c.optString("pre_shared_key") }
        var mtu = c.optInt("mtu", 1408)
        val addrList = JSONArray()
        (c.optJSONArray("addresses") ?: c.optJSONArray("local_address"))?.let { arr ->
            for (i in 0 until arr.length()) addrList.put(arr.optString(i).trim())
        }
        val reservedRaw = StringBuilder()
        // peers[0] در قالب sing-box
        c.optJSONArray("peers")?.optJSONObject(0)?.let { peer ->
            if (server.isEmpty()) { server = peer.optString("address").ifEmpty { peer.optString("server") } }
            if (port <= 0) port = peer.optInt("port", peer.optInt("server_port", 0))
            if (pub.isEmpty()) pub = peer.optString("public_key")
            if (psk.isEmpty()) psk = peer.optString("pre_shared_key")
            (peer.optJSONArray("reserved") ?: c.optJSONArray("reserved"))?.let { r ->
                if (r.length() == 3) { for (i in 0 until 3) { if (i > 0) reservedRaw.append(","); reservedRaw.append(r.optInt(i)) } }
            }
        }
        if (server.isEmpty() || port <= 0) {
            // شاید فقط یک peer با address/port داشته باشیم یا Endpoint جدا باشد
            val ep = c.optString("endpoint")
            if (ep.contains(":")) {
                val h = ep.substringBeforeLast(":").removeSurrounding("[", "]")
                val p2 = ep.substringAfterLast(":").toIntOrNull() ?: 0
                if (server.isEmpty() && h.isNotEmpty()) server = h
                if (port <= 0) port = p2
            }
        }
        c.optString("reserved").trim().takeIf { it.isNotEmpty() && reservedRaw.isEmpty() }?.let { reservedRaw.append(it) }
        if (addrList.length() == 0) {
            c.optString("addresses_csv").takeIf { it.isNotEmpty() }?.split(",")?.forEach { addrList.put(it.trim()) }
        }
        if (mtu !in 576..9000) mtu = 1408

        require(priv.isNotEmpty()) { "کلید خصوصی وایرگارد خالی است" }
        require(pub.isNotEmpty()) { "کلید عمومی همتای وایرگارد خالی است" }
        require(server.isNotEmpty()) { "آدرس Endpoint وایرگارد مشخص نیست" }
        require(port in 1..65535) { "پورت Endpoint وایرگارد نامعتبر است" }
        require(addrList.length() > 0) { "آدرس محلی (Address) وایرگارد لازم است — مثل 10.2.0.2/32" }

        val o = JSONObject()
            .put("type", "wireguard")
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
            .put("private_key", priv)
            .put("peer_public_key", pub)
            .put("mtu", mtu)
            .put("local_address", addrList)
        psk.takeIf { it.isNotEmpty() }?.let { o.put("pre_shared_key", it) }
        // Reserved برای WARP: "1,2,3" یا base64 client id
        val reserved = JSONArray()
        reservedRaw.toString().trim().takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching {
                if (raw.contains(",")) raw.split(",").forEach { reserved.put(it.trim().toInt()) }
                else {
                    val b = b64(raw)
                    if (b.length >= 3) { for (i in 0 until 3) reserved.put(b[i].toInt() and 0xFF) }
                }
            }
        }
        if (reserved.length() == 3) o.put("reserved", reserved)
        return o
    }

    // ------------------------------------------------------------ تشخیص IP

    private fun isIpAddress(host: String): Boolean {
        if (host.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$"))) return true
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.contains(':')) {
            try {
                java.net.InetAddress.getByName(h)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    // ------------------------------------------------------------ لینک‌ها

    private fun fromLink(link: String): JSONObject = when {
        link.startsWith("vless://", true) -> fromUri("vless", link)
        link.startsWith("trojan://", true) -> fromUri("trojan", link)
        link.startsWith("ss://", true) -> fromSs(link)
        link.startsWith("vmess://", true) -> fromVmess(link)
        link.startsWith("hysteria2://", true) || link.startsWith("hy2://", true) -> fromUri("hysteria2", "hysteria2://" + link.substringAfter("://"))
        link.startsWith("tuic://", true) -> fromUri("tuic", link)
        link.contains("xhttp", true) || link.contains("splithttp", true) ->
            throw IllegalArgumentException("هسته‌ی VEX از ترنسپورت XHTTP (splitHTTP) پشتیبانی نمی‌کند — از کانفیگ vless با WS/gRPC/TCP استفاده کن")
        else -> throw IllegalArgumentException("این نوع لینک پشتیبانی نمی‌شود (پروتکل‌های پشتیبانی‌شده: vless / vmess / trojan / ss / hysteria2 / tuic)")
    }

    private fun fromUri(proto: String, link: String): JSONObject {
        val uri = URI(link.substringBefore("#"))
        val user = URLDecoder.decode(uri.rawUserInfo ?: "", "UTF-8")
        val params = query(uri).toMutableMap()
        // برخی لینک‌های grpc از path برای serviceName استفاده می‌کنند
        if (params["type"] == "grpc" && params["serviceName"].isNullOrEmpty() && !params["path"].isNullOrEmpty()) {
            params["serviceName"] = params["path"]!!.trimStart('/')
        }
        return buildOutbound(proto, uri.host, uri.port, user, params)
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
            creds = if (userPart.contains(":")) URLDecoder.decode(userPart, "UTF-8") else b64(userPart) // SIP002
            hostPart = body.substringAfterLast("@")
        } else {
            val decoded = b64(body) // نسخه‌ی قدیمی: base64(method:password@host:port)
            creds = decoded.substringBeforeLast("@")
            hostPart = decoded.substringAfterLast("@")
        }
        val o = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", hostPart.substringBeforeLast(":").removeSurrounding("[", "]"))
            .put("server_port", hostPart.substringAfterLast(":").substringBefore("/").toIntOrNull() ?: 8388)
            .put("method", creds.substringBefore(":"))
            .put("password", creds.substringAfter(":"))
        // پلاگین SIP003: plugin=obfs-local;obfs=http;obfs-host=... یا v2ray-plugin
        val rawQuery = link.substringAfter("?", "").substringBefore("#")
        val params = rawQuery.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2); k to URLDecoder.decode(v, "UTF-8")
        }
        params["plugin"]?.let { pl ->
            val parts = pl.split(";")
            val pluginName = parts[0]
            val opts = parts.drop(1).joinToString(";")
            when (pluginName) {
                "obfs-local", "simple-obfs" -> { o.put("plugin", "obfs-local"); if (opts.isNotEmpty()) o.put("plugin_opts", opts) }
                "v2ray-plugin" -> { o.put("plugin", "v2ray-plugin"); if (opts.isNotEmpty()) o.put("plugin_opts", opts) }
                else -> throw IllegalArgumentException("پلاگین Shadowsocks پشتیبانی نمی‌شود: $pluginName")
            }
        }
        return o
    }

    private fun fromVmess(link: String): JSONObject {
        val j = JSONObject(b64(link.substringAfter("://")))
        val net = j.optString("net", "tcp").ifEmpty { "tcp" }
        val p = mutableMapOf(
            "security" to if (j.optString("tls") == "tls") "tls" else "none",
            "sni" to j.optString("sni"),
            "type" to net,
            "path" to j.optString("path", "/"),
            "host" to j.optString("host"),
            "alpn" to j.optString("alpn"),
            "fp" to j.optString("fp")
        )
        if (j.optString("verify_cert") == "false" || j.optString("allowInsecure") == "1") p["insecure"] = "1"
        if (net == "grpc") {
            // در فرمت vmess لینک، serviceName گاهی در path است
            p["serviceName"] = j.optString("path", "").trimStart('/')
            if (j.optString("serviceName").isNotEmpty()) p["serviceName"] = j.optString("serviceName")
        }
        if (net == "tcp" && j.optString("type") == "http") p["headerType"] = "http"
        val o = buildOutbound("vmess", j.optString("add"), j.optString("port").toIntOrNull() ?: 443, j.optString("id"), p)
        // رمزنگاری داخلی (scy)
        j.optString("scy").takeIf { it.isNotEmpty() }?.let { o.put("security", it) }
        return o
    }

    // ------------------------------------------------------------ ساخت outbound

    private fun buildOutbound(proto: String, host: String?, port: Int, secret: String, p: Map<String, String>): JSONObject {
        require(!host.isNullOrBlank()) { "آدرس سرور مشخص نیست" }
        val o = JSONObject()
            .put("tag", "proxy")
            .put("server", host.trim().removeSurrounding("[", "]"))
            .put("server_port", if (port > 0) port else 443)
        when (proto) {
            "vless" -> {
                o.put("type", "vless").put("uuid", secret).put("packet_encoding", "xudp")
                p["flow"]?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
            }
            "trojan" -> o.put("type", "trojan").put("password", secret)
            "vmess" -> o.put("type", "vmess").put("uuid", secret).put("security", "auto").put("alter_id", 0).put("packet_encoding", "xudp")
            "shadowsocks", "ss" -> o.put("type", "shadowsocks")
                .put("method", p["ssMethod"]?.takeIf { it.isNotEmpty() } ?: "aes-256-gcm")
                .put("password", secret)
            "hysteria2", "hy2" -> {
                o.put("type", "hysteria2").put("password", secret)
                val obfs = p["obfs"] ?: ""
                if (obfs == "salamander") {
                    o.put("obfs", JSONObject().put("type", "salamander").put("password", p["obfs-password"] ?: p["obfsParam"] ?: ""))
                }
            }
            "tuic" -> {
                // secret = uuid:password
                val uuid = secret.substringBefore(":")
                val pass = secret.substringAfter(":", "")
                o.put("type", "tuic").put("uuid", uuid)
                if (pass.isNotEmpty()) o.put("password", pass)
                p["congestion_control"]?.takeIf { it.isNotEmpty() }?.let { o.put("congestion_control", it) }
                p["udp_relay_mode"]?.takeIf { it.isNotEmpty() }?.let { o.put("udp_relay_mode", it) }
            }
            else -> throw IllegalArgumentException("پروتکل پشتیبانی‌نشده: $proto")
        }
        if (proto != "hysteria2") {
            tls(p, host)?.let { o.put("tls", it) }
        } else {
            // hysteria2 همیشه TLS (QUIC) دارد
            tls(p, host, always = true)?.let { o.put("tls", it) }
        }
        transport(p)?.let { o.put("transport", it) }
        return o
    }

    private fun tls(p: Map<String, String>, host: String, always: Boolean = false): JSONObject? {
        var sec = p["security"] ?: "none"
        if (sec.isEmpty()) sec = if (always) "tls" else "none"
        if (sec == "none" && !always) return null
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
        } else if (fp != null && fp != "none") {
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        return t
    }

    private fun transport(p: Map<String, String>): JSONObject? {
        val net = (p["type"] ?: "tcp").lowercase()
        // tcp با هدر HTTP = ترنسپورت http در sing-box
        if (net == "tcp" && p["headerType"] == "http") {
            val t = JSONObject().put("type", "http")
            p["path"]?.takeIf { it.isNotEmpty() }?.let { t.put("path", it) }
            val hosts = p["host"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (!hosts.isNullOrEmpty()) t.put("host", JSONArray().apply { hosts.forEach { put(it) } })
            p["method"]?.takeIf { it.isNotEmpty() }?.let { t.put("method", it) }
            return t
        }
        return when (net) {
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
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").also { t ->
                t.put("path", p["path"]?.takeIf { it.isNotEmpty() } ?: "/")
                p["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("host", it) }
            }
            "quic" -> JSONObject().put("type", "quic")
            "kcp" -> throw IllegalArgumentException("هسته‌ی VEX از ترنسپورت mKCP پشتیبانی نمی‌کند")
            "xhttp", "splithttp" -> throw IllegalArgumentException("هسته‌ی VEX از ترنسپورت XHTTP (splitHTTP) پشتیبانی نمی‌کند")
            else -> null
        }
    }

    private fun b64(s: String): String {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    }
}
