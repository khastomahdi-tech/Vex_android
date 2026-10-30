/**
 * Vex Gate — Telegram bot backend for Vex Client
 * Cloudflare Workers + D1 (vex-gate-db)
 *
 * Endpoints:
 *   POST /tg                 → Telegram webhook (verified by X-Telegram-Bot-Api-Secret-Token)
 *   GET  /v1/state?did=...   → app polls gate state → {"ok":true,"gate":"open"|"closed"}
 *   POST /v1/report          → app reports install/open  (D1 + Telegram message to owner)
 *   POST /v1/ack             → device acknowledges lock/unlock → owner notified
 *   GET  /                   → health
 *
 * /v1/* endpoints require header  X-Vex-Key: <APP_KEY>
 *
 * Bot commands (owner = first chat that sends /start):
 *   /start   → register owner + help
 *   /stats   → user count + last users + gate state
 *   /close   → lock the app for everyone
 *   /open    → unlock the app
 *   /users   → list last 20 installs
 *   /help    → help
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && (path === "/" || path === "")) {
      return json({ ok: true, service: "vex-gate", time: new Date().toISOString() });
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
        return json({ ok: true, gate });
      }
      if (path === "/v1/report" && request.method === "POST") {
        return report(request, env);
      }
      if (path === "/v1/ack" && request.method === "POST") {
        return ack(request, env);
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
  const event = String(b.event || "open").slice(0, 16);
  const country = (request.cf && request.cf.country) ? String(request.cf.country) : "";
  const ip = request.headers.get("cf-connecting-ip") || "";
  const now = Date.now();

  const row = await env.DB.prepare(
    "SELECT opens FROM users WHERE did = ?1"
  ).bind(did).first();

  if (row) {
    await env.DB.prepare(
      "UPDATE users SET last_seen = ?2, opens = opens + 1, model = ?3, android = ?4, ver = ?5, lang = ?6, country = ?7 WHERE did = ?1"
    ).bind(did, now, model, android, ver, lang, country).run();
  } else {
    await env.DB.prepare(
      "INSERT INTO users (did, model, android, ver, lang, country, ip, opens, first_seen, last_seen) VALUES (?1,?2,?3,?4,?5,?6,?7,1,?8,?8)"
    ).bind(did, model, android, ver, lang, country, ip, now).run();
  }

  const opens = row ? (row.opens + 1) : 1;
  const total = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first();
  const gate = (await getSetting(env, "gate")) || "open";

  // notify owner (best-effort)
  const owner = await getSetting(env, "owner");
  if (owner) {
    const flag = country ? flagEmoji(country) + " " : "";
    const isNew = !row;
    const text =
      (isNew ? "🆕 <b>کاربر جدید Vex Client</b>\n" : "🔁 <b>ورود کاربر Vex Client</b>\n") +
      "📱 گوشی: " + esc(model) + "\n" +
      "🤖 اندروید: " + esc(android) + "\n" +
      "🏷 نسخه: " + esc(ver) + "\n" +
      "🌐 زبان: " + esc(lang) + (country ? "\n" + flag + "کشور: " + esc(country) : "") + "\n" +
      "🔢 دفعات ورود: " + opens + "\n" +
      "👥 کل کاربران: " + (total ? total.n : "?") + "\n" +
      "🕐 " + new Date(now).toISOString().replace("T", " ").slice(0, 16) + " UTC";
    await tgSend(env, owner, text);
  }

  return json({ ok: true, gate, users: total ? total.n : 0 });
}

async function ack(request, env) {
  let b = {};
  try { b = await request.json(); } catch (e) {}
  const state = b.state === "closed" ? "closed" : "open";
  const device = String(b.device || "?").slice(0, 80);
  const owner = await getSetting(env, "owner");
  if (owner) {
    const text = state === "closed"
      ? "🔒 Vex روی دستگاه «" + esc(device) + "» قفل شد (دستور /close اجرا شد)"
      : "🔓 Vex روی دستگاه «" + esc(device) + "» باز شد (دستور /open اجرا شد)";
    await tgSend(env, owner, text);
  }
  return json({ ok: true });
}

// ============================================================ telegram

async function handleUpdate(up, env) {
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
        "از این به بعد هر کس برنامه‌ی Vex Client را نصب کند یا باز کند، همین‌جا بهت خبر می‌دهم.\n\n" +
        "دستورها:\n" +
        "/stats — آمار کاربران\n" +
        "/users — آخرین کاربران\n" +
        "/close — بستن برنامه برای همه 🔒\n" +
        "/open — باز کردن برنامه 🔓\n" +
        "/help — راهنما");
    } else if (chatId !== owner) {
      await tgSend(env, chatId, "این ربات مخصوص مدیریت Vex Client است.");
    } else {
      await tgSend(env, chatId, "سلام مدیر 👋\n/stats · /users · /close · /open · /help");
    }
    return;
  }

  if (chatId !== owner) return; // only the owner can use commands

  if (lc.startsWith("/close")) {
    await setSetting(env, "gate", "closed");
    await tgSend(env, chatId, "🔒 برنامه برای همه بسته شد.\nبا /open دوباره باز می‌شود.");
  } else if (lc.startsWith("/open")) {
    await setSetting(env, "gate", "open");
    await tgSend(env, chatId, "🔓 برنامه باز شد و برای همه قابل استفاده است.");
  } else if (lc.startsWith("/stats")) {
    const t = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first();
    const today = await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM users WHERE first_seen >= ?1"
    ).bind(Date.now() - 86400000).first();
    const gate = (await getSetting(env, "gate")) || "open";
    await tgSend(env, chatId,
      "📊 <b>آمار Vex</b>\n" +
      "👥 کل کاربران: " + (t ? t.n : 0) + "\n" +
      "🆕 ۲۴ ساعت گذشته: " + (today ? today.n : 0) + "\n" +
      "وضعیت برنامه: " + (gate === "closed" ? "🔒 بسته" : "🔓 باز"));
  } else if (lc.startsWith("/users")) {
    const rs = await env.DB.prepare(
      "SELECT model, android, ver, country, opens, last_seen FROM users ORDER BY last_seen DESC LIMIT 20"
    ).all();
    let out = "👥 <b>آخرین کاربران</b>\n";
    for (const r of (rs.results || [])) {
      out += "\n" + (r.country ? flagEmoji(r.country) + " " : "") + esc(r.model || "?") +
        " · اندروید " + esc(r.android || "?") +
        " · v" + esc(r.ver || "?") +
        " · " + (r.opens || 1) + "×" +
        " · " + new Date(r.last_seen).toISOString().slice(5, 16).replace("T", " ");
    }
    if (!(rs.results || []).length) out += "\nهنوز کاری ثبت نشده";
    await tgSend(env, chatId, out);
  } else if (lc.startsWith("/help")) {
    await tgSend(env, chatId,
      "راهنما:\n/stats — آمار کاربران\n/users — آخرین کاربران\n/close — بستن برنامه 🔒\n/open — باز کردن 🔓");
  }
}

async function tgSend(env, chat, text) {
  if (!env.BOT_TOKEN || !chat) return;
  try {
    await fetch("https://api.telegram.org/bot" + env.BOT_TOKEN + "/sendMessage", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ chat_id: chat, text, parse_mode: "HTML", disable_web_page_preview: true })
    });
  } catch (e) { /* best effort */ }
}

// ============================================================ helpers

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
