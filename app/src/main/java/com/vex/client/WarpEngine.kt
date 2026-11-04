package com.vex.client

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * موتور وارپ (V5.10) — کلودفلر WARP: یک «موتور اتصال» رایگان و بدون انتخاب سرور.
 *
 * ایده (خواسته‌ی کاربر: «چند تا موتور دیگه که بدون انتخاب سرور رایگان وصل بشن و ترافیک
 * از هسته‌ی خودشان رد بشه»): دستگاه روی شبکه‌ی WireGuard کلودفلر ثبت‌نام می‌شود و
 * هسته‌ی sing-box خودمان تونل WireGuard را بالا می‌آورد — یعنی ترافیک کاربر از
 * شبکه‌ی کلودفلر عبور می‌کند (tun → sing-box → wireguard → cloudflare edge).
 *
 *  • کلید X25519 به‌صورت خالص در همین فایل ساخته می‌شود (بدون هیچ وابستگی بیرونی؛
 *    نردبان مونتگومری دقیقاً مطابق RFC 7748 — بردارهای تست در اسکریپت sandbox چک شده).
 *  • ثبت‌نام: مستقیم از api.cloudflareclient.com؛ اگر روی اپراتور کاربر باز نبود،
 *    از رله‌ی گیت خودمان (/warp-reg — داخل cloudflare/_worker.js) رد می‌شود.
 *  • نتیجه‌ی ثبت‌نام کش می‌شود (اشتراک وارپ دستگاه ماندگار است) و دوباره استفاده می‌شود.
 */
object WarpEngine {

    private const val TAG = "VexWarp"
    private const val PREFS = "vex_warp"
    private const val API_REG = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val CF_CLIENT_VERSION = "a-6.10-2158"
    private const val UA = "okhttp/3.12.1"

    /** همان میزبان‌های گیت در TelegramGate — رله‌ی ثبت‌نام وقتی مسیر مستقیم باز نبود */
    private val GATE_HOSTS = arrayOf(
        "https://demonvpn.shop",
        "https://gate.demonvpn.shop",
        "https://vex-gate.pages.dev"
    )
    /** همان APP_KEY سمت سرور (هدر X-Vex-Key) */
    private const val APP_KEY = "vexk_8c3b1e664023f4df081d8dbb"

    /** MTU استاندارد تونل WARP — ۱۲۸۰ (کوچک‌ترین ایمن؛ همان پیش‌فرض اپ رسمی 1.1.1.1) */
    private const val WARP_MTU = 1280
    private const val ENDPOINT_HOST = "engage.cloudflareclient.com"
    private const val ENDPOINT_PORT = 2408

    /** قفل ثبت‌نام — فقط یک ثبت‌نام هم‌زمان (هسته در تردهای vex-core صدا می‌زند) */
    private val registering = AtomicBoolean(false)

    // ------------------------------------------------------------------ API عمومی

    /**
     * کانفیگ آماده‌ی وارپ برای ConfigBuilder.buildWarp — ثبت‌نام کش‌شده یا تازه.
     * خروجی: {private_key, peer_public_key, reserved:[a,b,c], address_v4, address_v6, mtu}
     * پرتاب استثنا = ثبت‌نام نشد (تماس‌دهنده فال‌بک می‌کند).
     */
    fun ensureRegistration(ctx: Context, forceFresh: Boolean = false): JSONObject {
        if (!forceFresh) {
            val cached = cached(ctx)
            if (cached != null) return cached
        }
        if (!registering.compareAndSet(false, true)) {
            // ثبت‌نام دیگری در جریان است — تا ۲۰ ثانیه منتظر نتیجه‌اش بمان
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                cached(ctx)?.let { return it }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
            throw Exception("ثبت‌نام وارپ هم‌زمان در جریان است")
        }
        try {
            val reg = register()
            save(ctx, reg)
            Log.i(TAG, "WARP registered — endpoint=$ENDPOINT_HOST:$ENDPOINT_PORT v4=${reg.optString("address_v4")}")
            return reg
        } finally {
            registering.set(false)
        }
    }

    /** کش را پاک می‌کند (دستکاری/خرابی کش) */
    fun clearCache(ctx: Context) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Throwable) {}
    }

    /** گزارش تشخیصی کوتاه برای «جزئیات خطا» */
    fun diag(ctx: Context): String {
        val c = cached(ctx)
        return if (c == null) "وارپ: بدون ثبت‌نام کش‌شده"
        else "وارپ: ثبت‌نام کش‌شده (${c.optString("address_v4")}) — endpoint $ENDPOINT_HOST:$ENDPOINT_PORT"
    }

    // ------------------------------------------------------------------ ثبت‌نام

    private fun register(): JSONObject {
        runRfcSelfTest()
        val (priv, pub) = generateKeyPair()
        val pubB64 = Base64Url.encode(pub)
        val body = JSONObject()
            .put("key", pubB64)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("warp_enabled", true)
            .put("tos", "2025-01-04T00:00:00.000Z")
            .put("model", "PC")
            .put("serial_number", UUID.randomUUID().toString())
            .put("locale", "en_US")

        // ۱) مسیر مستقیم به API کلودفلر
        var lastErr: Exception? = null
        try {
            val resp = postJson(API_REG, body, viaRelay = false)
            if (resp != null) return parseRegistration(resp, priv)
        } catch (e: Exception) { lastErr = e }
        // ۲) رله‌ی گیت خودمان — کلودفلر edge همیشه از ایران در دسترس است
        for (host in GATE_HOSTS) {
            try {
                val resp = postJson("$host/warp-reg", body, viaRelay = true)
                if (resp != null) return parseRegistration(resp, priv)
            } catch (e: Exception) { lastErr = e }
        }
        throw Exception("ثبت‌نام WARP ناموفق بود (${lastErr?.message ?: "بدون پاسخ"})")
    }

    private fun postJson(url: String, body: JSONObject, viaRelay: Boolean): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("CF-Client-Version", CF_CLIENT_VERSION)
                if (viaRelay) setRequestProperty("X-Vex-Key", APP_KEY)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code از $url")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * استخراج کانفیگ از پاسخ ثبت‌نام (ساختار رسمی API وارپ):
     * config.interface.addresses.v4/v6 · config.peers[0].public.key · config.client_id
     * client_id (base64) همان ۳ بایت «reserved» وایرگارد است — بدون آن هسته بسته می‌شود.
     */
    private fun parseRegistration(resp: JSONObject, privateKey: ByteArray): JSONObject {
        val cfg = resp.optJSONObject("config")
            ?: throw Exception("پاسخ ثبت‌نام وارپ بدون config")
        val iface = cfg.optJSONObject("interface")
        val v4 = iface?.optString("v4").orEmpty()
        val v6 = iface?.optString("v6").orEmpty()
        if (v4.isEmpty()) throw Exception("آدرس v4 در پاسخ وارپ نیست")
        val peers = cfg.optJSONArray("peers")
        val peerKey = peers?.optJSONObject(0)
            ?.optJSONObject("public")?.optString("key").orEmpty()
        if (peerKey.isEmpty()) throw Exception("کلید عمومی همتا در پاسخ وارپ نیست")
        val clientId = cfg.optString("client_id").orEmpty()
        val reserved = try {
            val raw = android.util.Base64.decode(clientId, android.util.Base64.DEFAULT)
            if (raw.size >= 3) org.json.JSONArray()
                .put(raw[0].toInt() and 0xFF)
                .put(raw[1].toInt() and 0xFF)
                .put(raw[2].toInt() and 0xFF)
            else org.json.JSONArray().put(0).put(0).put(0)
        } catch (_: Throwable) {
            org.json.JSONArray().put(0).put(0).put(0)
        }
        return JSONObject()
            .put("private_key", Base64Std.encode(privateKey))
            .put("peer_public_key", peerKey)
            .put("reserved", reserved)
            .put("address_v4", v4)
            .put("address_v6", v6)
            .put("mtu", WARP_MTU)
            .put("endpoint_host", ENDPOINT_HOST)
            .put("endpoint_port", ENDPOINT_PORT)
    }

    // ------------------------------------------------------------------ کش

    private fun cached(ctx: Context): JSONObject? {
        return try {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("reg", null) ?: return null
            val o = JSONObject(s)
            if (o.optString("private_key").isEmpty() || o.optString("peer_public_key").isEmpty()) null else o
        } catch (_: Throwable) { null }
    }

    private fun save(ctx: Context, reg: JSONObject) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("reg", reg.toString()).apply()
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ X25519 (RFC 7748)

    // خودآزمایی یک‌باره با بردارهای رسمی RFC 7748 — اگر شکست بخورد ثبت‌نام متوقف می‌شود
    // (کلید خراب یعنی تونل WireGuard بالا می‌آید ولی هرگز handshake نمی‌شود)
    @Volatile
    private var rfcSelfTestDone = false

    private fun hexBytes(h: String): ByteArray {
        val out = ByteArray(h.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(h[i * 2], 16) shl 4)
                or Character.digit(h[i * 2 + 1], 16)).toByte()
        return out
    }

    private fun runRfcSelfTest() {
        if (rfcSelfTestDone) return
        synchronized(this) {
            if (rfcSelfTestDone) return
            // بردارهای §5.2 — اسکالر باید مثل decodeScalar25519 کلمپ شود
            val o1 = x25519(clampScalar(hexBytes("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")),
                hexBytes("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))
            val o2 = x25519(clampScalar(hexBytes("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")),
                hexBytes("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"))
            // §6.1 — کلید عمومی آلیس از نقطه‌ی پایه ۹
            val o3 = x25519(clampScalar(hexBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")),
                BASE_POINT)
            val h1 = hex(o1); val h2 = hex(o2); val h3 = hex(o3)
            if (h1 != "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552" ||
                h2 != "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957" ||
                h3 != "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a") {
                throw IllegalStateException("X25519 self-test FAILED: $h1 | $h2 | $h3")
            }
            Log.i(TAG, "X25519 self-test OK (RFC 7748 §5.2/§6.1)")
            rfcSelfTestDone = true
        }
    }

    private fun clampScalar(k: ByteArray): ByteArray {
        val o = k.copyOf()
        o[0] = (o[0].toInt() and 248).toByte()
        o[31] = (o[31].toInt() and 127).toByte()
        o[31] = (o[31].toInt() or 64).toByte()
        return o
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    private object Base64Url {
        fun encode(b: ByteArray): String =
            android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
                .trimEnd('=')
    }

    private object Base64Std {
        fun encode(b: ByteArray): String =
            android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    }

    // ⚠️ V5.14 — ریشه‌ی «BigInteger not invertible»: قبلاً ۲^۲۵۵−۱ نوشته شده بود که عدد مرکّب است
    // (شونده‌های کوچک ۷ و ۳۱ و … دارد) ⇒ modInverse تقریباً همیشه می‌میرد و «هیچ ثبت‌نامی»
    // انجام نمی‌شود. مقدار درست فیلد Curve25519 طبق RFC 7748 دقیقاً ۲^۲۵۵−۱۹ است.
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19L))
    private val A24: BigInteger = BigInteger.valueOf(121665L)
    private val BASE_POINT: ByteArray = run {
        val b = ByteArray(32); b[0] = 9; b
    }

    /** جفت‌کلید X25519 تازه — کلید خصوصی clamp‌شده مطابق RFC 7748 */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val rnd = SecureRandom()
        while (true) {
            val k = ByteArray(32)
            rnd.nextBytes(k)
            // clamp: k[0] &= 248 · k[31] &= 127 · k[31] |= 64
            k[0] = (k[0].toInt() and 248).toByte()
            k[31] = (k[31].toInt() and 127).toByte()
            k[31] = (k[31].toInt() or 64).toByte()
            val pub = x25519(k, BASE_POINT)
            if (!pub.all { it == 0.toByte() }) return k to pub
        }
    }

    /** ضرب نردبانی مونتگومری روی Curve25519 — دقیقاً ساختار شبه‌کد RFC 7748 */
    fun x25519(kBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val p = P
        val a24 = A24
        // ⚠️ Curve25519 little-endian است ولی BigInteger بیگ-اندین — بایت‌ها معکوس می‌شوند
        val k = BigInteger(1, ByteArray(32) { i -> kBytes[31 - i] })
        val uRev = ByteArray(32) { i -> uBytes[31 - i] }
        uRev[0] = (uRev[0].toInt() and 0x7F).toByte() // بیت ۲۵۵ (بیت بالای بایت آخر little-endian) پاک می‌شود
        val u = BigInteger(1, uRev).mod(p)

        val x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false

        for (t in 254 downTo 0) {
            val kt = k.testBit(t)
            if (kt != swap) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
                swap = kt
            }
            val a = x2.add(z2).mod(p)
            val aa = a.multiply(a).mod(p)
            val b = x2.subtract(z2).mod(p)
            val bb = b.multiply(b).mod(p)
            val e = aa.subtract(bb).mod(p)
            val c = x3.add(z3).mod(p)
            val d = x3.subtract(z3).mod(p)
            val da = d.multiply(a).mod(p)
            val cb = c.multiply(b).mod(p)
            x3 = da.add(cb).multiply(da.add(cb)).mod(p)
            z3 = x1.multiply(da.subtract(cb).multiply(da.subtract(cb)).mod(p)).mod(p)
            x2 = aa.multiply(bb).mod(p)
            z2 = e.multiply(aa.add(a24.multiply(e)).mod(p)).mod(p)
        }
        if (swap) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        // نقطه‌ی بی‌نهایت (z2=0؛ مثلاً ورودی u=0) — خروجی صفر مطابق RFC 7748، بدون modInverse
        if (z2.signum() == 0) return ByteArray(32)
        val out = x2.multiply(z2.modInverse(p)).mod(p)
        val res = out.toByteArray() // big-endian با علامت — باید به ۳۲ بایت little-endian تبدیل شود
        val le = ByteArray(32)
        var i = res.size - 1
        var j = 0
        while (i >= 0 && j < 32) { le[j] = res[i]; i--; j++ }
        return le
    }
}
