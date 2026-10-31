/**
 * Vex Gate v3 — Telegram bot backend for Vex Client
 * Cloudflare Pages + D1 (vex-gate-db)
 *
 * Endpoints:
 *   POST /tg                 → Telegram webhook (X-Telegram-Bot-Api-Secret-Token)
 *   GET  /v1/state?did=...   → {"ok":true,"gate":"open"|"closed","reason":"..."}
 *   POST /v1/report          → install/open report → D1 + rich Telegram message to owner
 *   POST /v1/ack             → device ack lock/unlock → owner notified
 *   POST /v1/traffic         → traffic delta report {did, type: free|private, up, down}
 *   GET  /v1/freeservers     → free servers managed from the bot → {"ok":true,"servers":[...]}
 *   GET  /v1/notifications?since=N → push notifications written by the bot (/notify)
 *   POST /v1/syncsnap        → re-sync the GitHub fallback snapshots (state/freeservers/notifications)
 *   GET  /apk                → public APK download mirror (streams latest GitHub release asset;
 *                               reliable in Iran where GitHub download links get truncated)
 *   GET  /                   → health
 *
 *   /admin/*                 → web admin panel API (panel.demonvpn.shop) — X-Admin-Key header,
 *                              key lives in D1 settings (admin_key). Full remote control:
 *                              overview stats, gate open/close, free servers CRUD,
 *                              broadcast notifications, users list, GitHub download stats.
 *
 * /v1/* endpoints require header  X-Vex-Key: <APP_KEY>
 * /admin/* endpoints require header  X-Admin-Key: <ADMIN_KEY from D1>
 *
 * GitHub FALLBACK channel: *.pages.dev is SNI-filtered in Iran, so users without a VPN
 * could never reach the gate (no free servers, no notifications, no lock screen). Every
 * bot mutation now also writes JSON snapshots into the repo (gate/*.json, gh_token/gh_repo
 * live in the D1 settings table) and the app reads raw.githubusercontent.com when the
 * direct gate is unreachable — GitHub is reachable from Iran.
 *
 * Bot (inline-button menu; owner = first chat that sends /start):
 *   📊 آمار · 👥 کاربران · 🖥 سرورهای رایگان · 📈 مصرف ترافیک · 🟢 وضعیت · 🔒 بستن · 🔓 باز کردن
 *   Free servers: add (paste configs) / list / delete one / delete all — D1-backed,
 *   pushed to every user's «سرورهای رایگان» tab automatically (60s auto-refresh, read-only).
 *   /close [reason] locks the app; locked users see «دسترسی برنامه بسته شده است» + reason.
 *   Traffic: app reports session deltas (free/private); bot shows totals + top users.
 *   Install notification: exactly ONE per device (first install only — updates stay silent).
 *   /notify Title | Body  → push notification to every user's phone — reaches users even
 *                           with the app fully closed: in-app tick every 45s + WorkManager
 *                           background pulse every 15 min (system-scheduled, survives swipe-away).
 *   /sname <id> <prefix>  → set/remove the server prefix (emoji/label) shown in the app.
 *   Free-server add supports «پیشوند | config» lines, wg:// links and full WG .conf text.
 *   /panel → web admin panel link · /minver <code> → forced-update banner on old builds
 *   /ping → health check · /reload → re-sync GitHub fallback snapshots · /notifs recent list
 *   🚫 نسخه‌های بسته: versions menu — close/open SPECIFIC app versions (blocked_versions in D1)
 *       → users running a blocked version see «این نسخه بسته شده است؛ بروزرسانی کنید» and cannot enter.
 *       Web panel has the same section under tab «🚫 نسخه‌ها».
 *   Free-server list rows now also have a per-server ⚫/🟢 enable/disable toggle.
 *
 * USER-FACING BOT (v11):
 *   Non-owner users get a public menu: 📥 دانلود · 🟢 وضعیت سرویس · 🆘 پشتیبانی · ℹ️ راهنما
 *   + /app /status /id /help. Every message from a user becomes a support ticket:
 *   copied to the owner with a «📩 پاسخ» button → owner's reply is delivered back to the user.
 *   Users are tracked in bot_users (D1) with join date, msg count, ban flag.
 *
 * OWNER ADDITIONS (v11):
 *   📣 پیام همگانی /broadcast — chunked mass-send (copyMessage) to all bot users with a
 *       progress report; chunk continuation via self-call /bcast-tick (fresh subrequest budget).
 *   📢 پست کانال /chanpost — publish to @vex_vpnfree with auto download buttons.
 *   🧑‍💬 کاربران ربات /tgu · 🧪 وضعیت بیلد /ci (GitHub Actions runs) · /reply · /ban · /unban.
 *   /stats now includes a 7-day install bar chart (from devices.first_seen).
 *   Unhandled bot errors are reported to the owner (max 1/min) instead of being swallowed.
 */

const SERVICE_V = 13;

const DEFAULT_REPO = "khastomahdi-tech/Vex_android";
const LATEST_ASSET = "Vex-Client-latest.apk";

const DEFAULT_CLOSE_REASON = "The admin has closed the app for now. Please try again later.";
const CLOSE_REASON_UPDATE = "We're updating the app. Please try again later.";
const CLOSE_REASON_TECH = "A technical problem occurred. Please try again later.";

const PROTOCOLS = ["vless", "vmess", "trojan", "ss", "wg", "wireguard"];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && (path === "/" || path === "")) {
      return json({ ok: true, service: "vex-gate", v: SERVICE_V, time: new Date().toISOString() });
    }

    // ---------------- Public APK download mirror ----------------
    // GitHub blocks release downloads from Cloudflare egress IPs (404/403), so the
    // APK is stored here as static asset parts (<25MiB each) + manifest.json, and
    // this route reassembles and streams it. CI refreshes the parts on every release.
    if (path === "/apk" && request.method === "GET") {
      try {
        const mfRes = await env.ASSETS.fetch(new URL("/parts/manifest.json", request.url));
        if (!mfRes.ok) return json({ ok: false, error: "mirror not ready" }, 503);
        const mf = await mfRes.json();
        const bufs = [];
        let total = 0;
        for (let i = 0; i < mf.parts; i++) {
          const n = String(i).padStart(2, "0");
          const r = await env.ASSETS.fetch(new URL(`/parts/vex.part-${n}`, request.url));
          if (!r.ok) return json({ ok: false, error: "mirror part missing", part: i }, 503);
          const b = await r.arrayBuffer();
          bufs.push(b);
          total += b.byteLength;
        }
        const body = new Uint8Array(total);
        let o = 0;
        for (const b of bufs) { body.set(new Uint8Array(b), o); o += b.byteLength; }
        return new Response(body, {
          headers: {
            "Content-Type": "application/vnd.android.package-archive",
            "Content-Disposition": `attachment; filename="${mf.name}"`,
            "Content-Length": String(total),
            "Cache-Control": "public, max-age=120",
          },
        });
      } catch (e) {
        return json({ ok: false, error: "mirror error: " + (e && e.message) }, 502);
      }
    }

    // ---------------- Telegram webhook ----------------
    if (path === "/tg" && request.method === "POST") {
      if (env.WEBHOOK_SECRET && request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      let up = null;
      try { up = await request.json(); } catch (e) { return json({ ok: true }); }
      try {
        await handleUpdate(up, env, url.origin);
      } catch (e) {
        // never fail the webhook — but tell the owner what broke (max once per minute)
        try { await botError(env, e); } catch (_e) {}
      }
      return json({ ok: true });
    }

    // ---------------- Broadcast tick — self-call between chunks (fresh subrequest budget) ----------------
    if (path === "/bcast-tick" && request.method === "POST") {
      const k = url.searchParams.get("k") || "";
      const admin = env.APP_KEY || (await getSetting(env, "admin_key"));
      if (!admin || k !== admin) return json({ ok: false, error: "forbidden" }, 403);
      try { await bcastTick(env, url.origin); } catch (e) { try { await botError(env, e); } catch (_e) {} }
      return json({ ok: true });
    }

    // ---------------- App API (X-Vex-Key protected) ----------------
    if (path.startsWith("/v1/")) {
      if (!env.APP_KEY || request.headers.get("X-Vex-Key") !== env.APP_KEY) {
        return json({ ok: false, error: "unauthorized" }, 401);
      }
      if (path === "/v1/state" && request.method === "GET") {
        const gate = (await getSetting(env, "gate")) || "open";
        const reason = gate === "closed" ? ((await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON) : null;
        const minVer = Number(await getSetting(env, "min_ver")) || 0;
        const blocked = await getBlocked(env);
        // لینک رباتی که دکمه‌ی «ورود به ربات» برنامه باز می‌کند — از تنظیمات D1 قابل تغییر است
        const botUrl = (await getSetting(env, "app_bot_url")) || "https://t.me/Vexpanelvpn_bot";
        return json({ ok: true, gate, reason, min_ver: minVer, blocked, bot_url: botUrl });
      }
      if (path === "/v1/report" && request.method === "POST") {
        return report(request, env);
      }
      if (path === "/v1/ack" && request.method === "POST") {
        return ack(request, env);
      }
      if (path === "/v1/traffic" && request.method === "POST") {
        let b = {};
        try { b = await request.json(); } catch (e) {}
        const did = String(b.did || "").slice(0, 64);
        if (!did) return json({ ok: false, error: "did required" }, 400);
        const up = Math.max(0, Math.round(Number(b.up) || 0));
        const dn = Math.max(0, Math.round(Number(b.down) || 0));
        const srv = Math.round(Number(b.srv) || 0);
        if (srv > 0) {
          // مصرف تفکیکی هر سرور رایگان (از نسخه‌ی ۴ به بعد) — جدول جدا، دوباره‌شماری در مصرف کلی ندارد
          await ensureTrafficSrv(env);
          if (up || dn) {
            await env.DB.prepare(
              "INSERT INTO traffic_srv (srv, did, up, down, updated) VALUES (?1,?2,?3,?4,?5) " +
              "ON CONFLICT(srv,did) DO UPDATE SET up = up + ?3, down = down + ?4, updated = ?5"
            ).bind(srv, did, up, dn, Date.now()).run();
          }
          return json({ ok: true });
        }
        const type = b.type === "free" ? "free" : "private";
        if (up || dn) {
          await env.DB.prepare(
            "INSERT INTO traffic (did, type, up, down, updated) VALUES (?1,?2,?3,?4,?5) " +
            "ON CONFLICT(did,type) DO UPDATE SET up = up + ?3, down = down + ?4, updated = ?5"
          ).bind(did, type, up, dn, Date.now()).run();
        }
        return json({ ok: true });
      }
      if (path === "/v1/freeservers" && request.method === "GET") {
        const rs = await env.DB.prepare(
          "SELECT id, name, label, proto, raw FROM free_servers WHERE enabled = 1 ORDER BY id"
        ).all();
        return json({ ok: true, servers: rs.results || [] });
      }
      if (path === "/v1/notifications" && request.method === "GET") {
        const since = Math.max(0, Math.round(Number(url.searchParams.get("since")) || 0));
        const rs = await env.DB.prepare(
          "SELECT id, title, body FROM notifications WHERE id > ?1 ORDER BY id ASC LIMIT 20"
        ).bind(since).all();
        return json({ ok: true, list: rs.results || [] });
      }
      // ---------------- اشتراک: فعال‌سازی توکن (۳۰ حرف انگلیسی / کدهای قدیمی) ----------------
      if (path === "/v1/sub/activate" && request.method === "POST") {
        await ensureSubTables(env);
        let b = {};
        try { b = await request.json(); } catch (e) {}
        const code = normSubCode(b.code);
        const did = String(b.did || "").slice(0, 64);
        const device = String(b.device || "?").slice(0, 80);
        const android = String(b.android || "").slice(0, 60);
        const ver = String(b.ver || "").slice(0, 24);
        const now = Date.now();
        if (!did) return json({ ok: false, error: "did required", server_time: now });
        if (!validSubCode(code)) return json({ ok: false, error: "bad_code", server_time: now });
        const c = await env.DB.prepare(
          "SELECT code, chat_id, created, expires, active FROM sub_codes WHERE code = ?1"
        ).bind(code).first();
        if (!c) return json({ ok: false, error: "not_found", server_time: now });
        if (!c.active) return json({ ok: false, error: "revoked", expires: c.expires, server_time: now });
        if (c.expires <= now) return json({ ok: false, error: "expired", expires: c.expires, server_time: now });
        // هر کد فقط روی «یک دستگاه» فعال می‌شود — اگر دستگاه دیگری این کد را برداشته، رد می‌شود
        const taken = await env.DB.prepare(
          "SELECT did FROM sub_sessions WHERE code = ?1"
        ).bind(code).all();
        const trows = (taken && taken.results) || [];
        const firstTime = trows.length === 0;
        if (trows.length && !trows.some((r) => r && r.did === did)) {
          return json({ ok: false, error: "device_taken", expires: c.expires, server_time: now });
        }
        // ثبت نشست همین دستگاه — اگر قبلاً با همین کد فعال شده، همان نشست تازه می‌شود
        await env.DB.prepare(
          "INSERT INTO sub_sessions (code, did, device, activated, last_seen) VALUES (?1,?2,?3,?4,?4) " +
          "ON CONFLICT(code,did) DO UPDATE SET device = excluded.device, last_seen = excluded.last_seen"
        ).bind(code, did, device, now).run();
        const sn = await env.DB.prepare(
          "SELECT COUNT(*) AS n FROM sub_sessions WHERE code = ?1"
        ).bind(code).first();
        // اولین اتصال با این توکن → کاربر از طرف ربات توکن اطلاع‌رسانی می‌شود + رویداد ثبت می‌شود
        if (firstTime) {
          await logEvent(env, "activate", code, c.chat_id, device, android, ver, true);
          if (c.chat_id) {
            await notifySend(env, c.chat_id,
              "✅ <b>اتصال شما به برنامه با موفقیت برقرار شد</b>\n\n" +
              "📱 دستگاه: <b>" + esc(device) + "</b>\n" +
              (android ? "🤖 اندروید: <b>" + esc(android) + "</b>\n" : "") +
              (ver ? "📦 نسخه‌ی برنامه: <b>v" + esc(ver) + "</b>\n" : "") +
              "🕐 زمان: <b>" + faTime(now) + "</b>\n\n" +
              "💡 توکن تا پایان اعتبارش روی همین دستگاه فعّال است.");
          }
        } else {
          await logEvent(env, "activate", code, c.chat_id, device, android, ver, false);
        }
        return json({ ok: true, code, created: c.created, expires: c.expires, server_time: now, device, sessions: sn ? sn.n : 0 });
      }

      // ---------------- اشتراک: بررسی وضعیت (کیک/انقضا را هم تشخیص می‌دهد) ----------------
      // فقط خواندنی است — هیچ write ای روی D1 نمی‌زند تا فشار بی‌دلیل نیاید
      if (path === "/v1/sub/status" && request.method === "POST") {
        await ensureSubTables(env);
        let b = {};
        try { b = await request.json(); } catch (e) {}
        const code = normSubCode(b.code);
        const did = String(b.did || "").slice(0, 64);
        const now = Date.now();
        if (!did || !validSubCode(code)) return json({ ok: false, error: "bad_request", server_time: now });
        const c = await env.DB.prepare(
          "SELECT code, created, expires, active FROM sub_codes WHERE code = ?1"
        ).bind(code).first();
        if (!c) return json({ ok: false, error: "not_found", server_time: now });
        if (c.expires <= now) return json({ ok: false, error: "expired", expires: c.expires, server_time: now });
        const s = await env.DB.prepare(
          "SELECT did, device, activated FROM sub_sessions WHERE code = ?1 AND did = ?2"
        ).bind(code, did).first();
        if (!s) return json({ ok: false, error: "kicked", expires: c.expires, server_time: now });
        const sn = await env.DB.prepare(
          "SELECT COUNT(*) AS n FROM sub_sessions WHERE code = ?1"
        ).bind(code).first();
        return json({ ok: true, code, created: c.created, expires: c.expires, server_time: now, device: s.device, activated: s.activated, sessions: sn ? sn.n : 0 });
      }

      // ---------------- اشتراک: خروج از حساب (حذف نشست این دستگاه) ----------------
      // دکمه‌ی قرمز «خروج از حساب» در تب حساب کاربری برنامه این را صدا می‌زند؛
      // نشست دستگاه حذف می‌شود (توکن تا پایان اعتبارش باقی می‌ماند و دوباره قابل ورود است)
      // و ربات توکن به کاربر «یک نشانه از روی حسابت حذف شد» را اطلاع می‌دهد.
      if (path === "/v1/sub/logout" && request.method === "POST") {
        await ensureSubTables(env);
        let b = {};
        try { b = await request.json(); } catch (e) {}
        const code = normSubCode(b.code);
        const did = String(b.did || "").slice(0, 64);
        const android = String(b.android || "").slice(0, 60);
        const now = Date.now();
        if (!did || !validSubCode(code)) return json({ ok: false, error: "bad_request", server_time: now });
        const c = await env.DB.prepare(
          "SELECT code, chat_id FROM sub_codes WHERE code = ?1"
        ).bind(code).first();
        if (!c) return json({ ok: false, error: "not_found", server_time: now });
        const s = await env.DB.prepare(
          "SELECT did, device FROM sub_sessions WHERE code = ?1 AND did = ?2"
        ).bind(code, did).first();
        if (s) {
          await env.DB.prepare(
            "DELETE FROM sub_sessions WHERE code = ?1 AND did = ?2"
          ).bind(code, did).run();
          await logEvent(env, "logout", code, c.chat_id, s.device, android, "", false);
          if (c.chat_id) {
            await notifySend(env, c.chat_id,
              "🗑 <b>یک نشانه از روی حساب شما حذف شد</b>\n\n" +
              "یعنی یک نشانه از روی حسابت رفت بیرون.\n" +
              "📱 دستگاه: <b>" + esc(String(s.device || "?").slice(0, 60)) + "</b>\n" +
              (android ? "🤖 اندروید: <b>" + esc(android) + "</b>\n" : "") +
              "🕐 زمان: <b>" + faTime(now) + "</b>\n\n" +
              "برای ورود دوباره، همان توکن را داخل برنامه وارد کن.");
          }
        }
        return json({ ok: true, server_time: now });
      }

      if (path === "/v1/syncsnap" && request.method === "POST") {
        const st = await stateSnapshot(env);
        const fs = await fsSnapshot(env);
        const nt = await notifSnapshot(env);
        await ghSync(env, "state.json", st);
        await ghSync(env, "freeservers.json", fs);
        await ghSync(env, "notifications.json", nt);
        return json({ ok: true, servers: (fs.servers || []).length, notifs: (nt.list || []).length, gate: st.gate });
      }
      return json({ ok: false, error: "not found" }, 404);
    }

    // ---------------- Admin panel API (X-Admin-Key protected) ----------------
    // پنل مدیریت وب — روی panel.demonvpn.shop اجرا می‌شود و به همین API وصل است.
    // کلید ادمین در D1 settings (admin_key) نگهداری می‌شود — نه در کد، نه در ریپوی عمومی.
    if (path.startsWith("/admin/")) {
      const ch = adminCors({ "content-type": "application/json; charset=utf-8" });
      if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: adminCors({}) });
      if (!(await isAdminAuthed(env, request))) {
        return new Response(JSON.stringify({ ok: false, error: "unauthorized" }), { status: 401, headers: ch });
      }
      return handleAdmin(path, url, request, env);
    }

    return json({ ok: false, error: "not found" }, 404);
  }
};

// ============================================================ admin panel API

function adminCors(extra) {
  const h = {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, POST, OPTIONS",
    "access-control-allow-headers": "X-Admin-Key, Content-Type",
    "access-control-max-age": "86400"
  };
  if (extra) for (const k in extra) h[k] = extra[k];
  return h;
}

let adminKeyCache = { v: null, at: 0 };

async function isAdminAuthed(env, request) {
  const key = request.headers.get("X-Admin-Key") || "";
  if (!key) return false;
  let real = null;
  const now = Date.now();
  if (adminKeyCache.v && now - adminKeyCache.at < 60000) real = adminKeyCache.v;
  else {
    real = await getSetting(env, "admin_key");
    if (real) { adminKeyCache.v = real; adminKeyCache.at = now; }
  }
  return !!real && key === real;
}

async function handleAdmin(path, url, request, env) {
  const J = (obj, status = 200) => new Response(JSON.stringify(obj), {
    status, headers: adminCors({ "content-type": "application/json; charset=utf-8" })
  });

  if (path === "/admin/login" && request.method === "POST") {
    return J({ ok: true, v: SERVICE_V });
  }

  if (path === "/admin/overview" && request.method === "GET") {
    const gate = (await getSetting(env, "gate")) || "open";
    const reason = gate === "closed" ? ((await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON) : null;
    const t = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first();
    const today = await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE first_seen >= ?1").bind(Date.now() - 86400000).first();
    const active = await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE last_seen >= ?1").bind(Date.now() - 86400000).first();
    const op = await env.DB.prepare("SELECT COALESCE(SUM(opens),0) AS n FROM users").first();
    const fs = await countFs(env);
    const tt = await trafficTotals(env);
    const nc = await env.DB.prepare("SELECT COUNT(*) AS n FROM notifications").first();
    const gh = await ghReleaseStats(env);
    return J({
      ok: true, gate, reason,
      users: t ? t.n : 0,
      todayInstalls: today ? today.n : 0,
      active24h: active ? active.n : 0,
      opens: op ? op.n : 0,
      freeServers: fs,
      traffic: { free: tt.freeB, private: tt.privB },
      notifications: nc ? nc.n : 0,
      github: gh
    });
  }

  if (path === "/admin/gate" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    const gate = b.gate === "closed" ? "closed" : "open";
    await setSetting(env, "gate", gate);
    if (gate === "closed") {
      const reason = String(b.reason || "").trim().slice(0, 400) || DEFAULT_CLOSE_REASON;
      await setSetting(env, "close_reason", reason);
    }
    await ghSync(env, "state.json", await stateSnapshot(env));
    return J({ ok: true, gate, reason: gate === "closed" ? ((await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON) : null });
  }

  if (path === "/admin/servers" && request.method === "GET") {
    const rs = await env.DB.prepare(
      "SELECT id, name, label, proto, added_at, enabled, LENGTH(raw) AS raw_len FROM free_servers ORDER BY id DESC LIMIT 200"
    ).all();
    return J({ ok: true, servers: rs.results || [] });
  }

  if (path === "/admin/servers" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    const text = String(b.text || "");
    if (!text.trim()) return J({ ok: false, error: "text required" }, 400);
    const res = await addServersCore(text, env);
    if (res.added) await ghSync(env, "freeservers.json", await fsSnapshot(env));
    res.total = await countFs(env);
    return J(Object.assign({ ok: true }, res));
  }

  if (path === "/admin/servers/update" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    const id = parseInt(b.id, 10);
    if (!id) return J({ ok: false, error: "id required" }, 400);
    if (b.name !== undefined) {
      const nm = String(b.name || "").trim().slice(0, 80);
      if (nm) await env.DB.prepare("UPDATE free_servers SET name = ?2 WHERE id = ?1").bind(id, nm).run();
    }
    if (b.raw !== undefined) {
      // جایگزینی کامل کانفیگ — پروتکل از لینک جدید و تکراری‌نبودن چک می‌شود
      const raw = String(b.raw || "").trim().slice(0, 1200);
      const p = raw.split("://")[0].toLowerCase();
      if (!raw || !PROTOCOLS.includes(p)) return J({ ok: false, error: "bad raw" }, 400);
      const du = await env.DB.prepare("SELECT id FROM free_servers WHERE raw = ?1 AND id <> ?2").bind(raw, id).first();
      if (du) return J({ ok: false, error: "duplicate raw" }, 400);
      await env.DB.prepare("UPDATE free_servers SET raw = ?2, proto = ?3 WHERE id = ?1").bind(id, raw, p).run();
    }
    if (b.label !== undefined) {
      const label = String(b.label || "").trim().slice(0, 40);
      const v = (!label || label === "-" || label === "clear") ? "" : label;
      await env.DB.prepare("UPDATE free_servers SET label = ?2 WHERE id = ?1").bind(id, v).run();
    }
    if (b.enabled !== undefined) {
      await env.DB.prepare("UPDATE free_servers SET enabled = ?2 WHERE id = ?1").bind(id, b.enabled ? 1 : 0).run();
    }
    await ghSync(env, "freeservers.json", await fsSnapshot(env));
    return J({ ok: true });
  }

  if (path === "/admin/servers/delete" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    if (b.all) {
      await env.DB.prepare("DELETE FROM free_servers").run();
    } else {
      const id = parseInt(b.id, 10);
      if (!id) return J({ ok: false, error: "id required" }, 400);
      await env.DB.prepare("DELETE FROM free_servers WHERE id = ?1").bind(id).run();
    }
    await ghSync(env, "freeservers.json", await fsSnapshot(env));
    return J({ ok: true, total: await countFs(env) });
  }

  if (path === "/admin/servers/usage" && request.method === "GET") {
    // مصرف تفکیکی هر سرور رایگان: بایت + تعداد دستگاه + آخرین استفاده — مرتب از پرمصرف‌ترین
    await ensureTrafficSrv(env);
    const rows = await env.DB.prepare("SELECT id, name, label, proto, enabled FROM free_servers").all();
    const agg = await env.DB.prepare(
      "SELECT srv, COUNT(DISTINCT did) AS users, SUM(up + down) AS b, MAX(updated) AS last FROM traffic_srv GROUP BY srv"
    ).all();
    const known = {};
    for (const s of (rows.results || [])) known[String(s.id)] = s;
    const list = [];
    let orphanB = 0, orphanN = 0;
    for (const a of (agg.results || [])) {
      const item = {
        id: a.srv,
        bytes: Number(a.b) || 0,
        users: Number(a.users) || 0,
        last: Number(a.last) || 0
      };
      const k = known[String(a.srv)];
      if (k) {
        item.name = k.name; item.label = k.label || ""; item.proto = k.proto || "";
        item.enabled = Number(k.enabled) === 1;
      } else {
        item.name = "سرور حذف‌شده"; item.orphan = true;
        orphanB += item.bytes; orphanN++;
      }
      list.push(item);
    }
    list.sort((x, y) => y.bytes - x.bytes);
    return J({
      ok: true,
      servers: list.slice(0, 50),
      total_bytes: list.reduce((s, x) => s + x.bytes, 0),
      orphans: { n: orphanN, bytes: orphanB }
    });
  }

  if (path === "/admin/notifications" && request.method === "GET") {
    const rs = await env.DB.prepare(
      "SELECT id, title, body, created_at FROM notifications ORDER BY id DESC LIMIT 50"
    ).all();
    return J({ ok: true, list: rs.results || [] });
  }

  if (path === "/admin/notifications" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    const title = String(b.title || "").trim().slice(0, 80);
    const body = String(b.body || "").trim().slice(0, 400);
    if (!title) return J({ ok: false, error: "title required" }, 400);
    const r = await env.DB.prepare(
      "INSERT INTO notifications (title, body, created_at) VALUES (?1,?2,?3)"
    ).bind(title, body, Date.now()).run();
    await ghSync(env, "notifications.json", await notifSnapshot(env));
    return J({ ok: true, id: (r && r.meta && r.meta.last_row_id) || null });
  }

  if (path === "/admin/users" && request.method === "GET") {
    const rs = await env.DB.prepare(
      "SELECT u.did, u.model, u.android, u.ver, u.lang, u.country, u.ip, u.opens, u.first_seen, u.last_seen, " +
      "COALESCE((SELECT SUM(t.up + t.down) FROM traffic t WHERE t.did = u.did), 0) AS bytes " +
      "FROM users u ORDER BY u.last_seen DESC LIMIT 100"
    ).all();
    return J({ ok: true, users: rs.results || [] });
  }

  if (path === "/admin/syncsnap" && request.method === "POST") {
    const st = await stateSnapshot(env);
    const fs = await fsSnapshot(env);
    const nt = await notifSnapshot(env);
    await ghSync(env, "state.json", st);
    await ghSync(env, "freeservers.json", fs);
    await ghSync(env, "notifications.json", nt);
    return J({ ok: true, servers: (fs.servers || []).length, notifs: (nt.list || []).length, gate: st.gate });
  }

  if (path === "/admin/settings" && request.method === "GET") {
    const mv = Number(await getSetting(env, "min_ver")) || 0;
    const cr = (await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON;
    const owner = await getSetting(env, "owner");
    const blocked = await getBlocked(env);
    return J({ ok: true, min_ver: mv, close_reason: cr, owner, blocked, service_v: SERVICE_V });
  }

  if (path === "/admin/settings" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    let changed = false;
    if (b.min_ver !== undefined) {
      const mv = Math.max(0, Math.round(Number(b.min_ver) || 0));
      await setSetting(env, "min_ver", String(mv));
      changed = true;
    }
    if (b.close_reason !== undefined) {
      const cr = String(b.close_reason || "").trim().slice(0, 400);
      if (cr) { await setSetting(env, "close_reason", cr); changed = true; }
    }
    if (changed) await ghSync(env, "state.json", await stateSnapshot(env));
    return J({ ok: true, min_ver: Number(await getSetting(env, "min_ver")) || 0 });
  }

  // ---------------- نسخه‌های بسته (version kill-switch) ----------------
  // نسخه‌ی مشخصی از برنامه را می‌بندد؛ کاربرِ آن نسخه پیام «این نسخه بسته شده است» می‌بیند.
  if (path === "/admin/versions" && request.method === "GET") {
    const blocked = await getBlocked(env);
    const rs = await env.DB.prepare(
      "SELECT ver, COUNT(*) AS n, MAX(last_seen) AS last_seen FROM users GROUP BY ver ORDER BY last_seen DESC LIMIT 40"
    ).all();
    return J({ ok: true, blocked, versions: rs.results || [] });
  }

  if (path === "/admin/versions/block" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    const ver = normalizeVersion(b.ver);
    if (!ver) return J({ ok: false, error: "ver required" }, 400);
    const list = await getBlocked(env);
    if (!list.includes(ver)) list.push(ver);
    await setBlocked(env, list);
    await ghSync(env, "state.json", await stateSnapshot(env));
    return J({ ok: true, blocked: list });
  }

  if (path === "/admin/versions/unblock" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    const ver = normalizeVersion(b.ver);
    if (!ver) return J({ ok: false, error: "ver required" }, 400);
    let list = await getBlocked(env);
    list = list.filter(v => v !== ver);
    await setBlocked(env, list);
    await ghSync(env, "state.json", await stateSnapshot(env));
    return J({ ok: true, blocked: list });
  }

  if (path === "/admin/versions/clear" && request.method === "POST") {
    await setBlocked(env, []);
    await ghSync(env, "state.json", await stateSnapshot(env));
    return J({ ok: true, blocked: [] });
  }

  if (path === "/admin/notifications/delete" && request.method === "POST") {
    let b = {}; try { b = await request.json(); } catch (e) {}
    const id = parseInt(b.id, 10);
    if (!id) return J({ ok: false, error: "id required" }, 400);
    await env.DB.prepare("DELETE FROM notifications WHERE id = ?1").bind(id).run();
    await ghSync(env, "notifications.json", await notifSnapshot(env));
    return J({ ok: true });
  }

  // ---------------- اشتراک: ساخت/مشاهده/حذف نشست‌های کد اشتراک ----------------
  // ربات تلگرامیِ جدا (Botttt) با کلید ادمین اینجا کد ۱۶ رقمیِ ۱۰ روزه می‌سازد.
  if (path === "/admin/sub/create" && request.method === "POST") {
    await ensureSubTables(env);
    let b = {}; try { b = await request.json(); } catch (e) {}
    const chatId = String(b.chat_id || "").slice(0, 40);
    const days = Math.min(3650, Math.max(1, Math.round(Number(b.days) || 10)));
    if (!chatId) return J({ ok: false, error: "chat_id required" }, 400);
    const now = Date.now();
    // کدِ فعالِ قبلیِ همین کاربر → همان برگردانده می‌شود (هر کاربر فقط یک کد فعال)
    const ex = await env.DB.prepare(
      "SELECT code, created, expires FROM sub_codes WHERE chat_id = ?1 AND active = 1 AND expires > ?2 ORDER BY created DESC LIMIT 1"
    ).bind(chatId, now).first();
    if (ex) {
      const sn = await env.DB.prepare("SELECT COUNT(*) AS n FROM sub_sessions WHERE code = ?1").bind(ex.code).first();
      return J({ ok: true, code: ex.code, days, created: ex.created, expires: ex.expires, sessions: sn ? sn.n : 0, existed: true });
    }
    let code = "", done = false;
    for (let i = 0; i < 6 && !done; i++) {
      code = genSubCode();
      try {
        await env.DB.prepare(
          "INSERT INTO sub_codes (code, chat_id, days, created, expires) VALUES (?1,?2,?3,?4,?5)"
        ).bind(code, chatId, days, now, now + days * 86400000).run();
        done = true;
      } catch (e) { done = false; }
    }
    if (!done) return J({ ok: false, error: "code generation failed" }, 500);
    return J({ ok: true, code, days, created: now, expires: now + days * 86400000, sessions: 0, existed: false });
  }

  if (path === "/admin/sub/info" && request.method === "POST") {
    await ensureSubTables(env);
    let b = {}; try { b = await request.json(); } catch (e) {}
    const chatId = String(b.chat_id || "").slice(0, 40);
    const code0 = normSubCode(b.code);
    let c = null;
    if (validSubCode(code0)) c = await subCodeInfo(env, code0);
    else if (chatId) {
      const row = await env.DB.prepare(
        "SELECT code FROM sub_codes WHERE chat_id = ?1 AND active = 1 ORDER BY created DESC LIMIT 1"
      ).bind(chatId).first();
      if (row) c = await subCodeInfo(env, row.code);
    }
    if (!c) return J({ ok: false, error: "not_found" }, 404);
    const ss = await env.DB.prepare(
      "SELECT did, device, activated, last_seen FROM sub_sessions WHERE code = ?1 ORDER BY activated DESC LIMIT 50"
    ).bind(c.code).all();
    return J(Object.assign({}, c, { session_list: ss.results || [] }));
  }

  if (path === "/admin/sub/kick" && request.method === "POST") {
    await ensureSubTables(env);
    let b = {}; try { b = await request.json(); } catch (e) {}
    const code = normSubCode(b.code);
    if (!validSubCode(code)) return J({ ok: false, error: "code required" }, 400);
    const d = await env.DB.prepare("DELETE FROM sub_sessions WHERE code = ?1").bind(code).run();
    return J({ ok: true, removed: (d.meta && d.meta.changes) || 0 });
  }

  return J({ ok: false, error: "not found" }, 404);
}

// ============================================================ app API

async function report(request, env) {
  let b = {};
  try { b = await request.json(); } catch (e) {}
  const did = String(b.did || "").slice(0, 64);
  if (!did) return json({ ok: false, error: "did required" }, 400);
  const model = String(b.model || "?").slice(0, 80);
  const android = String(b.android || "?").slice(0, 24);
  const ver = String(b.ver || "?").slice(0, 24);
  const lang = String(b.lang || "?").slice(0, 16);
  const country = (request.cf && request.cf.country) ? String(request.cf.country) : "";
  const ip = request.headers.get("cf-connecting-ip") || "";
  const now = Date.now();

  const row = await env.DB.prepare(
    "SELECT opens, ver FROM users WHERE did = ?1"
  ).bind(did).first();

  if (row) {
    await env.DB.prepare(
      "UPDATE users SET last_seen = ?2, opens = opens + 1, model = ?3, android = ?4, ver = ?5, lang = ?6, country = ?7, ip = ?8 WHERE did = ?1"
    ).bind(did, now, model, android, ver, lang, country, ip).run();
  } else {
    await env.DB.prepare(
      "INSERT INTO users (did, model, android, ver, lang, country, ip, opens, first_seen, last_seen) VALUES (?1,?2,?3,?4,?5,?6,?7,1,?8,?8)"
    ).bind(did, model, android, ver, lang, country, ip, now).run();
  }

  const opens = row ? (row.opens + 1) : 1;
  const total = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first();
  const gate = (await getSetting(env, "gate")) || "open";
  const minVer = Number(await getSetting(env, "min_ver")) || 0;

  // notify owner ONLY on brand-new install — exactly ONE message per device.
  // routine opens and version updates stay completely silent (owner request).
  const owner = await getSetting(env, "owner");
  if (owner && !row) {
    const flag = country ? flagEmoji(country) + " " : "";
    const text = "🆕 <b>کاربر جدید Vex Client را نصب کرد!</b>" + "\n" +
      "🌐 IP: <code>" + esc(ip || "?") + "</code>" + (country ? "  " + flag + esc(country) : "") + "\n" +
      "📱 گوشی: " + esc(model) + "\n" +
      "🤖 سیستم: " + esc(android) + "\n" +
      "🏷 نسخه: " + esc(ver) + "\n" +
      "🌍 زبان: " + esc(lang) + "\n" +
      "👥 کل کاربران: " + (total ? total.n : "?") + "\n" +
      "🕐 " + new Date(now).toISOString().replace("T", " ").slice(0, 16) + " UTC";
    await notifySend(env, owner, text);
  }

  return json({ ok: true, gate, reason: gate === "closed" ? ((await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON) : null, min_ver: minVer, blocked: await getBlocked(env), users: total ? total.n : 0 });
}

async function ack(request, env) {
  let b = {};
  try { b = await request.json(); } catch (e) {}
  const state = b.state === "closed" ? "closed" : "open";
  const device = String(b.device || "?").slice(0, 80);
  const owner = await getSetting(env, "owner");
  if (owner) {
    const text = state === "closed"
      ? "🔒 Vex روی دستگاه «" + esc(device) + "» واقعاً قفل شد"
      : "🔓 Vex روی دستگاه «" + esc(device) + "» دوباره باز شد";
    await notifySend(env, owner, text);
  }
  return json({ ok: true });
}

// ============================================================ telegram

async function handleUpdate(up, env, origin) {
  // ---------- callback_query (inline buttons) ----------
  const cb = up && up.callback_query;
  if (cb && cb.data) {
    const chatId = String((cb.message && cb.message.chat && cb.message.chat.id) || cb.from.id);
    const owner = await getSetting(env, "owner");
    // دکمه‌های عمومی کاربران (پیشوند u_) — برای همه آزاد است
    if (cb.data.startsWith("u_")) {
      await answerCb(env, cb.id);
      await handleUserCb(cb.data.slice(2), chatId, env);
      return;
    }
    if (chatId !== owner) { await answerCb(env, cb.id, "دسترسی نداری"); return; }
    await answerCb(env, cb.id);
    await handleCb(cb.data, chatId, env);
    return;
  }

  // ---------- messages ----------
  const msg = up && up.message;
  if (!msg || !msg.chat) return;
  const chatId = String(msg.chat.id);
  const text = (msg.text || "").trim();
  const lc = text.toLowerCase();

  const owner = await getSetting(env, "owner");

  // ---------- کاربران عادی (غیر مالک): منوی عمومی + پشتیبانی ----------
  if (chatId !== owner) {
    await handleUserMsg(msg, chatId, text, lc, env);
    return;
  }

  if (lc.startsWith("/start")) {
    if (!owner) {
      await setSetting(env, "owner", chatId);
      await setSetting(env, "gate", "open");
      await tgSend(env, chatId,
        "👑 تو مدیر Vex شدی!\n\n" +
        "هر کس برای اولین بار Vex Client را نصب کند، با تمام مشخصات (IP، مدل گوشی، نسخه‌ی اندروید و…) بهت خبر می‌دهم — فقط یک بار برای هر دستگاه، بدون اسپم.\n" +
        "مصرف ترافیک همه‌ی کاربران (رایگان/شخصی) هم همین‌جا جمع می‌شود.\n\n" +
        "از منوی زیر همه‌کاره می‌توانی باشی 👇",
        mainMenu());
    } else if (chatId !== owner) {
      await tgSend(env, chatId, "این ربات مخصوص مدیریت Vex Client است.");
    } else {
      await tgSend(env, chatId, "سلام مدیر 👋 از منوی زیر استفاده کن:", mainMenu());
    }
    return;
  }

  // (کاربران عادی بالا مسیریابی شدند — از اینجا به بعد فقط مالک)

  // pending flows (add free server / close with reason / broadcast / channel / reply)
  const pend = await getSetting(env, "pend_" + chatId);
  if (!lc.startsWith("/") && pend) {
    await setSetting(env, "pend_" + chatId, "");
    if (pend === "bcast") { await startBroadcast(env, chatId, chatId, msg.message_id, origin || "https://vex-gate.pages.dev"); return; }
    if (pend === "chan") {
      if (!text) { await tgSend(env, chatId, "متن پست خالی بود — فقط عکس بدون کپشن منتشر نمی‌کنم؛ متنش را با کپشن بفرست.", mainMenu()); return; }
      await doChannelPost(text, chatId, env);
      return;
    }
    if (pend.startsWith("reply_")) {
      const uid = pend.slice(6);
      const okk = await tgCopy(env, chatId, uid, msg.message_id);
      await tgSend(env, chatId,
        okk ? ("✅ پاسخت به کاربر <code>" + esc(uid) + "</code> تحویل شد.")
            : ("⚠️ ارسال به کاربر <code>" + esc(uid) + "</code> ناموفق بود — احتمالاً ربات را بلاک کرده."), mainMenu());
      return;
    }
    if (pend === "add_fs") { await addFreeServers(text, chatId, env); return; }
    if (pend === "notify") { await doNotify(text, chatId, env); return; }
    if (pend.startsWith("sname_")) {
      const id = parseInt(pend.slice(6), 10);
      const label = text.trim().slice(0, 40);
      if (id) {
        const v = (!label || label === "-" || label === "clear") ? "" : label;
        await env.DB.prepare("UPDATE free_servers SET label = ?2 WHERE id = ?1").bind(id, v).run();
        await ghSync(env, "freeservers.json", await fsSnapshot(env));
        await tgSend(env, chatId, v ? ("🏷 پیشوند سرور «" + v + "» ثبت شد — در برنامه‌ی کاربران کنار نام سرور نشان داده می‌شود.") : "🏷 پیشوند این سرور پاک شد.", fsMenuKb());
      }
      return;
    }
    if (pend === "blockver") {
      const vers = text.split(/[،,\s]+/).map(normalizeVersion).filter(Boolean);
      if (!vers.length) {
        await tgSend(env, chatId, "نسخه‌ای پیدا نشد — مثلاً بفرست: <code>3.23.0</code>\n\nبرای لغو /cancel");
        return;
      }
      const list = await getBlocked(env);
      let added = 0;
      for (const v of vers) {
        if (!list.includes(v)) { list.push(v); added++; }
      }
      await setBlocked(env, list);
      await ghSync(env, "state.json", await stateSnapshot(env));
      await tgSend(env, chatId,
        "🚫 " + added + " نسخه بسته شد:\n" +
        vers.map(v => "• " + esc(v)).join("\n") +
        "\n\nکاربران این نسخه‌ها موقع ورود پیام بستن می‌بینند. لیست کامل: 🚫 نسخه‌های بسته", mainMenu());
      return;
    }
    if (pend === "close_reason") {
      const reason = text.slice(0, 400);
      await doClose(chatId, env, reason);
      return;
    }
  }
  if (lc.startsWith("/cancel")) {
    await setSetting(env, "pend_" + chatId, "");
    await tgSend(env, chatId, "لغو شد.", mainMenu());
    return;
  }

  if (lc.startsWith("/close")) {
    const reason = text.replace(/^\/close\s*/i, "").trim();
    await doClose(chatId, env, reason || null);
  } else if (lc.startsWith("/open")) {
    await setSetting(env, "gate", "open");
    await ghSync(env, "state.json", await stateSnapshot(env));
    await tgSend(env, chatId, "🔓 برنامه باز شد و برای همه قابل استفاده است.", mainMenu());
  } else if (lc.startsWith("/stats")) {
    await sendStats(chatId, env);
  } else if (lc.startsWith("/ghstats")) {
    await sendGhStats(chatId, env);
  } else if (lc.startsWith("/traffic")) {
    await sendTraffic(chatId, env);
  } else if (lc.startsWith("/users")) {
    await sendUsers(chatId, env);
  } else if (lc.startsWith("/sname")) {
    const rest = text.replace(/^\/sname\s*/i, "").trim();
    await snameCmd(rest, chatId, env);
  } else if (lc.startsWith("/notify")) {
    const rest = text.replace(/^\/notify\s*/i, "").trim();
    if (rest && rest.includes("|")) { await doNotify(rest, chatId, env); return; }
    await setSetting(env, "pend_" + chatId, "notify");
    await tgSend(env, chatId,
      "📢 <b>ارسال اعلان به کاربران</b>\n\n" +
      "عنوان و متن اعلان را در یک پیام بفرست، با این شکل:\n" +
      "<code>عنوان اعلان | متن توضیحات اعلان</code>\n\n" +
      "اعلان برای همه‌ی کاربران برنامه (که اجازه‌ی اعلان داده‌اند) به‌صورت سیستم‌عاملی بالای صفحه می‌آید.\n\nبرای لغو /cancel");
  } else if (lc.startsWith("/fsadd")) {
    const rest = text.replace(/^\/fsadd\s*/i, "").trim();
    if (rest) { await addFreeServers(rest, chatId, env); return; }
    await askAddFreeServer(chatId, env);
  } else if (lc.startsWith("/panel")) {
    await tgSend(env, chatId,
      "🌐 <b>پنل مدیریت وب Vex</b>\n\n" +
      "همه‌ی اختیارات ربات + آمار و نمودار نصب، همین‌جا در مرورگر:\n" +
      "🔒 https://panel.demonvpn.shop\n" +
      "(اگر نشد: https://vex-panel.pages.dev)\n\n" +
      "🔑 کلید ورود همان کلید ادمین است.",
      kb([[{ text: "🌐 بازکردن پنل", url: "https://panel.demonvpn.shop" }], [{ text: "⬅️ منوی اصلی", callback_data: "menu" }]]));
  } else if (lc.startsWith("/minver")) {
    await minverCmd(text.replace(/^\/minver\s*/i, "").trim(), chatId, env);
  } else if (lc.startsWith("/blocked") || lc.startsWith("/vers")) {
    await sendVersions(chatId, env);
  } else if (lc.startsWith("/ping")) {
    const t0 = Date.now();
    await env.DB.prepare("SELECT 1 AS x").first();
    const dbms = Date.now() - t0;
    const fs = await countFs(env);
    const gate = (await getSetting(env, "gate")) || "open";
    await tgSend(env, chatId,
      "🏓 سرویس سالم است\n" +
      "🗄 دیتابیس: " + dbms + "ms\n" +
      "🖥 سرورهای رایگان فعال: " + fs + "\n" +
      "🚦 دروازه: " + (gate === "closed" ? "🔒 بسته" : "🔓 باز") + "\n" +
      "⚙️ نسخه‌ی سرویس: v" + SERVICE_V, mainMenu());
  } else if (lc.startsWith("/reload")) {
    await syncSnaps(chatId, env);
  } else if (lc.startsWith("/broadcast")) {
    const rest = text.replace(/^\/broadcast\s*/i, "").trim();
    if (!rest) {
      await setSetting(env, "pend_" + chatId, "bcast");
      await tgSend(env, chatId,
        "📣 <b>پیام همگانی</b>\n\n" +
        "پیامت را بفرست — عیناً (همان قالب؛ عکس/ویدیو/فایل هم می‌شود) برای همه‌ی کاربران ربات کپی می‌شود.\n" +
        "پخش دسته‌دسته انجام می‌شود و در پایان گزارش ✅/❌ می‌گیری.\n\nبرای لغو /cancel", mainMenu());
      return;
    }
    const sent = await tgSendRet(env, chatId, rest);
    if (sent && sent.ok && sent.result && sent.result.message_id) {
      await startBroadcast(env, chatId, chatId, sent.result.message_id, origin || "https://vex-gate.pages.dev");
    } else {
      await tgSend(env, chatId, "⚠️ شروع پخش ناموفق بود — دوباره امتحان کن.", mainMenu());
    }
  } else if (lc.startsWith("/chanpost") || lc.startsWith("/announce")) {
    const rest = text.replace(/^\/(chanpost|announce)\s*/i, "").trim();
    if (rest) { await doChannelPost(rest, chatId, env); return; }
    await setSetting(env, "pend_" + chatId, "chan");
    await tgSend(env, chatId,
      "📢 <b>پست کانال</b>\n\n" +
      "متن پست را بفرست — در @vex_vpnfree منتشر می‌شود و دکمه‌ی «📥 دانلود Vex» زیرش اضافه می‌شود.\n" +
      "HTML (بولد/ایتالیک) پشتیبانی می‌شود.\n\nبرای لغو /cancel", mainMenu());
  } else if (lc.startsWith("/reply")) {
    const rest = text.replace(/^\/reply\s*/i, "").trim();
    const sp = rest.indexOf(" ");
    const uid = sp > 0 ? rest.slice(0, sp).trim() : "";
    const body = sp > 0 ? rest.slice(sp + 1).trim() : "";
    if (!/^\d+$/.test(uid) || !body) {
      await tgSend(env, chatId, "شکل درست: <code>/reply 123456789 سلام!</code>\nیا روی دکمه‌ی «📩 پاسخ» زیر پیام پشتیبانی بزن.", mainMenu());
      return;
    }
    const sent = await tgSendRet(env, uid, body);
    await tgSend(env, chatId,
      (sent && sent.ok) ? "✅ پاسخ ارسال شد." : "⚠️ ارسال ناموفق — شاید کاربر ربات را بلاک کرده باشد.", mainMenu());
  } else if (lc.startsWith("/ban")) {
    const uid = text.replace(/^\/ban\s*/i, "").trim().split(/\s+/)[0];
    if (!/^\d+$/.test(uid)) { await tgSend(env, chatId, "شکل: <code>/ban 123456789</code>", mainMenu()); return; }
    await ensureBotUsersTable(env);
    await env.DB.prepare(
      "INSERT INTO bot_users (chat_id, name, username, joined_at, last_seen, msgs, banned) VALUES (?1,'بن‌شده','',?2,?2,0,1) " +
      "ON CONFLICT(chat_id) DO UPDATE SET banned = 1"
    ).bind(uid, Date.now()).run();
    await tgSend(env, chatId, "⛔️ کاربر <code>" + esc(uid) + "</code> از ربات بن شد.", mainMenu());
  } else if (lc.startsWith("/unban")) {
    const uid = text.replace(/^\/unban\s*/i, "").trim().split(/\s+/)[0];
    if (!/^\d+$/.test(uid)) { await tgSend(env, chatId, "شکل: <code>/unban 123456789</code>", mainMenu()); return; }
    await ensureBotUsersTable(env);
    await env.DB.prepare("UPDATE bot_users SET banned = 0 WHERE chat_id = ?1").bind(uid).run();
    await tgSend(env, chatId, "✅ کاربر <code>" + esc(uid) + "</code> آنبن شد.", mainMenu());
  } else if (lc.startsWith("/ci")) {
    await sendCi(chatId, env);
  } else if (lc.startsWith("/tgu")) {
    await sendBotUsers(chatId, env);
  } else if (lc.startsWith("/menu") || lc.startsWith("/help")) {
    await sendHelp(chatId, env);
  } else if (pend === null) {
    await tgSend(env, chatId, "از منوی زیر استفاده کن 👇", mainMenu());
  }
}

// ---------------- callbacks (inline buttons) ----------------

async function handleCb(data, chatId, env) {
  if (data === "notify") {
    await setSetting(env, "pend_" + chatId, "notify");
    await askNotify(chatId, env);
    return;
  }
  if (data === "menu") {
    await tgSend(env, chatId, "منوی مدیریت Vex:", mainMenu());
  } else if (data === "stats") {
    await sendStats(chatId, env);
  } else if (data === "ghstats") {
    await sendGhStats(chatId, env);
  } else if (data === "traffic") {
    await sendTraffic(chatId, env);
  } else if (data === "users") {
    await sendUsers(chatId, env);
  } else if (data === "state") {
    await sendState(chatId, env);
  } else if (data === "close") {
    await tgSend(env, chatId,
      "🔒 <b>بستن برنامه</b>\n\n" +
      "انتخاب کن کاربران موقع ورود چه پیامی ببینند " +
      "(پیام به‌صورت انگلیسی روی صفحه‌ی کاربر نمایش داده می‌شود):\n\n" +
      "🛠 <code>" + CLOSE_REASON_UPDATE + "</code>\n" +
      "⚠️ <code>" + CLOSE_REASON_TECH + "</code>\n" +
      "🔒 <code>" + DEFAULT_CLOSE_REASON + "</code>",
      closeKb());
  } else if (data === "close_u") {
    await doClose(chatId, env, CLOSE_REASON_UPDATE);
  } else if (data === "close_t") {
    await doClose(chatId, env, CLOSE_REASON_TECH);
  } else if (data === "close_a") {
    await doClose(chatId, env, DEFAULT_CLOSE_REASON);
  } else if (data === "close_r") {
    await setSetting(env, "pend_" + chatId, "close_reason");
    await tgSend(env, chatId,
      "✍️ پیام سفارشی را بفرست — دقیقاً همین متن روی صفحه‌ی کاربران (انگلیسی) نشان داده می‌شود:\n\n" +
      "مثلاً: <code>App access closed. We are updating. Please try again later.</code>\n\nبرای لغو /cancel");
  } else if (data === "open") {
    await setSetting(env, "gate", "open");
    await ghSync(env, "state.json", await stateSnapshot(env));
    await tgSend(env, chatId, "🔓 برنامه باز شد و برای همه قابل استفاده است.", mainMenu());
  } else if (data === "fs") {
    await sendFsMenu(chatId, env);
  } else if (data === "fs_add") {
    await askAddFreeServer(chatId, env);
  } else if (data.startsWith("fs_name_")) {
    const id = parseInt(data.slice(8), 10);
    await setSetting(env, "pend_" + chatId, "sname_" + id);
    await tgSend(env, chatId,
      "🏷 <b>پیشوند سرور</b>\n\n" +
      "متن/ایموجی پیشوند را بفرست (مثلاً <code>🇩🇪</code> یا <code>ویژه</code>).\n" +
      "در برنامه کنار نام سرور، طلایی نشان داده می‌شود.\n" +
      "برای پاک‌کردن پیشوند بنویس: <code>-</code>\n\nبرای لغو /cancel");
  } else if (data === "fs_list") {
    await sendFsList(chatId, env);
  } else if (data === "notifs") {
    await sendNotifs(chatId, env);
  } else if (data === "minver") {
    await minverCmd("", chatId, env);
  } else if (data === "vers") {
    await sendVersions(chatId, env);
  } else if (data === "ver_add") {
    await setSetting(env, "pend_" + chatId, "blockver");
    await tgSend(env, chatId,
      "🚫 <b>بستن نسخه</b>\n\n" +
      "شماره‌ی نسخه را بفرست — چند نسخه را با ویرگول یا فاصله جدا کن:\n" +
      "<code>3.23.0, 3.22.0</code>\n\n" +
      "کاربرانی که همان نسخه را دارند موقع ورود پیام «This version has been disabled…» می‌بینند و وارد نمی‌شوند.\n\nبرای لغو /cancel");
  } else if (data === "ver_clear") {
    await setBlocked(env, []);
    await ghSync(env, "state.json", await stateSnapshot(env));
    await tgSend(env, chatId, "♻️ همه‌ی نسخه‌های بسته آزاد شدند — همه می‌توانند وارد شوند.");
    await sendVersions(chatId, env);
  } else if (data.startsWith("vblk_")) {
    const ver = normalizeVersion(data.slice(5));
    if (ver) {
      const list = await getBlocked(env);
      if (!list.includes(ver)) list.push(ver);
      await setBlocked(env, list);
      await ghSync(env, "state.json", await stateSnapshot(env));
      await tgSend(env, chatId, "🚫 نسخه‌ی <b>" + esc(ver) + "</b> بسته شد — کاربرانش دیگر وارد نمی‌شوند.");
    }
    await sendVersions(chatId, env);
  } else if (data.startsWith("vunblk_")) {
    const ver = normalizeVersion(data.slice(7));
    if (ver) {
      let list = await getBlocked(env);
      list = list.filter(v => v !== ver);
      await setBlocked(env, list);
      await ghSync(env, "state.json", await stateSnapshot(env));
      await tgSend(env, chatId, "✅ نسخه‌ی <b>" + esc(ver) + "</b> باز شد.");
    }
    await sendVersions(chatId, env);
  } else if (data === "syncsnap") {
    await syncSnaps(chatId, env);
  } else if (data === "fs_delall") {
    const n = await countFs(env);
    if (!n) { await tgSend(env, chatId, "لیست سرورهای رایگان خالی است."); return; }
    await tgSend(env, chatId, "⚠️ مطمئنی همه‌ی " + n + " سرور رایگان حذف شود؟",
      kb([[{ text: "✅ بله حذف همه", callback_data: "fs_delall_yes" }, { text: "❌ نه", callback_data: "fs" }]]));
  } else if (data === "fs_delall_yes") {
    await env.DB.prepare("DELETE FROM free_servers").run();
    await ghSync(env, "freeservers.json", await fsSnapshot(env));
    await tgSend(env, chatId, "🗑 همه‌ی سرورهای رایگان حذف شدند.", fsMenuKb());
  } else if (data.startsWith("fs_del_")) {
    const id = parseInt(data.slice(7), 10);
    if (id) {
      await env.DB.prepare("DELETE FROM free_servers WHERE id = ?1").bind(id).run();
      await ghSync(env, "freeservers.json", await fsSnapshot(env));
    }
    await sendFsList(chatId, env);
  } else if (data.startsWith("fs_tgl_")) {
    const id = parseInt(data.slice(7), 10);
    if (id) {
      const row = await env.DB.prepare("SELECT enabled FROM free_servers WHERE id = ?1").bind(id).first();
      if (row) {
        const nv = (Number(row.enabled) === 1) ? 0 : 1;
        await env.DB.prepare("UPDATE free_servers SET enabled = ?2 WHERE id = ?1").bind(id, nv).run();
        await ghSync(env, "freeservers.json", await fsSnapshot(env));
      }
    }
    await sendFsList(chatId, env);
  } else if (data === "bcast") {
    await setSetting(env, "pend_" + chatId, "bcast");
    await tgSend(env, chatId,
      "📣 <b>پیام همگانی</b>\n\n" +
      "پیامت را بفرست — عیناً (همان قالب؛ عکس/ویدیو/فایل هم می‌شود) برای همه‌ی کاربران ربات کپی می‌شود.\n" +
      "پخش دسته‌دسته انجام می‌شود و در پایان گزارش ✅/❌ می‌گیری.\n\nبرای لغو /cancel", mainMenu());
  } else if (data === "chan") {
    await setSetting(env, "pend_" + chatId, "chan");
    await tgSend(env, chatId,
      "📢 <b>پست کانال</b>\n\n" +
      "متن پست را بفرست — در @vex_vpnfree منتشر می‌شود و دکمه‌ی «📥 دانلود Vex» زیرش اضافه می‌شود.\n" +
      "HTML (بولد/ایتالیک) پشتیبانی می‌شود.\n\nبرای لغو /cancel", mainMenu());
  } else if (data === "ci") {
    await sendCi(chatId, env);
  } else if (data === "tgu") {
    await sendBotUsers(chatId, env);
  } else if (data === "help") {
    await sendHelp(chatId, env);
  } else if (data.startsWith("supr_")) {
    const uid = data.slice(5).replace(/[^0-9]/g, "");
    if (uid) {
      await setSetting(env, "pend_" + chatId, "reply_" + uid);
      await tgSend(env, chatId,
        "✍️ پاسخ به کاربر <code>" + esc(uid) + "</code> را بنویس و بفرست (عکس/فایل هم می‌شود).\n\nبرای لغو /cancel", mainMenu());
    }
  }
}

// ---------------- bot builders ----------------

function mainMenu() {
  return kb([
    [{ text: "📊 آمار", callback_data: "stats" }, { text: "👥 کاربران", callback_data: "users" }],
    [{ text: "📈 مصرف ترافیک", callback_data: "traffic" }, { text: "📥 نصب از گیت‌هاب", callback_data: "ghstats" }],
    [{ text: "📣 پیام همگانی", callback_data: "bcast" }, { text: "📢 پست کانال", callback_data: "chan" }],
    [{ text: "🧑‍💬 کاربران ربات", callback_data: "tgu" }, { text: "🧪 وضعیت بیلد", callback_data: "ci" }],
    [{ text: "🖥 سرورهای رایگان", callback_data: "fs" }],
    [{ text: "📢 ارسال اعلان", callback_data: "notify" }, { text: "📜 اعلان‌های اخیر", callback_data: "notifs" }],
    [{ text: "🟢 وضعیت برنامه", callback_data: "state" }],
    [{ text: "🔒 بستن برنامه", callback_data: "close" }, { text: "🔓 باز کردن", callback_data: "open" }],
    [{ text: "🚫 نسخه‌های بسته", callback_data: "vers" }, { text: "🔄 حداقل نسخه", callback_data: "minver" }],
    [{ text: "❓ راهنما", callback_data: "help" }, { text: "🌐 پنل مدیریت وب", url: "https://panel.demonvpn.shop" }],
    [{ text: "🔄 همگام‌سازی", callback_data: "syncsnap" }]
  ]);
}

function closeKb() {
  return kb([
    [{ text: "🛠 Updating the app", callback_data: "close_u" }],
    [{ text: "⚠️ Technical problem", callback_data: "close_t" }],
    [{ text: "🔒 Admin closed the app", callback_data: "close_a" }],
    [{ text: "✍️ پیام سفارشی", callback_data: "close_r" }],
    [{ text: "⬅️ منوی اصلی", callback_data: "menu" }]
  ]);
}

function fsMenuKb() {
  return kb([
    [{ text: "➕ افزودن سرور", callback_data: "fs_add" }, { text: "📃 لیست سرورها", callback_data: "fs_list" }],
    [{ text: "🗑 حذف همه", callback_data: "fs_delall" }],
    [{ text: "⬅️ منوی اصلی", callback_data: "menu" }]
  ]);
}

function kb(rows) {
  return { inline_keyboard: rows };
}

async function trafficTotals(env) {
  const tr = await env.DB.prepare(
    "SELECT type, COALESCE(SUM(up + down),0) AS b FROM traffic GROUP BY type"
  ).all();
  let freeB = 0, privB = 0;
  for (const r of (tr.results || [])) {
    if (r.type === "free") freeB = Number(r.b) || 0; else privB = Number(r.b) || 0;
  }
  return { freeB, privB };
}

async function sendStats(chatId, env) {
  const t = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first();
  const today = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM users WHERE first_seen >= ?1"
  ).bind(Date.now() - 86400000).first();
  const active = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM users WHERE last_seen >= ?1"
  ).bind(Date.now() - 86400000).first();
  const active7 = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM users WHERE last_seen >= ?1"
  ).bind(Date.now() - 7 * 86400000).first();
  const op = await env.DB.prepare("SELECT COALESCE(SUM(opens),0) AS n FROM users").first();
  const fs = await countFs(env);
  const gate = (await getSetting(env, "gate")) || "open";
  const tt = await trafficTotals(env);
  const nc = await env.DB.prepare("SELECT COUNT(*) AS n FROM notifications").first();
  const mv = Number(await getSetting(env, "min_ver")) || 0;
  const bl = await getBlocked(env);
  // ---- نمودار نصب ۷ روز اخیر (میله‌ای متنی) ----
  const dayMs = 86400000;
  const today0 = Math.floor(Date.now() / dayMs) * dayMs;
  const rs7 = await env.DB.prepare("SELECT first_seen FROM users WHERE first_seen >= ?1").bind(today0 - 6 * dayMs).all();
  const days = [0, 0, 0, 0, 0, 0, 0];
  for (const r of (rs7.results || [])) {
    const d = Math.floor(Number(r.first_seen) / dayMs) * dayMs;
    const idx = 6 - Math.round((today0 - d) / dayMs);
    if (idx >= 0 && idx <= 6) days[idx]++;
  }
  const mx = Math.max(1, ...days);
  let chart = "";
  for (let i = 0; i < 7; i++) {
    const barN = days[i] ? Math.max(1, Math.round((days[i] / mx) * 8)) : 0;
    const dt = new Date(today0 - (6 - i) * dayMs);
    chart += "\n<code>" + "▇".repeat(barN) + (barN ? "" : "·") + "</code> " + days[i] +
      " · " + (dt.getUTCMonth() + 1) + "/" + dt.getUTCDate();
  }
  await tgSend(env, chatId,
    "📊 <b>آمار Vex Client</b>\n" +
    "👥 کل کاربران (نصب‌ها): <b>" + (t ? t.n : 0) + "</b>\n" +
    "🆕 نصب ۲۴ ساعت گذشته: " + (today ? today.n : 0) + "\n" +
    "🟢 فعال ۲۴ ساعت: " + (active ? active.n : 0) + " · فعال ۷ روز: " + (active7 ? active7.n : 0) + "\n" +
    "🔁 مجموع ورودها: " + (op ? op.n : 0) + "\n" +
    "🖥 سرورهای رایگان فعال: " + fs + "\n" +
    "📢 اعلان‌های ارسال‌شده: " + (nc ? nc.n : 0) + "\n" +
    "🔄 حداقل نسخه‌ی مجاز: " + (mv ? mv : "—") + "\n" +
    "🚫 نسخه‌های بسته: " + (bl.length ? bl.map(v => esc(v)).join("، ") : "—") + "\n" +
    "🆓 مصرف سرورهای رایگان: " + fmtB(tt.freeB) + "\n" +
    "🔑 مصرف سرورهای شخصی: " + fmtB(tt.privB) + "\n" +
    "🧮 مصرف کل: <b>" + fmtB(tt.freeB + tt.privB) + "</b>\n" +
    "وضعیت برنامه: " + (gate === "closed" ? "🔒 بسته" : "🔓 باز") + "\n" +
    "\n📈 <b>نصب ۷ روز اخیر:</b>" + chart, mainMenu());
}

/**
 * جمع‌آوری آمار دانلود از گیت‌هاب — هم برای ربات (/ghstats) هم برای پنل ادمین.
 * توکن/ریپو از D1 settings (gh_token / gh_repo) — بدون توکن هم کار می‌کند.
 */
async function ghReleaseStats(env) {
  let repo = env.GH_REPO || (await getSetting(env, "gh_repo")) || DEFAULT_REPO;
  repo = String(repo).replace(/^https?:\/\/github\.com\//i, "").replace(/\.git$/i, "").replace(/^\/+|\/+$/g, "");
  const token = env.GH_TOKEN || (await getSetting(env, "gh_token"));
  const hdr = { "user-agent": "vex-gate", "accept": "application/vnd.github+json" };
  if (token) hdr.authorization = "Bearer " + token;
  let rels = null;
  try {
    const res = await fetch("https://api.github.com/repos/" + repo + "/releases?per_page=100", { headers: hdr });
    if (res.ok) {
      const j = await res.json();
      if (Array.isArray(j)) rels = j;
    }
  } catch (e) {}
  if (!rels) return { ok: false, repo };
  const rows = [];
  let total = 0, latestDl = 0;
  for (const r of rels) {
    if (r.draft) continue;
    let verDl = 0, latDl = 0;
    for (const a of (r.assets || [])) {
      const c = Number(a.download_count) || 0;
      total += c;
      if (a.name === LATEST_ASSET) latDl += c; else verDl += c;
    }
    latestDl += latDl;
    rows.push({ tag: String(r.tag_name || r.name || "?"), ver: verDl, lat: latDl, date: String(r.published_at || "").slice(0, 10) });
  }
  return { ok: true, repo, total, latest: latestDl, rows };
}

/** آمار نصب از گیت‌هاب برای ربات */
async function sendGhStats(chatId, env) {
  const s = await ghReleaseStats(env);
  if (!s.ok) {
    await tgSend(env, chatId, "⚠️ گرفتن آمار از گیت‌هاب ممکن نشد — چند لحظه بعد دوباره امتحان کن.", mainMenu());
    return;
  }
  const rows = s.rows;
  const total = s.total;
  const latestDl = s.latest;
  let out = "📥 <b>آمار نصب از گیت‌هاب</b>\n\n" +
    "📦 کل دانلودهای APK: <b>" + total + "</b>\n" +
    "🔗 دانلود از لینک Latest: " + latestDl + "\n";
  if (rows.length) {
    out += "\n🔢 <b>نصب به تفکیک نسخه:</b>";
    for (const r of rows.slice(0, 14)) {
      out += "\n• " + esc(r.tag) + " — <b>" + r.ver + "</b> نصب" +
        (r.date ? "  <i>(" + r.date + ")</i>" : "");
    }
    if (rows.length > 14) out += "\n… و " + (rows.length - 14) + " نسخه‌ی دیگر";
  } else {
    out += "\nهنوز Release‌ای منتشر نشده است.";
  }
  out += "\n\n<i>هر دانلود APK از صفحه‌ی Releases گیت‌هاب = یک نصب. این آمار مستقیم از گیت‌هاب خوانده می‌شود.</i>";
  await tgSend(env, chatId, out, mainMenu());
}

async function sendTraffic(chatId, env) {
  const tt = await trafficTotals(env);
  const top = await env.DB.prepare(
    "SELECT u.model AS model, u.country AS country, t.type AS type, (t.up + t.down) AS b " +
    "FROM traffic t JOIN users u ON u.did = t.did ORDER BY b DESC LIMIT 8"
  ).all();
  let out = "📈 <b>مصرف ترافیک کاربران Vex</b>\n\n" +
    "🆓 سرورهای رایگان: <b>" + fmtB(tt.freeB) + "</b>\n" +
    "🔑 سرورهای شخصی (کاربران): <b>" + fmtB(tt.privB) + "</b>\n" +
    "🧮 مصرف کل همه‌ی کاربران: <b>" + fmtB(tt.freeB + tt.privB) + "</b>";
  if ((top.results || []).length) {
    out += "\n\n🏆 <b>پرمصرف‌ترین کاربران</b>";
    let i = 1;
    for (const r of top.results) {
      out += "\n" + i + ". " + (r.country ? flagEmoji(r.country) + " " : "") +
        esc(r.model || "?") + " · " + (r.type === "free" ? "🆓" : "🔑") + " " + fmtB(r.b);
      i++;
    }
  } else {
    out += "\n\nهنوز مصرفی ثبت نشده — به‌محض اتصال کاربران، مصرفشان اینجا جمع می‌شود.";
  }
  await tgSend(env, chatId, out, mainMenu());
}

async function sendUsers(chatId, env) {
  const rs = await env.DB.prepare(
    "SELECT u.model, u.android, u.ver, u.country, u.ip, u.opens, u.last_seen, " +
    "COALESCE((SELECT SUM(t.up + t.down) FROM traffic t WHERE t.did = u.did), 0) AS bytes " +
    "FROM users u ORDER BY u.last_seen DESC LIMIT 20"
  ).all();
  let out = "👥 <b>آخرین کاربران</b>\n";
  for (const r of (rs.results || [])) {
    out += "\n" + (r.country ? flagEmoji(r.country) + " " : "") +
      "IP: <code>" + esc(r.ip || "?") + "</code> · " + esc(r.model || "?") +
      " · " + esc(r.android || "?") +
      " · v" + esc(r.ver || "?") +
      " · " + (r.opens || 1) + "×" +
      " · 📶 " + fmtB(r.bytes) +
      " · " + new Date(r.last_seen).toISOString().slice(5, 16).replace("T", " ");
  }
  if (!(rs.results || []).length) out += "\nهنوز کاری ثبت نشده";
  await tgSend(env, chatId, out);
}

async function sendState(chatId, env) {
  const gate = (await getSetting(env, "gate")) || "open";
  const fs = await countFs(env);
  const reason = (await getSetting(env, "close_reason")) || "";
  let out = "🟢 <b>وضعیت برنامه</b>\n" +
    "دروازه: " + (gate === "closed" ? "🔒 بسته" : "🔓 باز") + "\n" +
    "🖥 سرورهای رایگان: " + fs;
  if (gate === "closed") out += "\n📝 پیام کاربران: " + (reason || DEFAULT_CLOSE_REASON);
  await tgSend(env, chatId, out, mainMenu());
}

async function doClose(chatId, env, reason) {
  const r = (reason && reason.trim()) || DEFAULT_CLOSE_REASON;
  await setSetting(env, "gate", "closed");
  await setSetting(env, "close_reason", r);
  await ghSync(env, "state.json", await stateSnapshot(env));
  await tgSend(env, chatId,
    "🔒 برنامه برای همه بسته شد.\n\n📝 کاربران این را می‌بینند:\n«" + r + "»\n\nبا دکمه‌ی 🔓 یا /open دوباره باز می‌شود.", mainMenu());
}

// ---------------- bot users (public side of the bot) ----------------

let botUsersTableReady = false;
async function ensureBotUsersTable(env) {
  if (botUsersTableReady) return;
  await env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS bot_users (" +
    "chat_id TEXT PRIMARY KEY, name TEXT, username TEXT, joined_at INTEGER, " +
    "last_seen INTEGER, msgs INTEGER DEFAULT 0, banned INTEGER DEFAULT 0)"
  ).run();
  botUsersTableReady = true;
}

// ============================================================ اشتراک (subscription)

// کدهای اشتراک ۱۶ رقمی + نشست‌های هر کد (هر دستگاهی که با کد فعال شده باشد)
let subTablesReady = false;
async function ensureSubTables(env) {
  if (subTablesReady) return;
  await env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS sub_codes (" +
    "code TEXT PRIMARY KEY, chat_id TEXT, days INTEGER DEFAULT 10, " +
    "created INTEGER, expires INTEGER, active INTEGER DEFAULT 1)"
  ).run();
  await env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS sub_sessions (" +
    "code TEXT, did TEXT, device TEXT, activated INTEGER, last_seen INTEGER, " +
    "PRIMARY KEY (code, did))"
  ).run();
  // گزارش رویدادها برای پنل مدیریت ربات توکن (اتصال/خروج کاربران برنامه)
  await env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS vx_events (" +
    "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, type TEXT, code TEXT, chat_id TEXT, " +
    "device TEXT, android TEXT, ver TEXT, first INTEGER DEFAULT 0)"
  ).run();
  subTablesReady = true;
}

// مصرف تفکیکی هر سرور رایگان (نسخه‌ی ۴ به بعد) — فقط یک‌بار ساخته می‌شود
let tSrvReady = false;
async function ensureTrafficSrv(env) {
  if (tSrvReady) return;
  await env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS traffic_srv (" +
    "srv INTEGER, did TEXT, up INTEGER DEFAULT 0, down INTEGER DEFAULT 0, " +
    "updated INTEGER, PRIMARY KEY (srv, did))"
  ).run();
  tSrvReady = true;
}

/** ثبت رویداد برای پنل ربات (آمار روزانه + گزارش اتصال/خروج) */
async function logEvent(env, type, code, chatId, device, android, ver, first) {
  try {
    await ensureSubTables(env);
    await env.DB.prepare(
      "INSERT INTO vx_events (ts, type, code, chat_id, device, android, ver, first) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)"
    ).bind(Date.now(), String(type || ""), code || "", chatId == null ? "" : String(chatId),
           device || "", android || "", ver || "", first ? 1 : 0).run();
  } catch (e) {}
}

/** زمان فارسی برای پیام‌های اطلاع‌رسانی (مثل ۱۴۰۵/۸/۹ ۱۴:۳۰) */
function faTime(ms) {
  try {
    return new Intl.DateTimeFormat("fa-IR", { dateStyle: "medium", timeStyle: "short" }).format(new Date(ms));
  } catch (e) {
    try { return new Date(ms).toISOString().replace("T", " ").slice(0, 16); } catch (e2) { return ""; }
  }
}

/** پاک‌سازی ورودی کد اشتراک: حروف انگلیسی بزرگ + ارقام؛ خط‌تیره/فاصله/ارقام فارسی حذف یا تبدیل می‌شود */
function normSubCode(s) {
  let out = "";
  const src = String(s || "");
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (ch >= "A" && ch <= "Z") out += ch;
    else if (ch >= "a" && ch <= "z") out += ch.toUpperCase();
    else if (ch >= "0" && ch <= "9") out += ch;
    else if (ch >= "۰" && ch <= "۹") out += String(ch.charCodeAt(0) - 1776);
    else if (ch >= "٠" && ch <= "٩") out += String(ch.charCodeAt(0) - 1632);
  }
  return out;
}
/** کد معتبر: ۳۰ حرف انگلیسی (فرمت جدید توکن) یا ۲۷ حرف / ۱۶ رقم (کدهای قدیمی — برای قطع نشدن اشتراک‌های جاری) */
function validSubCode(c) { return c.length === 30 || c.length === 27 || c.length === 16; }

/** کد تصادفی ۳۰ حرفی از حروف انگلیسی بزرگ */
function genSubCode() {
  const a = new Uint8Array(30);
  crypto.getRandomValues(a);
  let s = "";
  for (let i = 0; i < 30; i++) s += String.fromCharCode(65 + (a[i] % 26));
  return s;
}

// ---------- ربات اطلاع‌رسانی (مستقل از ربات مدیریتی) ----------
// اعلان نصب/قفل برنامه به جای ربات قدیمی، از طریق ربات توکن (@Appvexvpnbot) به مدیر می‌رود.
// توکن از env NOTIFY_BOT_TOKEN یا از D1 settings (notify_bot_token) خوانده می‌شود؛
// اگر هیچ‌کدام نبود، مثل قبل همان BOT_TOKEN استفاده می‌شود.
let notifyTokCache = { v: null, at: 0 };
async function notifyToken(env) {
  if (env.NOTIFY_BOT_TOKEN) return env.NOTIFY_BOT_TOKEN;
  const now = Date.now();
  if (notifyTokCache.v !== null && now - notifyTokCache.at < 60000) return notifyTokCache.v;
  let v = "";
  try { v = (await getSetting(env, "notify_bot_token")) || ""; } catch (e) { v = ""; }
  notifyTokCache.v = v; notifyTokCache.at = now;
  return v;
}
async function notifySend(env, chat, text) {
  try {
    const t = await notifyToken(env);
    if (!t || !chat) return;
    await fetch("https://api.telegram.org/bot" + t + "/sendMessage", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ chat_id: String(chat), text, parse_mode: "HTML" })
    });
  } catch (e) {}
}

/** خواندن یک کد + تعداد نشست‌های فعالش */
async function subCodeInfo(env, code) {
  const c = await env.DB.prepare(
    "SELECT code, chat_id, days, created, expires, active FROM sub_codes WHERE code = ?1"
  ).bind(code).first();
  if (!c) return null;
  const sn = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM sub_sessions WHERE code = ?1"
  ).bind(code).first();
  return {
    code: c.code, chat_id: c.chat_id, days: c.days || 10,
    created: c.created, expires: c.expires, active: !!c.active,
    sessions: sn ? sn.n : 0
  };
}

function userMenu() {
  return kb([
    [{ text: "📥 دانلود Vex", callback_data: "u_dl" }, { text: "🟢 وضعیت سرویس", callback_data: "u_status" }],
    [{ text: "🆘 پشتیبانی", callback_data: "u_support" }, { text: "ℹ️ راهنما", callback_data: "u_help" }],
    [{ text: "📣 کانال Vex", url: "https://t.me/vex_vpnfree" }]
  ]);
}

async function handleUserMsg(msg, chatId, text, lc, env) {
  await ensureBotUsersTable(env);
  const from = msg.from || {};
  const name = [from.first_name, from.last_name].filter(Boolean).join(" ").slice(0, 80) || "کاربر";
  const row = await env.DB.prepare("SELECT banned FROM bot_users WHERE chat_id = ?1").bind(chatId).first();
  if (!row) {
    await env.DB.prepare(
      "INSERT INTO bot_users (chat_id, name, username, joined_at, last_seen, msgs, banned) VALUES (?1,?2,?3,?4,?4,1,0)"
    ).bind(chatId, name, String(from.username || "").slice(0, 64), Date.now()).run();
    const owner = await getSetting(env, "owner");
    if (owner) {
      await tgSend(env, owner,
        "👤 <b>کاربر جدید ربات</b>\n" + esc(name) +
        (from.username ? " · @" + esc(from.username) : "") +
        "\n🆔 <code>" + chatId + "</code>");
    }
  } else {
    if (Number(row.banned) === 1) {
      await tgSend(env, chatId, "⛔️ دسترسی شما به این ربات محدود شده است.");
      return;
    }
    await env.DB.prepare("UPDATE bot_users SET last_seen = ?2, msgs = msgs + 1 WHERE chat_id = ?1")
      .bind(chatId, Date.now()).run();
  }

  if (lc.startsWith("/start")) {
    await setSetting(env, "pend_" + chatId, "");
    await tgSend(env, chatId,
      "سلام " + esc(name) + " 👋\n" +
      "به ربات رسمی <b>Vex Client</b> خوش آمدی!\n\n" +
      "🚀 <b>جدید در نسخه‌ی جدید:</b> حالت «ترکیبی» — اتصال به سرور از داخل تونل سایفون؛ ضد فیلترینگ، سریع‌تر و پایدارتر.\n\n" +
      "از منوی زیر استفاده کن 👇",
      userMenu());
    return;
  }
  if (lc.startsWith("/app") || lc.startsWith("/download") || lc.startsWith("/get")) {
    await sendDownload(chatId, env);
    return;
  }
  if (lc.startsWith("/status")) { await sendUserStatus(chatId, env); return; }
  if (lc.startsWith("/id")) {
    await tgSend(env, chatId, "🆔 شناسه‌ی تلگرام تو: <code>" + chatId + "</code>", userMenu());
    return;
  }
  if (lc.startsWith("/help") || lc.startsWith("/menu")) {
    await sendUserHelp(chatId, env);
    return;
  }
  if (lc.startsWith("/cancel")) {
    await setSetting(env, "pend_" + chatId, "");
    await tgSend(env, chatId, "لغو شد.", userMenu());
    return;
  }

  // هر پیام دیگر = تیکت پشتیبانی → کپی به مدیر + دکمه‌ی پاسخ
  const owner = await getSetting(env, "owner");
  const pend = await getSetting(env, "pend_" + chatId);
  if (pend) await setSetting(env, "pend_" + chatId, "");
  if (!owner) {
    await tgSend(env, chatId, "فعلاً پشتیبانی در دسترس نیست — بعداً دوباره امتحان کن.", userMenu());
    return;
  }
  await tgSend(env, owner,
    "📩 <b>پیام پشتیبانی</b>\n" +
    "👤 " + esc(name) + (from.username ? " · @" + esc(from.username) : "") +
    "\n🆔 <code>" + chatId + "</code>",
    kb([[{ text: "📩 پاسخ", callback_data: "supr_" + chatId }]]));
  await tgCopy(env, chatId, owner, msg.message_id);
  await tgSend(env, chatId, "✅ پیامت رسید و به پشتیبانی ارسال شد — جواب را همین‌جا می‌گیری.", userMenu());
}

async function handleUserCb(a, chatId, env) {
  if (a === "dl") await sendDownload(chatId, env);
  else if (a === "status") await sendUserStatus(chatId, env);
  else if (a === "help") await sendUserHelp(chatId, env);
  else if (a === "menu") await tgSend(env, chatId, "منوی Vex:", userMenu());
  else if (a === "id") await tgSend(env, chatId, "🆔 شناسه‌ی تلگرام تو: <code>" + chatId + "</code>", userMenu());
  else if (a === "support") {
    await setSetting(env, "pend_" + chatId, "support");
    await tgSend(env, chatId,
      "🆘 <b>پشتیبانی Vex</b>\n\n" +
      "مشکل یا سؤالت را بنویس و بفرست — عکس و فایل هم می‌توانی بفرستی.\n" +
      "جواب را همین‌جا در همین چت می‌گیری.\n\nبرای لغو /cancel", userMenu());
  }
}

async function sendDownload(chatId, env) {
  const s = await ghReleaseStats(env);
  const ver = (s && s.ok && s.rows && s.rows.length) ? s.rows[0].tag : "";
  await tgSend(env, chatId,
    "📥 <b>دانلود Vex Client</b>" + (ver ? "\n📦 آخرین نسخه: <b>" + esc(ver) + "</b>" : "") +
    "\n\nلینک مستقیم در ایران باز می‌شود (بدون فیلترشکن):\nhttps://demonvpn.shop/apk\n\n" +
    "💡 بعد از نصب: یک سرور رایگان انتخاب کن و پاور را بزن — اگر سرعت/وصل‌شدن ضعیف بود، حالت «🔗 ترکیبی» را امتحان کن.",
    kb([
      [{ text: "⬇️ دانلود مستقیم APK", url: "https://demonvpn.shop/apk" }],
      [{ text: "📦 صفحه‌ی Releases گیت‌هاب", url: "https://github.com/khastomahdi-tech/Vex_android/releases/latest" }],
      [{ text: "⬅️ منوی اصلی", callback_data: "u_menu" }]
    ]));
}

async function sendUserStatus(chatId, env) {
  const gate = (await getSetting(env, "gate")) || "open";
  const fs = await countFs(env);
  const s = await ghReleaseStats(env);
  const ver = (s && s.ok && s.rows && s.rows.length) ? s.rows[0].tag : "";
  await tgSend(env, chatId,
    "🟢 <b>وضعیت سرویس Vex</b>\n" +
    "برنامه: " + (gate === "closed" ? "🔒 موقتاً بسته (در حال بروزرسانی)" : "✅ فعال و در دسترس") + "\n" +
    "🖥 سرورهای رایگان فعال: <b>" + fs + "</b>\n" +
    (ver ? "📦 آخرین نسخه: <b>" + esc(ver) + "</b>\n" : "") +
    (gate === "closed"
      ? "\n⏳ به‌زودی باز می‌شود — کمی صبر کن و دوباره وارد شو."
      : "\n✅ همه‌چیز آماده است — پاور را بزن و وصل شو!"),
    userMenu());
}

async function sendUserHelp(chatId, env) {
  await tgSend(env, chatId,
    "ℹ️ <b>راهنمای Vex Client</b>\n\n" +
    "سه موتور اتصال داری:\n" +
    "• <b>⚡ Vex</b> — اتصال مستقیم به سرور (پیش‌فرض)\n" +
    "• <b>🔗 ترکیبی</b> — اتصال به سرور از داخل تونل سایفون؛ اگر سرور مستقیم وصل نمی‌شد یا کند بود، این را امتحان کن\n" +
    "• <b>🧅 سایفون</b> — فقط سایفون، بدون نیاز به سرور\n\n" +
    "دستورها:\n" +
    "/app — لینک دانلود\n/status — وضعیت سرویس\n/id — شناسه‌ی تلگرام تو\n\n" +
    "برای هر مشکل یا سؤال، همین‌جا پیام بفرست — مستقیم به پشتیبانی می‌رسد و جواب همین‌جا می‌آید.",
    userMenu());
}

async function sendHelp(chatId, env) {
  await tgSend(env, chatId,
    "🤖 <b>راهنمای ربات Vex</b>\n\n" +
    "همه‌ی کارها از دکمه‌های منوی اصلی انجام می‌شود. دستورها:\n\n" +
    "<b>آمار:</b> /stats · /ghstats · /traffic · /users · /tgu · /ci\n" +
    "<b>برنامه:</b> /close [reason] · /open · /minver n · /blocked\n" +
    "<b>پیام‌رسانی:</b> /broadcast [متن] · /chanpost [متن] · /notify عنوان | متن\n" +
    "<b>سرورها:</b> /fsadd · /sname id پیشوند\n" +
    "<b>کاربران:</b> /reply id متن · /ban id · /unban id\n" +
    "<b>سرویس:</b> /ping · /reload · /panel\n\n" +
    "📩 پیام پشتیبانی کاربران با دکمه‌ی «📩 پاسخ» می‌آید؛ جواب بده، به خودشان می‌رسد.",
    mainMenu());
}

async function sendCi(chatId, env) {
  const repo = env.GH_REPO || (await getSetting(env, "gh_repo")) || DEFAULT_REPO;
  const token = env.GH_TOKEN || (await getSetting(env, "gh_token"));
  const hdr = { "user-agent": "vex-gate", "accept": "application/vnd.github+json" };
  if (token) hdr.authorization = "Bearer " + token;
  let runs = null;
  try {
    const res = await fetch("https://api.github.com/repos/" + repo + "/actions/runs?per_page=5", { headers: hdr });
    if (res.ok) {
      const j = await res.json();
      if (j && Array.isArray(j.workflow_runs)) runs = j.workflow_runs;
    }
  } catch (e) {}
  if (!runs) {
    await tgSend(env, chatId, "⚠️ گرفتن وضعیت بیلد از گیت‌هاب ناموفق بود — چند لحظه بعد دوباره امتحان کن.", mainMenu());
    return;
  }
  let out = "🧪 <b>وضعیت بیلد گیت‌هاب</b>\n\n";
  for (const r of runs.slice(0, 5)) {
    const ic = r.status !== "completed" ? "🔄 در حال اجرا"
      : r.conclusion === "success" ? "✅ موفق"
      : r.conclusion === "failure" ? "❌ ناموفق"
      : "▫️ " + esc(String(r.conclusion || "?"));
    out += "• " + ic + " — " + esc(String(r.display_title || r.name || "?")).slice(0, 44) +
      "\n  <i>" + String(r.created_at || "").slice(0, 16).replace("T", " ") + " UTC</i>\n";
  }
  await tgSend(env, chatId, out,
    kb([[{ text: "🔄 بروزرسانی", callback_data: "ci" }], [{ text: "⬅️ منوی اصلی", callback_data: "menu" }]]));
}

async function sendBotUsers(chatId, env) {
  await ensureBotUsersTable(env);
  const total = await env.DB.prepare("SELECT COUNT(*) AS n FROM bot_users WHERE banned = 0").first();
  const a24 = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM bot_users WHERE banned = 0 AND last_seen >= ?1"
  ).bind(Date.now() - 86400000).first();
  const ban = await env.DB.prepare("SELECT COUNT(*) AS n FROM bot_users WHERE banned = 1").first();
  const rs = await env.DB.prepare(
    "SELECT chat_id, name, username, joined_at, msgs FROM bot_users WHERE banned = 0 ORDER BY last_seen DESC LIMIT 12"
  ).all();
  let out = "🧑‍💬 <b>کاربران ربات</b>\n\n" +
    "👥 کل: <b>" + (total ? total.n : 0) + "</b> · 🟢 فعال ۲۴ ساعت: " + (a24 ? a24.n : 0) +
    " · ⛔️ بن‌شده: " + (ban ? ban.n : 0) + "\n\nآخرین کاربران:";
  for (const r of (rs.results || [])) {
    out += "\n• " + esc(r.name || "?") + (r.username ? " · @" + esc(r.username) : "") +
      " · " + (r.msgs || 0) + " پیام · " + new Date(r.joined_at).toISOString().slice(0, 10);
  }
  if (!(rs.results || []).length) out += "\n(هنوز کسی /start نداده)";
  out += "\n\n📣 پیام همگانی: دکمه‌ی «📣 پیام همگانی» یا /broadcast";
  await tgSend(env, chatId, out, mainMenu());
}

// ---------------- پیام همگانی (پخش دسته‌ای) + پست کانال ----------------

async function startBroadcast(env, ownerChat, fromChat, messageId, origin) {
  await ensureBotUsersTable(env);
  const tr = await env.DB.prepare("SELECT COUNT(*) AS n FROM bot_users WHERE banned = 0").first();
  const total = tr ? tr.n : 0;
  await setSetting(env, "bcast_src", fromChat + ":" + messageId);
  await setSetting(env, "bcast_owner", ownerChat);
  await setSetting(env, "bcast_cur", "0");
  await setSetting(env, "bcast_ok", "0");
  await setSetting(env, "bcast_fail", "0");
  if (!total) {
    await setSetting(env, "bcast_src", "");
    await tgSend(env, ownerChat, "هنوز کاربری در ربات ثبت نشده — چیزی برای پخش نیست.", mainMenu());
    return;
  }
  await tgSend(env, ownerChat, "📣 پخش به <b>" + total + "</b> کاربر ربات شروع شد — گزارش پس از پایان می‌آید.");
  await bcastTick(env, origin || "https://vex-gate.pages.dev");
}

/** یک تکه ۲۰تایی می‌فرستد؛ اگر باقی مانده، خودش تیک بعدی را با فراخوانی /bcast-tick اجرا می‌کند */
async function bcastTick(env, origin) {
  const src = (await getSetting(env, "bcast_src")) || "";
  if (!src) return;
  const ci = src.indexOf(":");
  const fromChat = src.slice(0, ci);
  const mid = parseInt(src.slice(ci + 1), 10);
  if (!mid) { await setSetting(env, "bcast_src", ""); return; }
  const ownerChat = (await getSetting(env, "bcast_owner")) || fromChat;
  const CHUNK = 20;
  let cur = Number(await getSetting(env, "bcast_cur")) || 0;
  let ok = Number(await getSetting(env, "bcast_ok")) || 0;
  let fail = Number(await getSetting(env, "bcast_fail")) || 0;
  const rs = await env.DB.prepare(
    "SELECT chat_id FROM bot_users WHERE banned = 0 ORDER BY joined_at LIMIT ?1 OFFSET ?2"
  ).bind(CHUNK, cur).all();
  for (const row of (rs.results || [])) {
    if (await tgCopy(env, fromChat, row.chat_id, mid)) ok++; else fail++;
  }
  cur += (rs.results || []).length;
  await setSetting(env, "bcast_cur", String(cur));
  await setSetting(env, "bcast_ok", String(ok));
  await setSetting(env, "bcast_fail", String(fail));
  const tr = await env.DB.prepare("SELECT COUNT(*) AS n FROM bot_users WHERE banned = 0").first();
  const total = tr ? tr.n : 0;
  if (cur < total && (rs.results || []).length > 0) {
    const key = env.APP_KEY || (await getSetting(env, "admin_key")) || "";
    try { await fetch(origin + "/bcast-tick?k=" + encodeURIComponent(key), { method: "POST" }); } catch (e) {}
  } else {
    await setSetting(env, "bcast_src", "");
    await tgSend(env, ownerChat,
      "📣 <b>پخش همگانی تمام شد</b>\n" +
      "✅ موفق: " + ok + "\n❌ ناموفق: " + fail + "\n👥 کل: " + total +
      "\n\n<i>ناموفق‌ها معمولاً کاربرانی هستند که ربات را بلاک کرده‌اند.</i>", mainMenu());
  }
}

async function doChannelPost(text, chatId, env) {
  const ch = (await getSetting(env, "channel")) || "@vex_vpnfree";
  const mark = kb([[
    { text: "📥 دانلود Vex", url: "https://demonvpn.shop/apk" },
    { text: "🤖 ربات Vex", url: "https://t.me/Appmasegbot" }
  ]]);
  let r = await tgSendRet(env, ch, text, mark);
  if (!r || !r.ok) r = await tgSendPlain(env, ch, text, mark); // HTML نامعتبر؟ بدون فرمت امتحان کن
  if (r && r.ok) {
    await tgSend(env, chatId, "📢 پست در کانال منتشر شد ✅\n\n" + esc(text).slice(0, 220), mainMenu());
  } else {
    await tgSend(env, chatId,
      "⚠️ انتشار در کانال ناموفق بود — مطمئن شو ربات @" + String(ch).replace("@", "") + " ادمین کانال است.\n" +
      (r && r.description ? "خطا: " + esc(r.description) : ""), mainMenu());
  }
}

// ---------------- گزارش خطای ربات به مدیر ----------------

async function botError(env, e) {
  const owner = await getSetting(env, "owner");
  if (!owner) return;
  const last = Number(await getSetting(env, "err_ts")) || 0;
  if (Date.now() - last < 60000) return;
  await setSetting(env, "err_ts", String(Date.now()));
  await tgSend(env, owner,
    "⚠️ <b>خطای ربات</b>\n<code>" +
    esc(String((e && (e.stack || e.message)) || e)).slice(0, 500) + "</code>");
}

// ---------------- free servers (bot-managed) ----------------

async function countFs(env) {
  const r = await env.DB.prepare("SELECT COUNT(*) AS n FROM free_servers WHERE enabled = 1").first();
  return r ? r.n : 0;
}

async function askAddFreeServer(chatId, env) {
  await setSetting(env, "pend_" + chatId, "add_fs");
  await tgSend(env, chatId,
    "➕ <b>افزودن سرور رایگان</b>\n\n" +
    "هر خط یک کانفیگ:\n" +
    "<code>vless:// · vmess:// · trojan:// · ss:// · wg://</code>\n\n" +
    "🏷 <b>پیشوند سرور (نشان کنار نام):</b> اگر خط را با پیشوند بفرستی، همان پیشوند در برنامه کنار نام سرور نشان داده می‌شود:\n" +
    "<code>🇩🇪 ویژه | vless://…</code>\n\n" +
    "📄 فایل کامل وایرگارد (<code>[Interface]…[Peer]</code>) را هم می‌توانی همان‌طور پیست کنی.\n\n" +
    "بعد از افزودن، سرورها در بخش «سرورهای رایگان» برنامه‌ی همه‌ی کاربران تا حداکثر یک دقیقه دیگر ظاهر می‌شوند (بدون امکان ویرایش یا حذف از سمت آن‌ها).\n\nبرای لغو /cancel");
}

function extractRemark(raw, proto) {
  try {
    if (proto === "vmess") {
      const b64 = raw.slice(8).split("#")[0];
      const j = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(b64.replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0))));
      return String(j.ps || "").trim();
    }
    const h = raw.split("#");
    if (h.length > 1 && h[1].trim()) return decodeURIComponent(h[1].trim()).slice(0, 60);
  } catch (e) {}
  return "";
}

/** تبدیل فایل conf وایرگارد به قالب دستی برنامه (single-line JSON) — مثل wgConfToManual سمت اندروید */
function wgConfToManual(text) {
  try {
    let section = "", priv = "", pub = "", psk = "", endpoint = "", dnsLine = "", mtu = "", reserved = "", name = "";
    const addresses = [];
    for (let rawLine of String(text).split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) continue;
      if (line.startsWith("#")) {
        const d = line.replace(/^#+\s*/, "").trim();
        if (!name && d.length > 2) name = d;
        continue;
      }
      if (line.startsWith("[")) {
        const end = line.indexOf("]");
        section = (end > 0 ? line.slice(1, end) : line.slice(1)).trim().toLowerCase();
        continue;
      }
      const idx = line.indexOf("=");
      if (idx <= 0) continue;
      const key = line.slice(0, idx).trim().toLowerCase();
      const value = line.slice(idx + 1).trim();
      if (section === "interface") {
        if (key === "privatekey") priv = value;
        else if (key === "address") value.split(",").forEach(a => { if (a.trim()) addresses.push(a.trim()); });
        else if (key === "mtu") mtu = value;
        else if (key === "dns") dnsLine = value;
      } else if (section === "peer") {
        if (key === "publickey") pub = value;
        else if (key === "presharedkey") psk = value;
        else if (key === "endpoint") endpoint = value;
        else if (key === "reserved") reserved = value;
      }
    }
    if (!priv || !pub || !endpoint || endpoint.indexOf(":") < 0) return null;
    const host = endpoint.slice(0, endpoint.lastIndexOf(":")).replace(/^\[/, "").replace(/\]$/, "");
    const port = parseInt(endpoint.slice(endpoint.lastIndexOf(":") + 1), 10) || 51820;
    const o = {
      protocol: "wireguard", wgName: (name || "WireGuard").slice(0, 60),
      privateKey: priv, publicKey: pub, server: host, port: port,
      addresses_csv: addresses.join(",")
    };
    if (psk) o.presharedKey = psk;
    if (mtu) o.mtu = parseInt(mtu, 10) || 1408;
    if (reserved) o.reserved = reserved;
    if (dnsLine) o.dns = dnsLine.split(",")[0].trim();
    return o;
  } catch (e) {
    return null;
  }
}

async function insertFs(env, raw, proto, name, label) {
  const ex = await env.DB.prepare("SELECT 1 AS x FROM free_servers WHERE raw = ?1").bind(raw).first();
  if (ex) return null;
  if (!name) {
    const c = await env.DB.prepare("SELECT COUNT(*) AS n FROM free_servers").first();
    name = "سرور رایگان " + ((c ? c.n : 0) + 1);
  }
  await env.DB.prepare(
    "INSERT INTO free_servers (name, label, raw, proto, added_at, enabled) VALUES (?1,?2,?3,?4,?5,1)"
  ).bind(String(name).slice(0, 80), String(label || "").slice(0, 40), raw, proto, Date.now()).run();
  return name;
}

/**
 * هسته‌ی افزودن سرورهای رایگان — هم ربات هم پنل ادمین استفاده می‌کنند.
 * خروجی: { added, dup, bad, names[] } — ارسال پیام/همگام‌سازی با فراخوان است.
 */
async function addServersCore(text, env) {
  let added = 0, dup = 0, bad = 0;
  const names = [];

  // ۱) فایل کامل وایرگارد ([Interface] … [Peer]) — کل پیام یک کانفیگ است
  if (/\[interface\]/i.test(text) && /\[peer\]/i.test(text)) {
    const man = wgConfToManual(text);
    if (man) {
      const n = await insertFs(env, JSON.stringify(man), "wireguard", man.wgName, "");
      if (n === null) dup++; else { added++; names.push(String(man.wgName || "WireGuard")); }
    } else bad++;
  } else {
    // ۲) خط‌به‌خط: «پیشوند | کانفیگ» یا فقط کانفیگ (چند کانفیگ در یک خط هم قبول است)
    for (let line of String(text).split(/\r?\n/)) {
      line = line.trim();
      if (!line) continue;
      let label = "", body = line;
      const bar = line.indexOf("|");
      if (bar > 0) {
        const head = line.slice(0, bar).trim();
        const tail = line.slice(bar + 1).trim();
        if (head && tail && head.length <= 40 && !head.toLowerCase().includes("://") && tail.toLowerCase().includes("://")) {
          label = head;
          body = tail;
        }
      }
      for (const t of body.split(/\s+/).filter(Boolean)) {
        const p = t.split("://")[0].toLowerCase();
        if (!PROTOCOLS.includes(p)) { bad++; continue; }
        const raw = t.slice(0, 1200);
        const name = extractRemark(raw, p);
        const n = await insertFs(env, raw, p, name, label);
        if (n === null) dup++; else { added++; names.push((label ? label + " | " : "") + n); }
      }
    }
  }
  return { added, dup, bad, names };
}

async function addFreeServers(text, chatId, env) {
  const res = await addServersCore(text, env);
  if (res.added) await ghSync(env, "freeservers.json", await fsSnapshot(env));
  const total = await countFs(env);
  let names = "";
  for (const n of res.names) { if (names.length < 400) names += "\n• " + n; }
  await tgSend(env, chatId,
    "✅ " + res.added + " سرور اضافه شد" +
    (res.dup ? "\n♻️ " + res.dup + " تکرار رد شد" : "") +
    (res.bad ? "\n❌ " + res.bad + " نامعتبر بود" : "") +
    (names ? "\n" + names : "") +
    "\n\n🖥 مجموع سرورهای رایگان فعال: <b>" + total + "</b>\nلیست داخل برنامه‌ی همه‌ی کاربران هر ۱ دقیقه خودکار به‌روزرسانی می‌شود — بدون نیاز به دکمه و بدون امکان ویرایش/حذف از سمت آن‌ها.", fsMenuKb());
}

/** /sname <id> <پیشوند> — گذاشتن/پاک‌کردن پیشوند یک سرور */
async function snameCmd(rest, chatId, env) {
  const m = rest.match(/^(\d+)\s*(.*)$/);
  if (!m) {
    await tgSend(env, chatId,
      "شکل درست: <code>/sname شناسه پیشوند</code>\n" +
      "مثال: <code>/sname 3 🇩🇪 ویژه</code>\n" +
      "پاک‌کردن پیشوند: <code>/sname 3 -</code>\n\n" +
      "شناسه‌ی سرورها را از «🖥 سرورهای رایگان ← 📃 لیست سرورها» ببین (دکمه‌ی 🏷 پیشوند همان‌جا هم هست).", fsMenuKb());
    return;
  }
  const id = parseInt(m[1], 10);
  const label = m[2].trim().slice(0, 40);
  const v = (!label || label === "-" || label === "clear") ? "" : label;
  await env.DB.prepare("UPDATE free_servers SET label = ?2 WHERE id = ?1").bind(id, v).run();
  await ghSync(env, "freeservers.json", await fsSnapshot(env));
  await tgSend(env, chatId,
    v ? ("🏷 پیشوند سرور #" + id + " → «" + esc(v) + "» ثبت شد — کنار نامش در برنامه نشان داده می‌شود.")
      : ("🏷 پیشوند سرور #" + id + " پاک شد."), fsMenuKb());
}

/** اعلان همگانی: ذخیره در D1 → اپ کاربران تا ۶۰ ثانیه بعد به‌صورت نوتیفیکیشن سیستم نشان می‌دهد */
async function askNotify(chatId, env) {
  await tgSend(env, chatId,
    "📢 <b>ارسال اعلان به کاربران</b>\n\n" +
    "عنوان و متن اعلان را در یک پیام بفرست، با این شکل:\n" +
    "<code>عنوان اعلان | متن توضیحات اعلان</code>\n\n" +
    "اعلان برای همه‌ی کاربرانی که دسترسی اعلان داده‌اند، به‌صورت نوتیفیکیشن سیستم بالای صفحه می‌آید — حتی اگر برنامه‌شان بسته باشد.\n\nبرای لغو /cancel");
}

async function doNotify(text, chatId, env) {
  const parts = String(text).split("|");
  const title = (parts[0] || "").trim().slice(0, 80);
  const body = parts.slice(1).join("|").trim().slice(0, 400);
  if (!title) {
    await tgSend(env, chatId, "عنوان اعلان خالی بود — دوباره با شکل «عنوان | متن» بفرست.\n\nبرای لغو /cancel");
    return;
  }
  const r = await env.DB.prepare(
    "INSERT INTO notifications (title, body, created_at) VALUES (?1,?2,?3)"
  ).bind(title, body, Date.now()).run();
  const id = (r && r.meta && r.meta.last_row_id) ? r.meta.last_row_id : "?";
  await ghSync(env, "notifications.json", await notifSnapshot(env));
  await tgSend(env, chatId,
    "📢 اعلان ثبت شد (شماره‌ی " + id + ")\n\n" +
    "🏷 عنوان: <b>" + esc(title) + "</b>\n" +
    (body ? "📝 متن: " + esc(body) + "\n" : "") +
    "\n⏱ کاربران آنلاین تا ۶۰ ثانیه دیگر، حتی اگر برنامه‌شان کاملاً بسته باشد حداکثر ۱۵ دقیقه دیگر این اعلان را بالای صفحه می‌بینند.", mainMenu());
}

/** 📜 اعلان‌های اخیر — برای مرور و یادآوری */
async function sendNotifs(chatId, env) {
  const rs = await env.DB.prepare(
    "SELECT id, title, body, created_at FROM notifications ORDER BY id DESC LIMIT 6"
  ).all();
  const list = rs.results || [];
  if (!list.length) {
    await tgSend(env, chatId, "📜 هنوز اعلانی ثبت نشده — با 📢 ارسال اعلان اولین را بفرست.", mainMenu());
    return;
  }
  let out = "📜 <b>اعلان‌های اخیر</b>";
  for (const r of list) {
    out += "\n\n#" + r.id + " · <b>" + esc(r.title) + "</b>" +
      (r.body ? "\n" + esc(r.body) : "") +
      "\n<i>" + new Date(r.created_at).toISOString().slice(0, 16).replace("T", " ") + " UTC</i>";
  }
  await tgSend(env, chatId, out, mainMenu());
}

/** 🔄 همگام‌سازی اسنپ‌شات‌های پشتیبان گیت‌هاب */
async function syncSnaps(chatId, env) {
  const st = await stateSnapshot(env);
  const fs = await fsSnapshot(env);
  const nt = await notifSnapshot(env);
  await ghSync(env, "state.json", st);
  await ghSync(env, "freeservers.json", fs);
  await ghSync(env, "notifications.json", nt);
  await tgSend(env, chatId,
    "🔄 اسنپ‌شات‌های پشتیبان همگام شد:\n" +
    "• state.json (دروازه: " + (st.gate === "closed" ? "بسته" : "باز") + ")\n" +
    "• freeservers.json (" + (fs.servers || []).length + " سرور)\n" +
    "• notifications.json (" + (nt.list || []).length + " اعلان)", mainMenu());
}

/** 🔄 حداقل نسخه‌ی مجاز — کاربران نسخه‌های قدیمی‌تر بنر «نسخه‌ی جدید» می‌بینند */
async function minverCmd(rest, chatId, env) {
  const cur = Number(await getSetting(env, "min_ver")) || 0;
  if (!rest) {
    await tgSend(env, chatId,
      "🔄 <b>حداقل نسخه‌ی مجاز</b>\n\n" +
      "الان: <b>" + (cur ? cur : "خاموش") + "</b>\n\n" +
      "اگر این عدد از versionCode کاربر بالاتر باشد، موقع ورود بنر «🔔 نسخه‌ی جدید منتشر شده» با دکمه‌ی دانلود می‌بیند.\n\n" +
      "تنظیم: <code>/minver 31</code>\nخاموش‌کردن: <code>/minver 0</code>\n\n" +
      "💡 کد نسخه‌ی فعلی برنامه <b>32</b> است (نام نمایشی: 1). برای بستن یک نسخه‌ی مشخص، «🚫 نسخه‌های بسته» را ببین.", mainMenu());
    return;
  }
  const n = Math.round(Number(rest));
  if (isNaN(n) || n < 0) {
    await tgSend(env, chatId, "عدد معتبر بده. مثال: <code>/minver 30</code> یا <code>/minver 0</code> برای خاموش‌کردن.", mainMenu());
    return;
  }
  await setSetting(env, "min_ver", String(n));
  await ghSync(env, "state.json", await stateSnapshot(env));
  await tgSend(env, chatId,
    n === 0
      ? "🔄 حداقل نسخه خاموش شد — همه با هر نسخه‌ای می‌توانند استفاده کنند."
      : "🔄 حداقل نسخه روی <b>" + n + "</b> تنظیم شد — کاربران نسخه‌های قدیمی‌تر موقع ورود بنر به‌روزرسانی می‌بینند.", mainMenu());
}

async function sendFsMenu(chatId, env) {
  const n = await countFs(env);
  await tgSend(env, chatId,
    "🖥 <b>مدیریت سرورهای رایگان</b>\n\n" +
    "تعداد فعال: <b>" + n + "</b>\n\n" +
    "این سرورها فقط از همین ربات مدیریت می‌شوند و در تب «سرورهای رایگان» برنامه‌ی همه‌ی کاربران نمایش داده می‌شوند — کاربر نمی‌تواند ویرایش یا حذفشان کند، فقط اتصال و پینگ.",
    fsMenuKb());
}

async function sendFsList(chatId, env) {
  const rs = await env.DB.prepare(
    "SELECT id, name, label, proto, added_at, enabled FROM free_servers ORDER BY id DESC LIMIT 50"
  ).all();
  const list = rs.results || [];
  if (!list.length) {
    await tgSend(env, chatId, "📃 لیست سرورهای رایگان خالی است.\nبا ➕ افزودن سرور اولین کانفیگ را بفرست.", fsMenuKb());
    return;
  }
  let out = "📃 <b>سرورهای رایگان (" + list.length + ")</b>\n" +
    "برای هر سرور: 🏷 پیشوند · ⚫/🟢 فعال/غیرفعال · 🗑 حذف:\n";
  const rows = [];
  for (const r of list) {
    const on = Number(r.enabled) === 1;
    out += "\n" + (on ? "🟢" : "⚫") + " " + (r.proto || "?").toUpperCase() + " · " +
      (r.label ? "🏷 " + esc(r.label) + " · " : "") + esc(r.name || "?") +
      " · #" + r.id + " · " + new Date(r.added_at).toISOString().slice(0, 10);
    rows.push([
      { text: "🏷 پیشوند", callback_data: "fs_name_" + r.id },
      { text: on ? "⚫ خاموش" : "🟢 روشن", callback_data: "fs_tgl_" + r.id },
      { text: "🗑 حذف: " + (r.name || r.id).slice(0, 14), callback_data: "fs_del_" + r.id }
    ]);
  }
  rows.push([{ text: "⬅️ بازگشت", callback_data: "fs" }]);
  await tgSend(env, chatId, out, kb(rows));
}

/**
 * 🚫 نسخه‌های بسته — بستن/باز کردن نسخه‌های مشخص برنامه.
 * نسخه‌ی بسته‌شده موقع ورود کاربر پیام «This version has been disabled» می‌بیند.
 */
async function sendVersions(chatId, env) {
  const blocked = await getBlocked(env);
  let rows = [];
  let out = "🚫 <b>نسخه‌های بسته</b>\n\n";
  if (blocked.length) {
    out += "الان بسته‌اند:";
    for (const v of blocked) {
      out += "\n• <b>" + esc(v) + "</b>";
      rows.push([{ text: "✅ باز کردن: " + v, callback_data: "vunblk_" + v }]);
    }
  } else {
    out += "هیچ نسخه‌ای بسته نیست — همه می‌توانند وارد شوند.";
  }
  out += "\n\n👥 <b>نسخه‌های دیده‌شده روی دستگاه کاربران</b> (برای بستن، دکمه‌اش را بزن):";
  const rs = await env.DB.prepare(
    "SELECT ver, COUNT(*) AS n, MAX(last_seen) AS last_seen FROM users GROUP BY ver ORDER BY last_seen DESC LIMIT 10"
  ).all();
  const vers = rs.results || [];
  if (!vers.length) {
    out += "\nهنوز ورودی ثبت نشده.";
  } else {
    for (const r of vers) {
      const v = normalizeVersion(r.ver) || "?";
      const isBlocked = blocked.includes(v);
      out += "\n" + (isBlocked ? "🚫" : "•") + " v" + esc(v) + " — " + r.n + " دستگاه" +
        " · آخرین ورود " + new Date(r.last_seen).toISOString().slice(5, 16).replace("T", " ");
      rows.push([{ text: (isBlocked ? "✅ باز کردن v" : "🚫 بستن v") + v, callback_data: (isBlocked ? "vunblk_" : "vblk_") + v }]);
    }
  }
  rows.push([{ text: "✍️ بستن نسخه‌ی جدید", callback_data: "ver_add" }]);
  if (blocked.length) rows.push([{ text: "♻️ آزاد کردن همه", callback_data: "ver_clear" }]);
  rows.push([{ text: "⬅️ منوی اصلی", callback_data: "menu" }]);
  await tgSend(env, chatId, out, kb(rows));
}

// ============================================================ GitHub fallback snapshots

/** اسنپ‌شات وضعیت دروازه — همان شکل پاسخ /v1/state به‌علاوه‌ی ts */
async function stateSnapshot(env) {
  const gate = (await getSetting(env, "gate")) || "open";
  const reason = gate === "closed" ? ((await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON) : null;
  const minVer = Number(await getSetting(env, "min_ver")) || 0;
  const blocked = await getBlocked(env);
  return { ok: true, gate, reason, min_ver: minVer, blocked, ts: Date.now() };
}

/** اسنپ‌شات سرورهای رایگان — همان شکل پاسخ /v1/freeservers */
async function fsSnapshot(env) {
  const rs = await env.DB.prepare(
    "SELECT id, name, label, proto, raw FROM free_servers WHERE enabled = 1 ORDER BY id"
  ).all();
  return { ok: true, servers: rs.results || [], ts: Date.now() };
}

/** اسنپ‌شات اعلان‌ها — ۵۰ مورد آخر صعودی؛ برنامه خودش since را محلی فیلتر می‌کند */
async function notifSnapshot(env) {
  const rs = await env.DB.prepare(
    "SELECT id, title, body FROM notifications ORDER BY id DESC LIMIT 50"
  ).all();
  return { ok: true, list: (rs.results || []).reverse(), ts: Date.now() };
}

/**
 * نوشتن یک اسنپ‌شات در ریپوی گیت‌هاب (gate/<file>) تا برنامه‌ی کاربرانی که به
 * pages.dev دسترسی ندارند (ایران)، از raw.githubusercontent.com بخوانند.
 * توکن/ریپو از D1 settings (gh_token / gh_repo) — شکستش هرگز ربات را نمی‌شکند.
 */
async function ghSync(env, file, obj) {
  try {
    const repo = env.GH_REPO || (await getSetting(env, "gh_repo"));
    const token = env.GH_TOKEN || (await getSetting(env, "gh_token"));
    if (!repo || !token) return;
    const body = JSON.stringify(obj);
    const api = "https://api.github.com/repos/" + repo + "/contents/gate/" + file;
    const hdr = {
      "authorization": "Bearer " + token,
      "user-agent": "vex-gate",
      "accept": "application/vnd.github+json",
      "content-type": "application/json"
    };
    let sha = null;
    try {
      const g = await fetch(api + "?ref=main", { headers: hdr });
      if (g.ok) { const j = await g.json(); sha = j.sha; }
    } catch (e) {}
    const payload = { message: "vex-gate snapshot: " + file + " [skip ci]", content: b64utf8(body), branch: "main" };
    if (sha) payload.sha = sha;
    await fetch(api, { method: "PUT", headers: hdr, body: JSON.stringify(payload) });
  } catch (e) { /* best effort */ }
}

function b64utf8(s) {
  const bytes = new TextEncoder().encode(s);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(bin);
}

// ============================================================ helpers

async function tgSend(env, chat, text, replyMarkup) {
  if (!env.BOT_TOKEN || !chat) return;
  const body = { chat_id: chat, text, parse_mode: "HTML", disable_web_page_preview: true };
  if (replyMarkup) body.reply_markup = replyMarkup;
  try {
    await fetch("https://api.telegram.org/bot" + env.BOT_TOKEN + "/sendMessage", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    });
  } catch (e) { /* best effort */ }
}

/** مثل tgSend ولی نتیجه‌ی JSON تلگرام را برمی‌گرداند (برای گرفتن message_id / ok) */
async function tgSendRet(env, chat, text, replyMarkup) {
  if (!env.BOT_TOKEN || !chat) return null;
  const body = { chat_id: chat, text, parse_mode: "HTML", disable_web_page_preview: true };
  if (replyMarkup) body.reply_markup = replyMarkup;
  try {
    const r = await fetch("https://api.telegram.org/bot" + env.BOT_TOKEN + "/sendMessage", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    });
    return await r.json();
  } catch (e) { return null; }
}

/** ارسال بدون parse_mode — وقتی HTML نامعتبر است */
async function tgSendPlain(env, chat, text, replyMarkup) {
  if (!env.BOT_TOKEN || !chat) return null;
  const body = { chat_id: chat, text, disable_web_page_preview: true };
  if (replyMarkup) body.reply_markup = replyMarkup;
  try {
    const r = await fetch("https://api.telegram.org/bot" + env.BOT_TOKEN + "/sendMessage", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    });
    return await r.json();
  } catch (e) { return null; }
}

/** کپی یک پیام (متن/عکس/فایل) از یک چت به چت دیگر — برای پخش همگانی و پشتیبانی؛ ok بودن را برمی‌گرداند */
async function tgCopy(env, fromChat, toChat, messageId) {
  if (!env.BOT_TOKEN || !toChat || !messageId) return false;
  try {
    const r = await fetch("https://api.telegram.org/bot" + env.BOT_TOKEN + "/copyMessage", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ chat_id: toChat, from_chat_id: fromChat, message_id: messageId })
    });
    const j = await r.json();
    return !!(j && j.ok);
  } catch (e) { return false; }
}

async function answerCb(env, id, text) {
  if (!env.BOT_TOKEN || !id) return;
  const body = { callback_query_id: id };
  if (text) body.text = text;
  try {
    await fetch("https://api.telegram.org/bot" + env.BOT_TOKEN + "/answerCallbackQuery", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    });
  } catch (e) { /* best effort */ }
}

async function getSetting(env, k) {
  const r = await env.DB.prepare("SELECT v FROM settings WHERE k = ?1").bind(k).first();
  return r ? r.v : null;
}

// ---------------- نسخه‌های بسته (blocked_versions در D1 settings) ----------------

/** فهرست نسخه‌های بسته — همیشه آرایه‌ی تمیز از رشته‌های کوتاه */
async function getBlocked(env) {
  const raw = (await getSetting(env, "blocked_versions")) || "[]";
  let arr = [];
  try { arr = JSON.parse(raw); } catch (e) {}
  if (!Array.isArray(arr)) arr = [];
  const out = [];
  for (const v of arr) {
    const n = normalizeVersion(v);
    if (n && !out.includes(n)) out.push(n);
  }
  return out;
}

async function setBlocked(env, list) {
  const clean = [];
  for (const v of (Array.isArray(list) ? list : [])) {
    const n = normalizeVersion(v);
    if (n && !clean.includes(n)) clean.push(n);
  }
  await setSetting(env, "blocked_versions", JSON.stringify(clean));
  return clean;
}

/** نرمال‌سازی رشته‌ی نسخه: حذف فاصله، حذف v ابتدایی، حداکثر ۲۴ کاراکتر */
function normalizeVersion(v) {
  let s = String(v == null ? "" : v).trim().replace(/^v/i, "").slice(0, 24);
  return s;
}

async function setSetting(env, k, v) {
  await env.DB.prepare(
    "INSERT INTO settings (k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v = ?2"
  ).bind(k, v).run();
}

function flagEmoji(cc) {
  if (!cc || cc.length !== 2) return "";
  return String.fromCodePoint(...[...cc.toUpperCase()].map(c => 0x1F1E6 + c.charCodeAt(0) - 65));
}

function fmtB(n) {
  n = Number(n) || 0;
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (i ? v.toFixed(2) : String(v)) + " " + u[i];
}

function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" }
  });
}
