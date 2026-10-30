# Vex Gate — ربات تلگرام Vex روی Cloudflare Pages + D1

بک‌اند ربات تلگرام برنامه‌ی Vex Client: آمار نصب/ورود کاربرها + کلید خاموش/روشن از راه دور (kill switch).

**آدرس سرویس:** https://vex-gate.pages.dev
**پروژه‌ی Pages:** `vex-gate` · **دیتابیس D1:** `vex-gate-db`

## مسیرها (Endpoints)

| مسیر | روش | کار |
|------|-----|-----|
| `/` | GET | وضعیت سلامت سرویس |
| `/tg` | POST | وب‌هوک تلگرام (با `X-Telegram-Bot-Api-Secret-Token` تأیید می‌شود) |
| `/v1/state` | GET | وضعیت قفل/باز برنامه → `{"ok":true,"gate":"open"|"closed"}` |
| `/v1/report` | POST | ثبت نصب/ورود دستگاه در D1 + پیام به مدیر |
| `/v1/ack` | POST | تأیید اجرای قفل/باز شدن روی دستگاه + پیام به مدیر |

هر سه مسیر `/v1/*` به هدر `X-Vex-Key` (کلید مشترک برنامه) نیاز دارند.

## دستورهای ربات

اولین کسی که به ربات `/start` بدهد **مدیر** می‌شود:

- `/stats` — آمار کل کاربران + ۲۴ ساعت گذشته + وضعیت برنامه
- `/users` — ۲۰ کاربر آخر (گوشی، اندروید، نسخه، کشور، تعداد ورود)
- `/close` — 🔒 قفل کردن برنامه برای همه (تونل هم قطع می‌شود)
- `/open` — 🔓 باز کردن برنامه
- `/help` — راهنما

برنامه‌ی اندروید وضعیت دروازه را هنگام ورود و بعد هر ۵ دقیقه از `/v1/state` می‌خواند؛
اگر مدیر `/close` داده باشد، صفحه‌ی «برنامه غیرفعال شده است» جای رابط برنامه می‌نشیند.

## استقرار (Deploy)

**خودکار:** هر push که پوشه‌ی `cloudflare/` را تغییر دهد، ورک‌فلو‌ی
`.github/workflows/deploy-bot.yml` با `wrangler pages deploy` منتشر می‌کند
(سکرت‌های `CLOUDFLARE_API_TOKEN` و `CLOUDFLARE_ACCOUNT_ID` در ریپو تنظیم شده‌اند).

**دستی:**
```bash
cd cloudflare
npx wrangler pages deploy . --project-name vex-gate --branch main
```

سکرت‌های پروژه‌ی Pages (یک‌بار تنظیم شده‌اند و در دیپلوی‌ها باقی می‌مانند):
`BOT_TOKEN` · `APP_KEY` · `WEBHOOK_SECRET`

## دیتابیس D1

ساخت جداول (یک‌بار اجرا شده):
```bash
npx wrangler d1 execute vex-gate-db --remote --file=schema.sql
```

## وب‌هوک تلگرام (یک‌بار تنظیم شده)
```
https://api.telegram.org/bot<TOKEN>/setWebhook
  ?url=https://vex-gate.pages.dev/tg
  &secret_token=<WEBHOOK_SECRET>
  &allowed_updates=["message"]
```
