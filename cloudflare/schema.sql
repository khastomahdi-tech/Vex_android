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
