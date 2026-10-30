/**
 * Vex Gate v2 — Telegram bot backend for Vex Client
 * Cloudflare Pages + D1 (vex-gate-db)
 *
 * Endpoints:
 *   POST /tg                 → Telegram webhook (X-Telegram-Bot-Api-Secret-Token)
 *   GET  /v1/state?did=...   → {"ok":true,"gate":"open"|"closed","reason":"..."}
 *   POST /v1/report          → install/open report → D1 + rich Telegram message to owner
 *   POST /v1/ack             → device ack lock/unlock → owner notified
 *   GET  /v1/freeservers     → free servers managed from the bot → {"ok":true,"servers":[...]}
 *   GET  /                   → health
 *
 * /v1/* endpoints require header  X-Vex-Key: <APP_KEY>
 *
 * Bot (inline-button menu; owner = first chat that sends /start):
 *   📊 آمار · 👥 کاربران · 🖥 سرورهای رایگان · 🟢 وضعیت · 🔒 بستن · 🔓 باز کردن
 *   Free servers: add (paste configs) / list / delete one / delete all — D1-backed,
 *   pushed to every user's «سرورهای رایگان» tab (read-only in the app).
 *   /close [reason] locks the app; locked users see «دسترسی برنامه بسته شده است» + reason.
 */

const DEFAULT_CLOSE_REASON =
  "یا برنامه در حال به‌روزرسانی است یا مشکل فنی موقت پیش آمده است.\nلطفاً کمی بعد دوباره تلاش کن؛ خبرها در کانال تلگرام منتشر می‌شود.";

const PROTOCOLS = ["vless", "vmess", "trojan", "ss"];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && (path === "/" || path === "")) {
      return json({ ok: true, service: "vex-gate", v: 2, time: new Date().toISOString() });
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
      if (path === "/v1/freeservers" && request.method === "GET") {
        const rs = await env.DB.prepare(
          "SELECT id, name, proto, raw FROM free_servers WHERE enabled = 1 ORDER BY id"
        ).all();
        return json({ ok: true, servers: rs.results || [] });
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

  // notify owner: new install (full details) or version update — silent for routine opens
  const owner = await getSetting(env, "owner");
  if (owner) {
    const isNew = !row;
    const isUpdate = row && row.ver !== ver;
    if (isNew || isUpdate) {
      const flag = country ? flagEmoji(country) + " " : "";
      const head = isNew
        ? "🆕 <b>کاربر جدید Vex Client نصب کرد!</b>"
        : "🔄 <b>کاربر قبلی برنامه را آپدیت کرد</b>";
      let text = head + "\n" +
        "🌐 IP: <code>" + esc(ip || "?") + "</code>" + (country ? "  " + flag + esc(country) : "") + "\n" +
        "📱 گوشی: " + esc(model) + "\n" +
        "🤖 سیستم: " + esc(android) + "\n" +
        "🏷 نسخه: " + esc(ver) + "\n" +
        "🌍 زبان: " + esc(lang) + "\n" +
        "🔢 دفعات ورود: " + opens + "\n" +
        "👥 کل کاربران: " + (total ? total.n : "?") + "\n" +
        "🕐 " + new Date(now).toISOString().replace("T", " ").slice(0, 16) + " UTC";
      await tgSend(env, owner, text);
    }
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
        "هر کس Vex Client را نصب کند یا آپدیت کند، همین‌جا بهت خبر می‌دهم (IP، مدل گوشی، نسخه اندروید و…).\n\n" +
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
    await tgSend(env, chatId, "🔓 برنامه باز شد و برای همه قابل استفاده است.", mainMenu());
  } else if (lc.startsWith("/stats")) {
    await sendStats(chatId, env);
  } else if (lc.startsWith("/users")) {
    await sendUsers(chatId, env);
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
  if (data === "menu") {
    await tgSend(env, chatId, "منوی مدیریت Vex:", mainMenu());
  } else if (data === "stats") {
    await sendStats(chatId, env);
  } else if (data === "users") {
    await sendUsers(chatId, env);
  } else if (data === "state") {
    await sendState(chatId, env);
  } else if (data === "close") {
    await doClose(chatId, env, null);
  } else if (data === "close_r") {
    await setSetting(env, "pend_" + chatId, "close_reason");
    await tgSend(env, chatId, "✍️ دلیل بستن برنامه را بفرست (این متن به کاربران نشان داده می‌شود):\n\nبرای لغو /cancel");
  } else if (data === "open") {
    await setSetting(env, "gate", "open");
    await tgSend(env, chatId, "🔓 برنامه باز شد و برای همه قابل استفاده است.", mainMenu());
  } else if (data === "fs") {
    await sendFsMenu(chatId, env);
  } else if (data === "fs_add") {
    await askAddFreeServer(chatId, env);
  } else if (data === "fs_list") {
    await sendFsList(chatId, env);
  } else if (data === "fs_delall") {
    const n = await countFs(env);
    if (!n) { await tgSend(env, chatId, "لیست سرورهای رایگان خالی است."); return; }
    await tgSend(env, chatId, "⚠️ مطمئنی همه‌ی " + n + " سرور رایگان حذف شود؟",
      kb([[{ text: "✅ بله حذف همه", callback_data: "fs_delall_yes" }, { text: "❌ نه", callback_data: "fs" }]]));
  } else if (data === "fs_delall_yes") {
    await env.DB.prepare("DELETE FROM free_servers").run();
    await tgSend(env, chatId, "🗑 همه‌ی سرورهای رایگان حذف شدند.", fsMenuKb());
  } else if (data.startsWith("fs_del_")) {
    const id = parseInt(data.slice(7), 10);
    if (id) await env.DB.prepare("DELETE FROM free_servers WHERE id = ?1").bind(id).run();
    await sendFsList(chatId, env);
  }
}

// ---------------- bot builders ----------------

function mainMenu() {
  return kb([
    [{ text: "📊 آمار", callback_data: "stats" }, { text: "👥 کاربران", callback_data: "users" }],
    [{ text: "🖥 سرورهای رایگان", callback_data: "fs" }],
    [{ text: "🟢 وضعیت برنامه", callback_data: "state" }],
    [{ text: "🔒 بستن برنامه", callback_data: "close" }, { text: "🔓 باز کردن", callback_data: "open" }]
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

async function sendStats(chatId, env) {
  const t = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first();
  const today = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM users WHERE first_seen >= ?1"
  ).bind(Date.now() - 86400000).first();
  const op = await env.DB.prepare("SELECT COALESCE(SUM(opens),0) AS n FROM users").first();
  const fs = await countFs(env);
  const gate = (await getSetting(env, "gate")) || "open";
  await tgSend(env, chatId,
    "📊 <b>آمار Vex Client</b>\n" +
    "👥 کل کاربران (نصب‌ها): <b>" + (t ? t.n : 0) + "</b>\n" +
    "🆕 نصب ۲۴ ساعت گذشته: " + (today ? today.n : 0) + "\n" +
    "🔁 مجموع ورودها: " + (op ? op.n : 0) + "\n" +
    "🖥 سرورهای رایگان فعال: " + fs + "\n" +
    "وضعیت برنامه: " + (gate === "closed" ? "🔒 بسته" : "🔓 باز"));
}

async function sendUsers(chatId, env) {
  const rs = await env.DB.prepare(
    "SELECT model, android, ver, country, ip, opens, last_seen FROM users ORDER BY last_seen DESC LIMIT 20"
  ).all();
  let out = "👥 <b>آخرین کاربران</b>\n";
  for (const r of (rs.results || [])) {
    out += "\n" + (r.country ? flagEmoji(r.country) + " " : "") +
      "IP: <code>" + esc(r.ip || "?") + "</code> · " + esc(r.model || "?") +
      " · " + esc(r.android || "?") +
      " · v" + esc(r.ver || "?") +
      " · " + (r.opens || 1) + "×" +
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
    "کانفیگ‌ها را بفرست — هر خط یک کانفیگ:\n" +
    "<code>vless:// · vmess:// · trojan:// · ss://</code>\n\n" +
    "می‌توانی چند کانفیگ را هم‌زمان بفرستی. بعد از افزودن، سرورها در بخش «سرورهای رایگان» برنامه‌ی همه‌ی کاربران ظاهر می‌شوند (بدون امکان ویرایش یا حذف از سمت آن‌ها).\n\nبرای لغو /cancel");
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

async function addFreeServers(text, chatId, env) {
  const tokens = text.split(/[\s\n]+/).map(s => s.trim()).filter(Boolean);
  let added = 0, dup = 0, bad = 0, names = "";
  for (const t of tokens) {
    const p = t.split("://")[0].toLowerCase();
    if (!PROTOCOLS.includes(p)) { bad++; continue; }
    const raw = t.slice(0, 900);
    const ex = await env.DB.prepare("SELECT 1 AS x FROM free_servers WHERE raw = ?1").bind(raw).first();
    if (ex) { dup++; continue; }
    let name = extractRemark(raw, p);
    if (!name) {
      const c = await env.DB.prepare("SELECT COUNT(*) AS n FROM free_servers").first();
      name = "سرور رایگان " + ((c ? c.n : 0) + 1);
    }
    await env.DB.prepare(
      "INSERT INTO free_servers (name, raw, proto, added_at, enabled) VALUES (?1,?2,?3,?4,1)"
    ).bind(name, raw, p, Date.now()).run();
    added++;
    if (names.length < 400) names += "\n• " + name;
  }
  const total = await countFs(env);
  await tgSend(env, chatId,
    "✅ " + added + " سرور اضافه شد" +
    (dup ? "\n♻️ " + dup + " تکرار رد شد" : "") +
    (bad ? "\n❌ " + bad + " نامعتبر بود" : "") +
    (names ? "\n" + names : "") +
    "\n\n🖥 مجموع سرورهای رایگان فعال: <b>" + total + "</b>\nکاربران با لمس «به‌روزرسانی» در تب سرورهای رایگان آن‌ها را می‌گیرند.", fsMenuKb());
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
    "SELECT id, name, proto, added_at FROM free_servers ORDER BY id DESC LIMIT 50"
  ).all();
  const list = rs.results || [];
  if (!list.length) {
    await tgSend(env, chatId, "📃 لیست سرورهای رایگان خالی است.\nبا ➕ افزودن سرور اولین کانفیگ را بفرست.", fsMenuKb());
    return;
  }
  let out = "📃 <b>سرورهای رایگان (" + list.length + ")</b> — برای حذف روی هرکدام بزن:\n";
  const rows = [];
  for (const r of list) {
    out += "\n" + (r.proto || "?").toUpperCase() + " · " + esc(r.name || "?") +
      " · " + new Date(r.added_at).toISOString().slice(0, 10);
    rows.push([{ text: "🗑 حذف: " + (r.name || r.id).slice(0, 24), callback_data: "fs_del_" + r.id }]);
  }
  rows.push([{ text: "⬅️ بازگشت", callback_data: "fs" }]);
  await tgSend(env, chatId, out, kb(rows));
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
