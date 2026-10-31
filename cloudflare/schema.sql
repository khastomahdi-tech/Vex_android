-- Vex Gate D1 schema
CREATE TABLE IF NOT EXISTS users (
  did TEXT PRIMARY KEY,
  model TEXT,
  android TEXT,
  ver TEXT,
  lang TEXT,
  country TEXT,
  ip TEXT,
  opens INTEGER DEFAULT 1,
  first_seen INTEGER,
  last_seen INTEGER
);
CREATE TABLE IF NOT EXISTS settings (
  k TEXT PRIMARY KEY,
  v TEXT
);
-- سرورهای رایگان که فقط از طریق ربات مدیریت می‌شوند
CREATE TABLE IF NOT EXISTS free_servers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT,
  raw TEXT UNIQUE,
  proto TEXT,
  added_at INTEGER,
  enabled INTEGER DEFAULT 1
);
-- مصرف ترافیک هر دستگاه (دلتای جمع‌شده) — تفکیک سرور رایگان / شخصی
CREATE TABLE IF NOT EXISTS traffic (
  did TEXT,
  type TEXT,
  up INTEGER DEFAULT 0,
  down INTEGER DEFAULT 0,
  updated INTEGER,
  PRIMARY KEY (did, type)
);
-- مصرف تفکیکی هر سرور رایگان (نسخه‌ی ۴ به بعد) — srv = free_servers.id
CREATE TABLE IF NOT EXISTS traffic_srv (
  srv INTEGER,
  did TEXT,
  up INTEGER DEFAULT 0,
  down INTEGER DEFAULT 0,
  updated INTEGER,
  PRIMARY KEY (srv, did)
);
-- کاربران تلگرامی ربات (سمت عمومی: منو/دانلود/پشتیبانی + مخاطبان پیام همگانی)
CREATE TABLE IF NOT EXISTS bot_users (
  chat_id TEXT PRIMARY KEY,
  name TEXT,
  username TEXT,
  joined_at INTEGER,
  last_seen INTEGER,
  msgs INTEGER DEFAULT 0,
  banned INTEGER DEFAULT 0
);

-- کدهای اشتراک ۱۶ رقمی (ساخته‌شده از ربات تلگرامی دوم) + نشست‌های هر کد
CREATE TABLE IF NOT EXISTS sub_codes (
  code TEXT PRIMARY KEY,
  chat_id TEXT,
  days INTEGER DEFAULT 10,
  created INTEGER,
  expires INTEGER,
  active INTEGER DEFAULT 1
);
CREATE TABLE IF NOT EXISTS sub_sessions (
  code TEXT,
  did TEXT,
  device TEXT,
  activated INTEGER,
  last_seen INTEGER,
  PRIMARY KEY (code, did)
);
