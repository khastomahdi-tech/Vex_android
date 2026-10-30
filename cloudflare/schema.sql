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
