import { DatabaseSync } from 'node:sqlite'
import fs from 'node:fs'
import path from 'node:path'
import { config } from './config.js'

const DATA_DIR = config.dataDir
fs.mkdirSync(DATA_DIR, { recursive: true })
fs.mkdirSync(path.join(DATA_DIR, 'files'), { recursive: true })

export const FILES_DIR = path.join(DATA_DIR, 'files')

export const db = new DatabaseSync(path.join(DATA_DIR, 'payandplan.sqlite'))
db.exec('PRAGMA journal_mode = WAL')
db.exec('PRAGMA foreign_keys = ON')

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  name TEXT NOT NULL,
  color_index INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS calendars (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  color_index INTEGER NOT NULL DEFAULT 0,
  owner_user_id TEXT NOT NULL,
  invite_code TEXT NOT NULL UNIQUE,
  currency TEXT NOT NULL DEFAULT 'EUR',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE IF NOT EXISTS calendar_members (
  calendar_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'member',
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (calendar_id, user_id),
  FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS payments (
  id TEXT PRIMARY KEY,
  calendar_id TEXT NOT NULL,
  series_id TEXT NOT NULL,
  owner_user_id TEXT,
  title TEXT NOT NULL,
  amount_cents INTEGER NOT NULL,
  currency TEXT NOT NULL DEFAULT 'EUR',
  color_index INTEGER NOT NULL DEFAULT 0,
  category TEXT NOT NULL DEFAULT '',
  due_date INTEGER NOT NULL,
  due_time_minutes INTEGER NOT NULL DEFAULT 540,
  recurrence TEXT NOT NULL DEFAULT 'NONE',
  recurrence_end_date INTEGER,
  notes TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'PENDING',
  paid_at INTEGER,
  paid_amount_cents INTEGER,
  paid_by_user_id TEXT,
  remind_days_before INTEGER NOT NULL DEFAULT 0,
  nag_minutes INTEGER NOT NULL DEFAULT 60,
  alarm_enabled INTEGER NOT NULL DEFAULT 1,
  require_receipt INTEGER NOT NULL DEFAULT 1,
  installment_index INTEGER NOT NULL DEFAULT 0,
  installment_count INTEGER NOT NULL DEFAULT 0,
  created_by_user_id TEXT,
  visibility TEXT NOT NULL DEFAULT 'SHARED',
  shopping_list_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_payments_cal ON payments(calendar_id, updated_at);

CREATE TABLE IF NOT EXISTS day_notes (
  id TEXT PRIMARY KEY,
  calendar_id TEXT NOT NULL,
  epoch_day INTEGER NOT NULL,
  text TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_day_notes_unique ON day_notes(calendar_id, epoch_day);

CREATE TABLE IF NOT EXISTS attachments (
  id TEXT PRIMARY KEY,
  calendar_id TEXT NOT NULL,
  owner_type TEXT NOT NULL,
  payment_id TEXT,
  epoch_day INTEGER,
  file_name TEXT NOT NULL,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL DEFAULT 0,
  storage_name TEXT NOT NULL,
  is_receipt INTEGER NOT NULL DEFAULT 0,
  uploaded_by TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_attachments_cal ON attachments(calendar_id, updated_at);

CREATE TABLE IF NOT EXISTS shopping_lists (
  id TEXT PRIMARY KEY,
  calendar_id TEXT NOT NULL,
  title TEXT NOT NULL,
  notes TEXT NOT NULL DEFAULT '',
  color_index INTEGER NOT NULL DEFAULT 0,
  due_date INTEGER,
  assigned_to_user_id TEXT,
  created_by_user_id TEXT,
  budget_cents INTEGER,
  actual_cents INTEGER,
  status TEXT NOT NULL DEFAULT 'OPEN',
  done_at INTEGER,
  done_by_user_id TEXT,
  payment_id TEXT,
  visibility TEXT NOT NULL DEFAULT 'SHARED',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_lists_cal ON shopping_lists(calendar_id, updated_at);

CREATE TABLE IF NOT EXISTS shopping_items (
  id TEXT PRIMARY KEY,
  list_id TEXT NOT NULL,
  calendar_id TEXT NOT NULL,
  text TEXT NOT NULL,
  quantity TEXT NOT NULL DEFAULT '',
  barcode TEXT NOT NULL DEFAULT '',
  checked INTEGER NOT NULL DEFAULT 0,
  price_cents INTEGER,
  sort_index INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  FOREIGN KEY (list_id) REFERENCES shopping_lists(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS push_subscriptions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  endpoint TEXT NOT NULL UNIQUE,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  user_agent TEXT,
  created_at INTEGER NOT NULL,
  last_ok INTEGER,
  failures INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_push_user ON push_subscriptions(user_id);

CREATE TABLE IF NOT EXISTS push_log (
  payment_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  sent_at INTEGER NOT NULL,
  count INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (payment_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_items_list ON shopping_items(list_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_items_cal ON shopping_items(calendar_id, updated_at);

CREATE TABLE IF NOT EXISTS notes (
  id TEXT PRIMARY KEY,
  calendar_id TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  body TEXT NOT NULL DEFAULT '',
  category TEXT NOT NULL DEFAULT '',
  color_index INTEGER NOT NULL DEFAULT 0,
  pinned INTEGER NOT NULL DEFAULT 0,
  owner_user_id TEXT,
  created_by_user_id TEXT,
  visibility TEXT NOT NULL DEFAULT 'SHARED',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_notes_cal ON notes(calendar_id, updated_at);
`)

// columns added after the first release
for (const [table, column, ddl] of [
  ['payments', 'created_by_user_id', 'ALTER TABLE payments ADD COLUMN created_by_user_id TEXT'],
  ['payments', 'visibility', "ALTER TABLE payments ADD COLUMN visibility TEXT NOT NULL DEFAULT 'SHARED'"],
  ['payments', 'shopping_list_id', 'ALTER TABLE payments ADD COLUMN shopping_list_id TEXT'],
  ['payments', 'kind', "ALTER TABLE payments ADD COLUMN kind TEXT NOT NULL DEFAULT 'BILL'"],
  ['payments', 'location', "ALTER TABLE payments ADD COLUMN location TEXT NOT NULL DEFAULT ''"],
  ['payments', 'duration_minutes', 'ALTER TABLE payments ADD COLUMN duration_minutes INTEGER NOT NULL DEFAULT 0'],
  ['payments', 'latitude', 'ALTER TABLE payments ADD COLUMN latitude REAL'],
  ['payments', 'longitude', 'ALTER TABLE payments ADD COLUMN longitude REAL'],
  ['shopping_lists', 'due_time_minutes', 'ALTER TABLE shopping_lists ADD COLUMN due_time_minutes INTEGER NOT NULL DEFAULT 1080'],
  ['attachments', 'item_id', 'ALTER TABLE attachments ADD COLUMN item_id TEXT'],
  ['attachments', 'note_id', 'ALTER TABLE attachments ADD COLUMN note_id TEXT'],
  ['shopping_items', 'barcode', "ALTER TABLE shopping_items ADD COLUMN barcode TEXT NOT NULL DEFAULT ''"]
]) {
  const has = db.prepare(`PRAGMA table_info(${table})`).all().some((c) => c.name === column)
  if (!has) db.exec(ddl)
}

export const now = () => Date.now()

export function uuid() {
  return crypto.randomUUID()
}

export function inviteCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  let out = ''
  for (let i = 0; i < 6; i++) out += alphabet[Math.floor(Math.random() * alphabet.length)]
  return out
}

export function isMember(calendarId, userId) {
  return !!db
    .prepare('SELECT 1 FROM calendar_members WHERE calendar_id = ? AND user_id = ?')
    .get(calendarId, userId)
}
