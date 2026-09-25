import fs from 'node:fs'
import path from 'node:path'
import webpush from 'web-push'
import { db, now, uuid } from './db.js'
import { config } from './config.js'

/**
 * Web push, so the people who carry an iPhone get the same nagging the Android app does.
 * Safari delivers these only to a web app added to the Home Screen, which is how the house
 * uses it anyway. The keys are made once and kept beside the database.
 */
const KEY_FILE = path.join(config.dataDir, 'vapid.json')

function keys() {
  if (fs.existsSync(KEY_FILE)) {
    try {
      return JSON.parse(fs.readFileSync(KEY_FILE, 'utf8').replace(/^﻿/, ''))
    } catch (e) {
      console.log(`[push] unreadable ${KEY_FILE}: ${e.message}`)
    }
  }
  const made = webpush.generateVAPIDKeys()
  fs.writeFileSync(KEY_FILE, JSON.stringify(made, null, 2))
  console.log('[push] new VAPID keys written')
  return made
}

const VAPID = keys()
webpush.setVapidDetails('mailto:noreply@payandplan.local', VAPID.publicKey, VAPID.privateKey)

export const publicKey = VAPID.publicKey

// ---------------------------------------------------------------- devices

export function saveSubscription(userId, subscription, userAgent) {
  const endpoint = String(subscription?.endpoint || '')
  const p256dh = String(subscription?.keys?.p256dh || '')
  const auth = String(subscription?.keys?.auth || '')
  if (!endpoint || !p256dh || !auth) throw new Error('that is not a push subscription')

  const stamp = now()
  db.prepare(`
    INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent, created_at, last_ok, failures)
    VALUES (?,?,?,?,?,?,?,?,0)
    ON CONFLICT(endpoint) DO UPDATE SET
      user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth,
      user_agent = excluded.user_agent, failures = 0
  `).run(uuid(), userId, endpoint, p256dh, auth, String(userAgent || '').slice(0, 300), stamp, stamp)
}

export function dropSubscription(endpoint) {
  db.prepare('DELETE FROM push_subscriptions WHERE endpoint = ?').run(String(endpoint || ''))
}

export function deviceCount(userId) {
  return db.prepare('SELECT COUNT(*) c FROM push_subscriptions WHERE user_id = ?').get(userId).c
}

/**
 * Sends one message to every device a person carries. A subscription the push service has
 * given up on is deleted: a phone that was wiped must not keep the queue busy forever.
 */
export async function sendToUser(userId, payload) {
  const rows = db.prepare('SELECT * FROM push_subscriptions WHERE user_id = ?').all(userId)
  let sent = 0
  for (const row of rows) {
    const subscription = {
      endpoint: row.endpoint,
      keys: { p256dh: row.p256dh, auth: row.auth }
    }
    try {
      await webpush.sendNotification(subscription, JSON.stringify(payload), { TTL: 3600 })
      db.prepare('UPDATE push_subscriptions SET last_ok = ?, failures = 0 WHERE id = ?').run(now(), row.id)
      sent++
    } catch (e) {
      const gone = e.statusCode === 404 || e.statusCode === 410
      if (gone) {
        db.prepare('DELETE FROM push_subscriptions WHERE id = ?').run(row.id)
        console.log(`[push] device gone, forgotten (${row.user_id})`)
      } else {
        db.prepare('UPDATE push_subscriptions SET failures = failures + 1 WHERE id = ?').run(row.id)
        console.log(`[push] ${e.statusCode || ''} ${e.message}`)
      }
    }
  }
  return sent
}

// ---------------------------------------------------------------- what to say

const MINUTE = 60_000
const DAY = 24 * 60 * MINUTE

const EMOJI = { APPOINTMENT: '🗓', REMINDER: '⏰', INCOME: '💰', BILL: '💸' }

const money = (cents, currency) => {
  const symbol = { EUR: '€', USD: '$', GBP: '£' }[String(currency || 'EUR').toUpperCase()] || ''
  return `${symbol}${(Math.abs(cents) / 100).toFixed(2)}`
}

const clock = (minutes) =>
  `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`

/** The moment an entry is due, in the clock of the house. */
function dueAt(row) {
  const day = new Date(row.due_date * DAY)
  const local = new Date(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate())
  return local.getTime() + (row.due_time_minutes || 0) * MINUTE
}

function wording(row, when) {
  const late = Math.floor((Date.now() - when) / DAY)
  const kind = row.kind || 'BILL'
  const amount = money(row.amount_cents, row.currency)
  const title = `${EMOJI[kind] || EMOJI.BILL} ${row.title}`

  if (kind === 'APPOINTMENT') {
    const where = row.location ? ` · 📍 ${row.location}` : ''
    return { title, body: (late > 0 ? `Was at ${clock(row.due_time_minutes)}` : `At ${clock(row.due_time_minutes)}`) + where }
  }
  if (kind === 'REMINDER') {
    return { title, body: late > 0 ? `${late} day${late > 1 ? 's' : ''} overdue` : `By ${clock(row.due_time_minutes)}` }
  }
  if (kind === 'INCOME') {
    return { title, body: `${late > 0 ? 'Expected' : 'Coming in'}  •  ${amount}` }
  }
  return {
    title,
    body: (late > 0 ? `OVERDUE by ${late} day${late > 1 ? 's' : ''}` : 'Due today') + `  •  ${amount}`
  }
}

/** Who carries this one: its owner, or everybody sharing the calendar. */
function audience(row) {
  const members = db.prepare('SELECT user_id FROM calendar_members WHERE calendar_id = ?')
    .all(row.calendar_id).map((m) => m.user_id)
  if (row.visibility === 'PRIVATE') {
    return [...new Set([row.owner_user_id, row.created_by_user_id].filter(Boolean))]
  }
  return row.owner_user_id ? [row.owner_user_id] : members
}

// ---------------------------------------------------------------- the rounds

/** Nobody wants a phone shouting at three in the morning. */
function quietHour(date = new Date()) {
  const h = date.getHours()
  return h >= 22 || h < 7
}

const MAX_PER_DAY = 6

/**
 * Once a minute: everything still open and armed that has come due, to the people who carry
 * it, and again every nagMinutes until it is ticked off. The first shout can come earlier,
 * by as many days as the entry asks for.
 */
export async function round() {
  const stamp = Date.now()
  if (quietHour()) return 0

  const rows = db.prepare(`
    SELECT p.*, c.currency FROM payments p
    JOIN calendars c ON c.id = p.calendar_id
    WHERE p.deleted_at IS NULL AND c.deleted_at IS NULL
      AND p.status = 'PENDING' AND p.alarm_enabled = 1
      AND p.due_date BETWEEN ? AND ?
  `).all(Math.floor(stamp / DAY) - 60, Math.floor(stamp / DAY) + 30)

  let sent = 0
  for (const row of rows) {
    const when = dueAt(row)
    const first = when - (row.remind_days_before || 0) * DAY
    if (stamp < first) continue

    const nag = Number(row.nag_minutes || 0)
    const words = wording(row, when)

    for (const userId of audience(row)) {
      const log = db.prepare('SELECT * FROM push_log WHERE payment_id = ? AND user_id = ?')
        .get(row.id, userId)

      if (log) {
        // said already: only nag once it is actually due, and never more than a few times a day
        if (nag <= 0 || stamp < when) continue
        if (stamp - log.sent_at < nag * MINUTE) continue
        const sameDay = new Date(log.sent_at).toDateString() === new Date(stamp).toDateString()
        if (sameDay && log.count >= MAX_PER_DAY) continue
      }

      const count = log && new Date(log.sent_at).toDateString() === new Date(stamp).toDateString()
        ? log.count + 1 : 1
      const delivered = await sendToUser(userId, {
        ...words,
        tag: row.id,
        url: `/?open=${row.id}`
      })
      if (delivered > 0) {
        db.prepare(`
          INSERT INTO push_log (payment_id, user_id, sent_at, count) VALUES (?,?,?,?)
          ON CONFLICT(payment_id, user_id) DO UPDATE SET sent_at = excluded.sent_at, count = excluded.count
        `).run(row.id, userId, stamp, count)
        sent += delivered
      }
    }
  }
  return sent
}

/** Starts the minute by minute rounds; safe to call once at boot. */
export function startRounds() {
  const tick = () => {
    round().catch((e) => console.log(`[push] round failed: ${e.message}`))
  }
  setTimeout(tick, 20_000)
  setInterval(tick, 60_000)
  console.log('[push] rounds armed')
}
