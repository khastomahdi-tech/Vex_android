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
 * /v1/* endpoints require header  X-Vex-Key: <APP_KEY>
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
 *   /notify Title | Body  → push notification to every user's phone (app polls every 60s,
 *                           asks POST_NOTIFICATIONS on first launch).
 *   /sname <id> <prefix>  → set/remove the server prefix (emoji/label) shown in the app.
 *   Free-server add supports «پیشوند | config» lines, wg:// links and full WG .conf text.
 */

const SERVICE_V = 7;

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
      try { await handleUpdate(up, env); } catch (e) { /* never fail the webhook */ }
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
        return json({ ok: true, gate, reason });
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
        const type = b.type === "free" ? "free" : "private";
        const up = Math.max(0, Math.round(Number(b.up) || 0));
        const dn = Math.max(0, Math.round(Number(b.down) || 0));
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

    return json({ ok: false, error: "not found" }, 404);
  }
};

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
    await tgSend(env, owner, text);
  }

  return json({ ok: true, gate, reason: gate === "closed" ? ((await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON) : null, users: total ? total.n : 0 });
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
    await tgSend(env, owner, text);
  }
  return json({ ok: true });
}

// ============================================================ telegram

async function handleUpdate(up, env) {
  // ---------- callback_query (inline buttons) ----------
  const cb = up && up.callback_query;
  if (cb && cb.data) {
    const chatId = String((cb.message && cb.message.chat && cb.message.chat.id) || cb.from.id);
    const owner = await getSetting(env, "owner");
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

  if (chatId !== owner) return; // only the owner can use the bot

  // pending flows (add free server / close with reason)
  const pend = await getSetting(env, "pend_" + chatId);
  if (!lc.startsWith("/") && pend) {
    await setSetting(env, "pend_" + chatId, "");
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
  } else if (lc.startsWith("/menu") || lc.startsWith("/help")) {
    await tgSend(env, chatId, "منوی مدیریت Vex:", mainMenu());
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
  }
}

// ---------------- bot builders ----------------

function mainMenu() {
  return kb([
    [{ text: "📊 آمار", callback_data: "stats" }, { text: "👥 کاربران", callback_data: "users" }],
    [{ text: "📈 مصرف ترافیک", callback_data: "traffic" }, { text: "📥 نصب از گیت‌هاب", callback_data: "ghstats" }],
    [{ text: "🖥 سرورهای رایگان", callback_data: "fs" }],
    [{ text: "📢 ارسال اعلان به کاربران", callback_data: "notify" }],
    [{ text: "🟢 وضعیت برنامه", callback_data: "state" }],
    [{ text: "🔒 بستن برنامه", callback_data: "close" }, { text: "🔓 باز کردن", callback_data: "open" }]
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
  const op = await env.DB.prepare("SELECT COALESCE(SUM(opens),0) AS n FROM users").first();
  const fs = await countFs(env);
  const gate = (await getSetting(env, "gate")) || "open";
  const tt = await trafficTotals(env);
  await tgSend(env, chatId,
    "📊 <b>آمار Vex Client</b>\n" +
    "👥 کل کاربران (نصب‌ها): <b>" + (t ? t.n : 0) + "</b>\n" +
    "🆕 نصب ۲۴ ساعت گذشته: " + (today ? today.n : 0) + "\n" +
    "🟢 کاربران فعال ۲۴ ساعت گذشته: " + (active ? active.n : 0) + "\n" +
    "🔁 مجموع ورودها: " + (op ? op.n : 0) + "\n" +
    "🖥 سرورهای رایگان فعال: " + fs + "\n" +
    "🆓 مصرف سرورهای رایگان: " + fmtB(tt.freeB) + "\n" +
    "🔑 مصرف سرورهای شخصی: " + fmtB(tt.privB) + "\n" +
    "🧮 مصرف کل: <b>" + fmtB(tt.freeB + tt.privB) + "</b>\n" +
    "وضعیت برنامه: " + (gate === "closed" ? "🔒 بسته" : "🔓 باز"), mainMenu());
}

/**
 * آمار نصب از گیت‌هاب — تعداد دانلودهای APK از Releases گیت‌هاب:
 * کل نصب‌ها + نصب هر نسخه (asset نسخه‌دار هر Release) + دانلودهای لینک Latest.
 * توکن/ریپو از D1 settings (gh_token / gh_repo) — بدون توکن هم کار می‌کند.
 */
async function sendGhStats(chatId, env) {
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
  if (!rels) {
    await tgSend(env, chatId, "⚠️ گرفتن آمار از گیت‌هاب ممکن نشد — چند لحظه بعد دوباره امتحان کن.", mainMenu());
    return;
  }
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

async function addFreeServers(text, chatId, env) {
  let added = 0, dup = 0, bad = 0, names = "";
  const show = (n, lb) => { if (names.length < 400) names += "\n• " + (lb ? lb + " | " : "") + n; };

  // ۱) فایل کامل وایرگارد ([Interface] … [Peer]) — کل پیام یک کانفیگ است
  if (/\[interface\]/i.test(text) && /\[peer\]/i.test(text)) {
    const man = wgConfToManual(text);
    if (man) {
      const n = await insertFs(env, JSON.stringify(man), "wireguard", man.wgName, "");
      if (n === null) dup++; else { added++; show(n, ""); }
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
        if (n === null) dup++; else { added++; show(n, label); }
      }
    }
  }
  const total = await countFs(env);
  if (added) await ghSync(env, "freeservers.json", await fsSnapshot(env));
  await tgSend(env, chatId,
    "✅ " + added + " سرور اضافه شد" +
    (dup ? "\n♻️ " + dup + " تکرار رد شد" : "") +
    (bad ? "\n❌ " + bad + " نامعتبر بود" : "") +
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
    "اعلان برای همه‌ی کاربرانی که دسترسی اعلان داده‌اند، به‌صورت نوتیفیکیشن سیستم بالای صفحه می‌آید.\n\nبرای لغو /cancel");
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
    "\n⏱ تا حداکثر ۶۰ ثانیه‌ی دیگر روی گوشی کاربران آنلاین بالای صفحه ظاهر می‌شود.", mainMenu());
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
    "SELECT id, name, label, proto, added_at FROM free_servers ORDER BY id DESC LIMIT 50"
  ).all();
  const list = rs.results || [];
  if (!list.length) {
    await tgSend(env, chatId, "📃 لیست سرورهای رایگان خالی است.\nبا ➕ افزودن سرور اولین کانفیگ را بفرست.", fsMenuKb());
    return;
  }
  let out = "📃 <b>سرورهای رایگان (" + list.length + ")</b>\nبرای هر سرور: 🏷 پیشوند (نشان کنار نام در برنامه) یا 🗑 حذف:\n";
  const rows = [];
  for (const r of list) {
    out += "\n" + (r.proto || "?").toUpperCase() + " · " + (r.label ? "🏷 " + esc(r.label) + " · " : "") + esc(r.name || "?") +
      " · #" + r.id + " · " + new Date(r.added_at).toISOString().slice(0, 10);
    rows.push([
      { text: "🏷 پیشوند", callback_data: "fs_name_" + r.id },
      { text: "🗑 حذف: " + (r.name || r.id).slice(0, 18), callback_data: "fs_del_" + r.id }
    ]);
  }
  rows.push([{ text: "⬅️ بازگشت", callback_data: "fs" }]);
  await tgSend(env, chatId, out, kb(rows));
}

// ============================================================ GitHub fallback snapshots

/** اسنپ‌شات وضعیت دروازه — همان شکل پاسخ /v1/state به‌علاوه‌ی ts */
async function stateSnapshot(env) {
  const gate = (await getSetting(env, "gate")) || "open";
  const reason = gate === "closed" ? ((await getSetting(env, "close_reason")) || DEFAULT_CLOSE_REASON) : null;
  return { ok: true, gate, reason, ts: Date.now() };
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
