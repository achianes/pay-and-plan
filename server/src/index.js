import express from 'express'
import cors from 'cors'
import bcrypt from 'bcryptjs'
import jwt from 'jsonwebtoken'
import multer from 'multer'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { db, FILES_DIR, now, uuid, inviteCode, isMember } from './db.js'
import { config } from './config.js'
import * as banking from './banking.js'
import { unfurl, fetchImage } from './unfurl.js'
import { readReceipt } from './receipt.js'
import { lookupProduct, productImage } from './products.js'
import { searchPlaces, resolveEventLink } from './places.js'
import * as push from './push.js'
import { mountBackup, startBackupRounds } from './backup.js'
import { startApkHandout } from './apk.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const PORT = config.port
const SECRET = config.jwtSecret
const MAX_UPLOAD = config.maxUploadMb * 1024 * 1024

const app = express()
app.use(cors())
app.use(express.json({ limit: '2mb' }))

// ---------------------------------------------------------------- helpers

const paymentColumns = [
  'id', 'calendar_id', 'series_id', 'owner_user_id', 'title', 'amount_cents', 'currency',
  'color_index', 'category', 'group_key', 'due_date', 'due_time_minutes', 'recurrence', 'recurrence_end_date',
  'notes', 'status', 'paid_at', 'paid_amount_cents', 'paid_by_user_id', 'remind_days_before',
  'nag_minutes', 'alarm_enabled', 'require_receipt', 'installment_index', 'installment_count',
  'created_by_user_id', 'visibility', 'shopping_list_id', 'kind', 'location', 'duration_minutes',
  'latitude', 'longitude',
  'created_at', 'updated_at', 'deleted_at'
]

const listColumns = [
  'id', 'calendar_id', 'title', 'notes', 'color_index', 'due_date', 'due_time_minutes', 'assigned_to_user_id',
  'created_by_user_id', 'budget_cents', 'actual_cents', 'status', 'done_at', 'done_by_user_id',
  'payment_id', 'visibility', 'created_at', 'updated_at', 'deleted_at'
]

const noteColumns = [
  'id', 'calendar_id', 'title', 'body', 'category', 'color_index', 'pinned', 'owner_user_id',
  'created_by_user_id', 'visibility', 'created_at', 'updated_at', 'deleted_at'
]

const itemColumns = [
  'id', 'list_id', 'calendar_id', 'text', 'quantity', 'barcode', 'checked', 'price_cents',
  'sort_index', 'created_at', 'updated_at', 'deleted_at'
]

const camel = (s) => s.replace(/_([a-z])/g, (_, c) => c.toUpperCase())
const snake = (s) => s.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase())

function rowOut(row) {
  if (!row) return null
  const out = {}
  for (const [k, v] of Object.entries(row)) out[camel(k)] = v
  return out
}

function auth(req, res, next) {
  const header = req.headers.authorization || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : req.query.token
  if (!token) return res.status(401).json({ error: 'missing token' })
  try {
    const payload = jwt.verify(token, SECRET)
    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(payload.sub)
    if (!user) return res.status(401).json({ error: 'unknown user' })
    req.user = user
    next()
  } catch {
    res.status(401).json({ error: 'bad token' })
  }
}

function requireMember(req, res, next) {
  const calendarId = req.params.calendarId || req.body.calendarId
  if (!calendarId) return res.status(400).json({ error: 'calendarId required' })
  if (!isMember(calendarId, req.user.id)) return res.status(403).json({ error: 'not a member' })
  req.calendarId = calendarId
  next()
}

function publicUser(u) {
  return { id: u.id, email: u.email, name: u.name, colorIndex: u.color_index }
}

function calendarPayload(calendarId) {
  const calendar = db.prepare('SELECT * FROM calendars WHERE id = ?').get(calendarId)
  const members = db
    .prepare(
      `SELECT u.*, m.role FROM calendar_members m
       JOIN users u ON u.id = m.user_id
       WHERE m.calendar_id = ? ORDER BY m.joined_at`
    )
    .all(calendarId)
  return {
    ...rowOut(calendar),
    members: members.map((m) => ({ ...publicUser(m), role: m.role }))
  }
}

// ---------------------------------------------------------------- auth

app.post('/api/auth/register', (req, res) => {
  const { email, password, name } = req.body || {}
  if (!email || !password || password.length < 6) {
    return res.status(400).json({ error: 'email and a password of 6+ chars are required' })
  }
  const clean = String(email).trim().toLowerCase()
  if (db.prepare('SELECT 1 FROM users WHERE email = ?').get(clean)) {
    return res.status(409).json({ error: 'that email is already registered' })
  }
  const id = uuid()
  const stamp = now()
  db.prepare(
    'INSERT INTO users (id, email, password_hash, name, color_index, created_at) VALUES (?,?,?,?,?,?)'
  ).run(id, clean, bcrypt.hashSync(String(password), 10), String(name || clean.split('@')[0]),
    Math.floor(Math.random() * 8), stamp)

  // everybody starts with a calendar of their own
  const calId = uuid()
  db.prepare(
    `INSERT INTO calendars (id, name, color_index, owner_user_id, invite_code, currency, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?)`
  ).run(calId, 'My calendar', 1, id, inviteCode(), 'EUR', stamp, stamp)
  db.prepare('INSERT INTO calendar_members (calendar_id, user_id, role, joined_at) VALUES (?,?,?,?)')
    .run(calId, id, 'owner', stamp)

  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(id)
  res.json({ token: jwt.sign({ sub: id }, SECRET, { expiresIn: '365d' }), user: publicUser(user) })
})

app.post('/api/auth/login', (req, res) => {
  const { email, password } = req.body || {}
  const clean = String(email || '').trim().toLowerCase()
  const user = db.prepare('SELECT * FROM users WHERE email = ?').get(clean)
  if (!user || !bcrypt.compareSync(String(password || ''), user.password_hash)) {
    // never log the secret itself, only enough to tell a typo from a missing account
    console.log(
      `[login failed] email=${JSON.stringify(clean)} accountExists=${!!user} ` +
      `passwordChars=${String(password || '').length}`
    )
    return res.status(401).json({ error: 'wrong email or password' })
  }
  console.log(`[login ok] ${clean}`)
  res.json({ token: jwt.sign({ sub: user.id }, SECRET, { expiresIn: '365d' }), user: publicUser(user) })
})

app.get('/api/me', auth, (req, res) => {
  const calendars = db
    .prepare(
      `SELECT c.id FROM calendars c
       JOIN calendar_members m ON m.calendar_id = c.id
       WHERE m.user_id = ? AND c.deleted_at IS NULL
       ORDER BY c.created_at`
    )
    .all(req.user.id)
  res.json({
    user: publicUser(req.user),
    calendars: calendars.map((c) => calendarPayload(c.id)),
    // what this particular server can do, so clients hide what is not there
    features: { receipts: !!config.ollamaUrl }
  })
})

app.patch('/api/me', auth, (req, res) => {
  const { name, colorIndex } = req.body || {}
  db.prepare('UPDATE users SET name = COALESCE(?, name), color_index = COALESCE(?, color_index) WHERE id = ?')
    .run(name ?? null, colorIndex ?? null, req.user.id)
  res.json({ user: publicUser(db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id)) })
})

// ---------------------------------------------------------------- calendars

app.post('/api/calendars', auth, (req, res) => {
  const { name, colorIndex, currency } = req.body || {}
  const id = uuid()
  const stamp = now()
  db.prepare(
    `INSERT INTO calendars (id, name, color_index, owner_user_id, invite_code, currency, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?)`
  ).run(id, String(name || 'Calendar'), Number(colorIndex || 0), req.user.id, inviteCode(),
    String(currency || 'EUR'), stamp, stamp)
  db.prepare('INSERT INTO calendar_members (calendar_id, user_id, role, joined_at) VALUES (?,?,?,?)')
    .run(id, req.user.id, 'owner', stamp)
  res.json(calendarPayload(id))
})

app.post('/api/calendars/join', auth, (req, res) => {
  const code = String(req.body?.code || '').trim().toUpperCase()
  const calendar = db.prepare('SELECT * FROM calendars WHERE invite_code = ? AND deleted_at IS NULL').get(code)
  if (!calendar) return res.status(404).json({ error: 'no calendar with that code' })
  if (!isMember(calendar.id, req.user.id)) {
    db.prepare('INSERT INTO calendar_members (calendar_id, user_id, role, joined_at) VALUES (?,?,?,?)')
      .run(calendar.id, req.user.id, 'member', now())
  }
  res.json(calendarPayload(calendar.id))
})

app.patch('/api/calendars/:calendarId', auth, requireMember, (req, res) => {
  const { name, colorIndex, currency } = req.body || {}
  db.prepare(
    `UPDATE calendars SET name = COALESCE(?, name), color_index = COALESCE(?, color_index),
     currency = COALESCE(?, currency), updated_at = ? WHERE id = ?`
  ).run(name ?? null, colorIndex ?? null, currency ?? null, now(), req.calendarId)
  res.json(calendarPayload(req.calendarId))
})

app.post('/api/calendars/:calendarId/leave', auth, requireMember, (req, res) => {
  const calendar = db.prepare('SELECT * FROM calendars WHERE id = ?').get(req.calendarId)
  if (calendar.owner_user_id === req.user.id) {
    return res.status(400).json({ error: 'the owner cannot leave; delete the calendar instead' })
  }
  db.prepare('DELETE FROM calendar_members WHERE calendar_id = ? AND user_id = ?')
    .run(req.calendarId, req.user.id)
  res.json({ ok: true })
})

app.delete('/api/calendars/:calendarId/members/:userId', auth, requireMember, (req, res) => {
  const calendar = db.prepare('SELECT * FROM calendars WHERE id = ?').get(req.calendarId)
  if (calendar.owner_user_id !== req.user.id) return res.status(403).json({ error: 'owner only' })
  if (req.params.userId === calendar.owner_user_id) return res.status(400).json({ error: 'cannot remove the owner' })
  db.prepare('DELETE FROM calendar_members WHERE calendar_id = ? AND user_id = ?')
    .run(req.calendarId, req.params.userId)
  res.json(calendarPayload(req.calendarId))
})

app.delete('/api/calendars/:calendarId', auth, requireMember, (req, res) => {
  const calendar = db.prepare('SELECT * FROM calendars WHERE id = ?').get(req.calendarId)
  if (calendar.owner_user_id !== req.user.id) return res.status(403).json({ error: 'owner only' })
  db.prepare('UPDATE calendars SET deleted_at = ?, updated_at = ? WHERE id = ?').run(now(), now(), req.calendarId)
  res.json({ ok: true })
})

app.post('/api/calendars/:calendarId/rotate-code', auth, requireMember, (req, res) => {
  const calendar = db.prepare('SELECT * FROM calendars WHERE id = ?').get(req.calendarId)
  if (calendar.owner_user_id !== req.user.id) return res.status(403).json({ error: 'owner only' })
  db.prepare('UPDATE calendars SET invite_code = ?, updated_at = ? WHERE id = ?')
    .run(inviteCode(), now(), req.calendarId)
  res.json(calendarPayload(req.calendarId))
})

// ---------------------------------------------------------------- sync

app.get('/api/calendars/:calendarId/sync', auth, requireMember, (req, res) => {
  const since = Number(req.query.since || 0)
  const cal = req.calendarId
  const me = req.user.id
  res.json({
    serverTime: now(),
    calendar: calendarPayload(cal),
    payments: db.prepare(
      `SELECT * FROM payments WHERE calendar_id = ? AND updated_at > ?
       AND (visibility = 'SHARED' OR created_by_user_id = ? OR created_by_user_id IS NULL
            OR owner_user_id = ?)`
    ).all(cal, since, me, me).map(rowOut),
    dayNotes: db.prepare('SELECT * FROM day_notes WHERE calendar_id = ? AND updated_at > ?')
      .all(cal, since).map(rowOut),
    attachments: db.prepare('SELECT * FROM attachments WHERE calendar_id = ? AND updated_at > ?')
      .all(cal, since).map(rowOut),
    shoppingLists: db.prepare(
      `SELECT * FROM shopping_lists WHERE calendar_id = ? AND updated_at > ?
       AND (visibility = 'SHARED' OR created_by_user_id = ? OR assigned_to_user_id = ?)`
    ).all(cal, since, me, me).map(rowOut),
    shoppingItems: db.prepare('SELECT * FROM shopping_items WHERE calendar_id = ? AND updated_at > ?')
      .all(cal, since).map(rowOut),
    notes: db.prepare(
      `SELECT * FROM notes WHERE calendar_id = ? AND updated_at > ?
       AND (visibility = 'SHARED' OR created_by_user_id = ? OR owner_user_id = ?)`
    ).all(cal, since, me, me).map(rowOut)
  })
})

const upsertPayment = db.prepare(`
INSERT INTO payments (${paymentColumns.join(',')})
VALUES (${paymentColumns.map((c) => '@' + camel(c)).join(',')})
ON CONFLICT(id) DO UPDATE SET
${paymentColumns.filter((c) => c !== 'id' && c !== 'calendar_id' && c !== 'created_at')
    .map((c) => `${c} = excluded.${c}`).join(',\n')}
WHERE excluded.updated_at >= payments.updated_at
`)

const upsertList = db.prepare(`
INSERT INTO shopping_lists (${listColumns.join(',')})
VALUES (${listColumns.map((c) => '@' + camel(c)).join(',')})
ON CONFLICT(id) DO UPDATE SET
${listColumns.filter((c) => c !== 'id' && c !== 'calendar_id' && c !== 'created_at')
    .map((c) => `${c} = excluded.${c}`).join(', ')}
WHERE excluded.updated_at >= shopping_lists.updated_at
`)

const upsertItem = db.prepare(`
INSERT INTO shopping_items (${itemColumns.join(',')})
VALUES (${itemColumns.map((c) => '@' + camel(c)).join(',')})
ON CONFLICT(id) DO UPDATE SET
${itemColumns.filter((c) => c !== 'id' && c !== 'calendar_id' && c !== 'created_at')
    .map((c) => `${c} = excluded.${c}`).join(', ')}
WHERE excluded.updated_at >= shopping_items.updated_at
`)

const upsertNote = db.prepare(`
INSERT INTO notes (${noteColumns.join(',')})
VALUES (${noteColumns.map((c) => '@' + camel(c)).join(',')})
ON CONFLICT(id) DO UPDATE SET
${noteColumns.filter((c) => c !== 'id' && c !== 'calendar_id' && c !== 'created_at')
    .map((c) => `${c} = excluded.${c}`).join(', ')}
WHERE excluded.updated_at >= notes.updated_at
`)

app.post('/api/calendars/:calendarId/sync', auth, requireMember, (req, res) => {
  const cal = req.calendarId
  const stamp = now()
  const payments = Array.isArray(req.body?.payments) ? req.body.payments : []
  const dayNotes = Array.isArray(req.body?.dayNotes) ? req.body.dayNotes : []
  const lists = Array.isArray(req.body?.shoppingLists) ? req.body.shoppingLists : []
  const items = Array.isArray(req.body?.shoppingItems) ? req.body.shoppingItems : []
  const notes = Array.isArray(req.body?.notes) ? req.body.notes : []

  const apply = () => {
    for (const p of payments) {
      if (!p?.id) continue
      upsertPayment.run({
        id: String(p.id),
        calendarId: cal,
        seriesId: String(p.seriesId || p.id),
        ownerUserId: p.ownerUserId ?? null,
        title: String(p.title || ''),
        amountCents: Number(p.amountCents || 0),
        currency: String(p.currency || 'EUR'),
        colorIndex: Number(p.colorIndex || 0),
        category: String(p.category || ''),
        groupKey: String(p.groupKey || ''),
        dueDate: Number(p.dueDate || 0),
        dueTimeMinutes: Number(p.dueTimeMinutes ?? 540),
        recurrence: String(p.recurrence || 'NONE'),
        recurrenceEndDate: p.recurrenceEndDate ?? null,
        notes: String(p.notes || ''),
        status: String(p.status || 'PENDING'),
        paidAt: p.paidAt ?? null,
        paidAmountCents: p.paidAmountCents ?? null,
        paidByUserId: p.paidByUserId ?? null,
        remindDaysBefore: Number(p.remindDaysBefore || 0),
        nagMinutes: Number(p.nagMinutes ?? 60),
        alarmEnabled: p.alarmEnabled ? 1 : 0,
        requireReceipt: p.requireReceipt ? 1 : 0,
        installmentIndex: Number(p.installmentIndex || 0),
        installmentCount: Number(p.installmentCount || 0),
        createdByUserId: p.createdByUserId ?? req.user.id,
        visibility: p.visibility === 'PRIVATE' ? 'PRIVATE' : 'SHARED',
        shoppingListId: p.shoppingListId ?? null,
        kind: ['APPOINTMENT', 'INCOME', 'REMINDER'].includes(p.kind) ? p.kind : 'BILL',
        location: String(p.location || ''),
        latitude: Number.isFinite(Number(p.latitude)) && p.latitude != null ? Number(p.latitude) : null,
        longitude: Number.isFinite(Number(p.longitude)) && p.longitude != null ? Number(p.longitude) : null,
        durationMinutes: Number(p.durationMinutes || 0),
        createdAt: Number(p.createdAt || stamp),
        updatedAt: Number(p.updatedAt || stamp),
        deletedAt: p.deletedAt ?? null
      })
    }
    for (const n of dayNotes) {
      if (n?.epochDay == null) continue
      const existing = db.prepare('SELECT * FROM day_notes WHERE calendar_id = ? AND epoch_day = ?')
        .get(cal, Number(n.epochDay))
      const updatedAt = Number(n.updatedAt || stamp)
      if (!existing) {
        db.prepare(
          'INSERT INTO day_notes (id, calendar_id, epoch_day, text, updated_at, deleted_at) VALUES (?,?,?,?,?,?)'
        ).run(n.id || uuid(), cal, Number(n.epochDay), String(n.text || ''), updatedAt, n.deletedAt ?? null)
      } else if (updatedAt >= existing.updated_at) {
        db.prepare('UPDATE day_notes SET text = ?, updated_at = ?, deleted_at = ? WHERE id = ?')
          .run(String(n.text || ''), updatedAt, n.deletedAt ?? null, existing.id)
      }
    }
    for (const l of lists) {
      if (!l?.id) continue
      upsertList.run({
        id: String(l.id),
        calendarId: cal,
        title: String(l.title || 'Shopping list'),
        notes: String(l.notes || ''),
        colorIndex: Number(l.colorIndex || 0),
        dueDate: l.dueDate ?? null,
        dueTimeMinutes: Number(l.dueTimeMinutes ?? 1080),
        assignedToUserId: l.assignedToUserId ?? null,
        createdByUserId: l.createdByUserId ?? req.user.id,
        budgetCents: l.budgetCents ?? null,
        actualCents: l.actualCents ?? null,
        status: String(l.status || 'OPEN'),
        doneAt: l.doneAt ?? null,
        doneByUserId: l.doneByUserId ?? null,
        paymentId: l.paymentId ?? null,
        visibility: l.visibility === 'PRIVATE' ? 'PRIVATE' : 'SHARED',
        createdAt: Number(l.createdAt || stamp),
        updatedAt: Number(l.updatedAt || stamp),
        deletedAt: l.deletedAt ?? null
      })
    }
    for (const n of notes) {
      if (!n?.id) continue
      upsertNote.run({
        id: String(n.id),
        calendarId: cal,
        title: String(n.title || ''),
        body: String(n.body || ''),
        category: String(n.category || ''),
        colorIndex: Number(n.colorIndex || 0),
        pinned: n.pinned ? 1 : 0,
        ownerUserId: n.ownerUserId ?? null,
        createdByUserId: n.createdByUserId ?? req.user.id,
        visibility: n.visibility === 'PRIVATE' ? 'PRIVATE' : 'SHARED',
        createdAt: Number(n.createdAt || stamp),
        updatedAt: Number(n.updatedAt || stamp),
        deletedAt: n.deletedAt ?? null
      })
    }
    for (const it of items) {
      if (!it?.id || !it?.listId) continue
      upsertItem.run({
        id: String(it.id),
        listId: String(it.listId),
        calendarId: cal,
        text: String(it.text || ''),
        quantity: String(it.quantity || ''),
        // what was scanned, so a name given by hand teaches the household for next time
        barcode: String(it.barcode || ''),
        checked: it.checked ? 1 : 0,
        priceCents: it.priceCents ?? null,
        sortIndex: Number(it.sortIndex || 0),
        createdAt: Number(it.createdAt || stamp),
        updatedAt: Number(it.updatedAt || stamp),
        deletedAt: it.deletedAt ?? null
      })
    }
  }

  try {
    db.exec('BEGIN')
    apply()
    db.exec('COMMIT')
  } catch (e) {
    try { db.exec('ROLLBACK') } catch { /* nothing to roll back */ }
    return res.status(400).json({ error: String(e.message || e) })
  }
  res.json({
    serverTime: now(),
    accepted: payments.length + dayNotes.length + lists.length + items.length + notes.length
  })
})

// ---------------------------------------------------------------- attachments

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, FILES_DIR),
  filename: (_req, file, cb) => {
    const ext = path.extname(file.originalname || '') || ''
    cb(null, `${uuid()}${ext}`)
  }
})
const upload = multer({ storage, limits: { fileSize: MAX_UPLOAD } })

app.post('/api/calendars/:calendarId/attachments', auth, requireMember, upload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'file missing' })
  const { paymentId, epochDay, isReceipt, id, itemId, noteId } = req.body || {}
  const ownerType = noteId ? 'NOTE' : itemId ? 'ITEM' : paymentId ? 'PAYMENT' : 'DAY'
  const stamp = now()
  const attachmentId = id || uuid()
  db.prepare(
    `INSERT INTO attachments
      (id, calendar_id, owner_type, payment_id, epoch_day, item_id, note_id, file_name, mime,
       size, storage_name, is_receipt, uploaded_by, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
  ).run(
    attachmentId, req.calendarId, ownerType, paymentId || null,
    epochDay != null && epochDay !== '' ? Number(epochDay) : null,
    itemId || null, noteId || null,
    req.file.originalname || 'file', req.file.mimetype || 'application/octet-stream',
    req.file.size || 0, req.file.filename,
    String(isReceipt) === 'true' || String(isReceipt) === '1' ? 1 : 0,
    req.user.id, stamp, stamp
  )
  res.json(rowOut(db.prepare('SELECT * FROM attachments WHERE id = ?').get(attachmentId)))
})

/**
 * A photo of a till receipt becomes a shopping list. The household's Ollama reads it, the
 * picture stays on that day as a receipt, and the client builds the list from the answer.
 */
app.post('/api/calendars/:calendarId/receipt', auth, requireMember, upload.single('file'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'file missing' })
  const stored = path.join(FILES_DIR, req.file.filename)
  if (!config.ollamaUrl) {
    fs.rmSync(stored, { force: true })
    return res.status(501).json({ error: 'receipt reading is not set up on this server' })
  }
  if (!String(req.file.mimetype || '').startsWith('image/')) {
    fs.rmSync(stored, { force: true })
    return res.status(400).json({ error: 'send a picture of the receipt' })
  }
  // STOP on the phone aborts the model call and keeps nothing. The tunnel in front of the
  // server does not pass a client hang-up through, so the client also says it out loud
  // through /receipt-jobs/:id/stop; the job id is its own.
  const gone = new AbortController()
  const jobId = String(req.body?.jobId || uuid())
  receiptJobs.set(jobId, { owner: req.user.id, abort: gone })
  res.on('close', () => { if (!res.writableFinished) gone.abort() })
  try {
    const receipt = await readReceipt(fs.readFileSync(stored), gone.signal)
    if (gone.signal.aborted) throw new Error('stopped')
    const fromDate = receipt.date
      ? Math.floor(Date.UTC(...receipt.date.split('-').map((n, i) => Number(n) - (i === 1 ? 1 : 0))) / 86400000)
      : null
    const today = Number(req.body?.today)
    const epochDay = fromDate ?? (Number.isFinite(today) ? today : Math.floor(Date.now() / 86400000))

    const stamp = now()
    const id = uuid()
    db.prepare(
      `INSERT INTO attachments
        (id, calendar_id, owner_type, payment_id, epoch_day, item_id, note_id, file_name, mime,
         size, storage_name, is_receipt, uploaded_by, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
    ).run(
      id, req.calendarId, 'DAY', null, epochDay, null, null,
      req.file.originalname || 'receipt.jpg', req.file.mimetype, req.file.size || 0, req.file.filename,
      1, req.user.id, stamp, stamp
    )
    res.json({
      ...receipt,
      epochDay,
      attachment: rowOut(db.prepare('SELECT * FROM attachments WHERE id = ?').get(id))
    })
  } catch (e) {
    fs.rmSync(stored, { force: true })
    console.log(`[receipt failed] ${e.message}`)
    res.status(gone.signal.aborted ? 499 : 502).json({ error: e.message })
  } finally {
    receiptJobs.delete(jobId)
  }
})

/**
 * A scanned barcode becomes a named item with a small picture: Open Food Facts knows most
 * of what Italian supermarkets sell. When an itemId comes along, the picture is stored as
 * that item's photo, exactly as if someone had photographed it.
 */
app.post('/api/calendars/:calendarId/products/lookup', auth, requireMember, async (req, res) => {
  const { barcode, itemId } = req.body || {}
  let product
  try {
    product = await lookupProduct(barcode)
  } catch (e) {
    return res.status(502).json({ error: e.message })
  }
  if (!product) return res.status(404).json({ error: 'not in the product database' })

  let attachment = null
  if (itemId) {
    const image = await productImage(product).catch(() => null)
    if (image) {
      const stamp = now()
      const id = uuid()
      const storageName = `${id}.jpg`
      fs.writeFileSync(path.join(FILES_DIR, storageName), image.buffer)
      db.prepare(
        `INSERT INTO attachments
          (id, calendar_id, owner_type, payment_id, epoch_day, item_id, note_id, file_name, mime,
           size, storage_name, is_receipt, uploaded_by, created_at, updated_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
      ).run(id, req.calendarId, 'ITEM', null, null, String(itemId), null, image.name, image.mime,
        image.buffer.length, storageName, 0, req.user.id, stamp, stamp)
      attachment = rowOut(db.prepare('SELECT * FROM attachments WHERE id = ?').get(id))
    }
  }
  res.json({ product, attachment })
})

/**
 * The household's picture cache: an item added by name gets the photo of the last item
 * with that name, whether it came from the food database or from somebody's camera.
 * The file is copied, so deleting one item's photo never blanks another's.
 */
app.post('/api/calendars/:calendarId/items/:itemId/photo-from/:sourceItemId', auth, requireMember, (req, res) => {
  const source = db.prepare(
    `SELECT * FROM attachments WHERE calendar_id = ? AND item_id = ? AND deleted_at IS NULL
     ORDER BY created_at DESC LIMIT 1`
  ).get(req.calendarId, req.params.sourceItemId)
  if (!source) return res.status(404).json({ error: 'that item has no photo' })
  const from = path.join(FILES_DIR, source.storage_name)
  if (!fs.existsSync(from)) return res.status(404).json({ error: 'the photo file is gone' })

  const id = uuid()
  const storageName = `${id}${path.extname(source.storage_name) || '.jpg'}`
  fs.copyFileSync(from, path.join(FILES_DIR, storageName))
  const stamp = now()
  db.prepare(
    `INSERT INTO attachments
      (id, calendar_id, owner_type, payment_id, epoch_day, item_id, note_id, file_name, mime,
       size, storage_name, is_receipt, uploaded_by, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
  ).run(id, req.calendarId, 'ITEM', null, null, req.params.itemId, null, source.file_name, source.mime,
    source.size, storageName, 0, req.user.id, stamp, stamp)
  res.json(rowOut(db.prepare('SELECT * FROM attachments WHERE id = ?').get(id)))
})

/** Address search on OpenStreetMap, for appointments that need a place. */
app.get('/api/places', auth, async (req, res) => {
  try {
    res.json({ places: await searchPlaces(req.query.q) })
  } catch (e) {
    res.status(502).json({ error: e.message })
  }
})

/** A shared Google Calendar link: what event is behind it? */
app.post('/api/calendars/:calendarId/resolve-event', auth, requireMember, async (req, res) => {
  try {
    const event = await resolveEventLink(String(req.body?.url || ''))
    if (!event) return res.status(404).json({ error: 'no event details behind that link' })
    res.json(event)
  } catch (e) {
    res.status(502).json({ error: e.message })
  }
})

// ---------------------------------------------------------------- the bank, asked directly

/**
 * Enable Banking reads the account under PSD2: the owner says yes in their own bank's app,
 * and from then on the movements arrive complete, including the card payment made while the
 * phone was in a bag. The notifications stay on — they are instant and this is not — and the
 * phone drops whatever it has already seen, by the fingerprint each movement carries.
 */
app.get('/api/banking/status', auth, (req, res) => {
  const rows = db.prepare(
    'SELECT id, aspsp, account_name, iban_tail, valid_until, last_read_at FROM bank_connections WHERE user_id = ?'
  ).all(req.user.id)
  res.json({
    configured: banking.configured(),
    redirectReady: Boolean(config.publicUrl),
    connections: rows.map((r) => ({
      id: r.id,
      bank: r.aspsp,
      account: r.account_name,
      ibanTail: r.iban_tail,
      validUntil: r.valid_until,
      lastReadAt: r.last_read_at
    }))
  })
})

app.get('/api/banking/banks', auth, async (req, res) => {
  if (!banking.configured()) return res.status(503).json({ error: 'banking not configured' })
  try {
    res.json({ banks: await banking.banks(String(req.query.country || 'IT').toUpperCase()) })
  } catch (e) {
    res.status(502).json({ error: e.message })
  }
})

app.post('/api/calendars/:calendarId/banking/connect', auth, requireMember, async (req, res) => {
  if (!banking.configured()) return res.status(503).json({ error: 'banking not configured' })
  if (!config.publicUrl) return res.status(503).json({ error: 'no publicUrl configured' })

  const bank = String(req.body?.bank || '').trim()
  const country = String(req.body?.country || 'IT').toUpperCase()
  if (!bank) return res.status(400).json({ error: 'bank is required' })

  const state = uuid()
  try {
    const started = await banking.startAuth({
      bank,
      country,
      redirectUrl: `${config.publicUrl}/api/banking/callback`,
      state
    })
    db.prepare(
      'INSERT INTO bank_pending (state, calendar_id, user_id, aspsp, country, created_at) VALUES (?,?,?,?,?,?)'
    ).run(state, req.params.calendarId, req.user.id, bank, country, Date.now())
    res.json({ url: started.url })
  } catch (e) {
    res.status(502).json({ error: e.message })
  }
})

/** Where the bank sends the owner back. A browser lands here, so it answers in a page. */
app.get('/api/banking/callback', async (req, res) => {
  const code = String(req.query.code || '')
  const state = String(req.query.state || '')
  const page = (title, body) => res.status(code && state ? 200 : 400).type('html').send(
    `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<title>${title}</title><style>body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;` +
    `display:grid;place-items:center;background:#fdf6e9;color:#1a1a1a;padding:24px;text-align:center}` +
    `div{max-width:28rem}h1{font-size:1.4rem;margin:0 0 .5rem}p{margin:0;line-height:1.5}</style>` +
    `<div><h1>${title}</h1><p>${body}</p></div>`
  )

  const waiting = state
    ? db.prepare('SELECT * FROM bank_pending WHERE state = ?').get(state)
    : null
  if (!code || !waiting) return page('Something went missing', 'Start the connection again from the app.')

  try {
    const done = await banking.completeAuth(code)
    db.prepare('DELETE FROM bank_pending WHERE state = ?').run(state)
    const now = Date.now()
    for (const account of done.accounts) {
      db.prepare(
        `INSERT INTO bank_connections
         (id, calendar_id, user_id, aspsp, country, session_id, account_uid, account_name,
          iban_tail, currency, valid_until, last_read_at, created_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`
      ).run(
        uuid(), waiting.calendar_id, waiting.user_id,
        done.aspsp || waiting.aspsp, done.country || waiting.country,
        done.sessionId, account.uid, account.name,
        account.iban ? account.iban.slice(-4) : '', account.currency,
        done.validUntil || 0, 0, now
      )
    }
    page('Your bank is connected', 'You can close this page and go back to Pay &amp; Plan.')
  } catch (e) {
    page('The bank said no', e.message)
  }
})

/**
 * What the account has done since a date. The phone asks, decides what is new to it and
 * feeds it through the same machinery as the notifications, so a shop that was sorted out
 * once is still sorted out here.
 */
app.get('/api/calendars/:calendarId/banking/movements', auth, requireMember, async (req, res) => {
  if (!banking.configured()) return res.status(503).json({ error: 'banking not configured' })

  const days = Math.min(Math.max(Number(req.query.days || 14), 1), 365)
  const since = Date.now() - days * 24 * 60 * 60 * 1000
  const rows = db.prepare(
    'SELECT * FROM bank_connections WHERE calendar_id = ? AND user_id = ?'
  ).all(req.params.calendarId, req.user.id)
  if (!rows.length) return res.json({ movements: [], accounts: 0 })

  const movements = []
  const trouble = []
  for (const row of rows) {
    try {
      const raw = await banking.transactions(row.account_uid, since)
      for (const t of raw) {
        // a payment that is still pending can change its figure, or never happen at all
        if (t.status && t.status !== 'BOOK') continue
        const m = banking.plainMovement(t, row.account_uid)
        if (m && m.happenedAt >= since) movements.push({ ...m, bank: row.aspsp, account: row.account_name })
      }
      db.prepare('UPDATE bank_connections SET last_read_at = ? WHERE id = ?').run(Date.now(), row.id)
    } catch (e) {
      trouble.push(`${row.aspsp}: ${e.message}`)
    }
  }
  movements.sort((a, b) => b.happenedAt - a.happenedAt)
  res.json({ movements, accounts: rows.length, trouble })
})

app.delete('/api/calendars/:calendarId/banking/connections/:id', auth, requireMember, async (req, res) => {
  const row = db.prepare('SELECT * FROM bank_connections WHERE id = ? AND user_id = ?')
    .get(req.params.id, req.user.id)
  if (!row) return res.status(404).json({ error: 'not found' })

  // the consent is the owner's: hand it back to the bank as well, not only forget it here
  const others = db.prepare('SELECT count(*) AS c FROM bank_connections WHERE session_id = ? AND id <> ?')
    .get(row.session_id, row.id).c
  if (!others && row.session_id) {
    try { await banking.dropSession(row.session_id) } catch (e) { console.warn('[banking]', e.message) }
  }
  db.prepare('DELETE FROM bank_connections WHERE id = ?').run(row.id)
  res.json({ ok: true })
})

const receiptJobs = new Map()

app.post('/api/receipt-jobs/:jobId/stop', auth, (req, res) => {
  const job = receiptJobs.get(req.params.jobId)
  if (job && job.owner === req.user.id) job.abort.abort()
  res.json({ stopped: !!job })
})

/**
 * Reads a public page for the client: the browser cannot, because of CORS, and a phone
 * should not have to. Images found on the page can be saved straight onto a note.
 */
app.post('/api/calendars/:calendarId/unfurl', auth, requireMember, async (req, res) => {
  const { url, noteId, withImages } = req.body || {}
  if (!url) return res.status(400).json({ error: 'url required' })
  try {
    const page = await unfurl(String(url))
    const saved = []

    if (noteId && withImages !== false) {
      for (const imageUrl of page.images.slice(0, 8)) {
        const image = await fetchImage(imageUrl)
        if (!image) continue
        const stamp = now()
        const id = uuid()
        const ext = (image.name.split('.').pop() || 'jpg').slice(0, 5)
        const storageName = `${uuid()}.${ext}`
        fs.writeFileSync(path.join(FILES_DIR, storageName), image.buffer)
        db.prepare(
          `INSERT INTO attachments
            (id, calendar_id, owner_type, payment_id, epoch_day, item_id, note_id, file_name, mime,
             size, storage_name, is_receipt, uploaded_by, created_at, updated_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
        ).run(id, req.calendarId, 'NOTE', null, null, null, String(noteId),
          image.name, image.mime, image.buffer.length, storageName, 0, req.user.id, stamp, stamp)
        saved.push(rowOut(db.prepare('SELECT * FROM attachments WHERE id = ?').get(id)))
      }
    }

    res.json({ ...page, savedImages: saved })
  } catch (e) {
    res.status(400).json({ error: String(e.message || e) })
  }
})

app.get('/api/attachments/:id/raw', auth, (req, res) => {
  const a = db.prepare('SELECT * FROM attachments WHERE id = ?').get(req.params.id)
  if (!a || a.deleted_at) return res.status(404).json({ error: 'not found' })
  if (!isMember(a.calendar_id, req.user.id)) return res.status(403).json({ error: 'not a member' })
  const full = path.join(FILES_DIR, a.storage_name)
  if (!fs.existsSync(full)) return res.status(404).json({ error: 'file gone' })
  res.setHeader('Content-Type', a.mime)
  res.setHeader('Content-Disposition', `inline; filename="${encodeURIComponent(a.file_name)}"`)
  fs.createReadStream(full).pipe(res)
})

app.delete('/api/attachments/:id', auth, (req, res) => {
  const a = db.prepare('SELECT * FROM attachments WHERE id = ?').get(req.params.id)
  if (!a) return res.status(404).json({ error: 'not found' })
  if (!isMember(a.calendar_id, req.user.id)) return res.status(403).json({ error: 'not a member' })
  db.prepare('UPDATE attachments SET deleted_at = ?, updated_at = ? WHERE id = ?').run(now(), now(), a.id)
  fs.rm(path.join(FILES_DIR, a.storage_name), { force: true }, () => {})
  res.json({ ok: true })
})

// ---------------------------------------------------------------- nightly backup to Google Drive

mountBackup(app)

// ---------------------------------------------------------------- web app

const webappDir = path.resolve(__dirname, '..', '..', 'webapp')
if (fs.existsSync(webappDir)) {
  app.use(express.static(webappDir, {
    extensions: ['html'],
    setHeaders: (res, filePath) => {
      // The app shell must never be cached by the CDN or the browser, otherwise a deploy
      // keeps serving yesterday's JavaScript. Fonts and icons are immutable, cache those.
      if (/\.(html|js|webmanifest|json)$/i.test(filePath)) {
        res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate')
      } else if (/\.(ttf|woff2?|png|svg|jpg|jpeg)$/i.test(filePath)) {
        res.setHeader('Cache-Control', 'public, max-age=604800')
      }
    }
  }))
  app.get(/^\/(?!api\/).*/, (_req, res) => {
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate')
    res.sendFile(path.join(webappDir, 'index.html'))
  })
}

// ---------------------------------------------------------------- push

/** The public half of the key a browser needs to subscribe. */
app.get('/api/push/key', (_req, res) => res.json({ key: push.publicKey }))

app.post('/api/push/subscribe', auth, (req, res) => {
  try {
    push.saveSubscription(req.user.id, req.body?.subscription, req.headers['user-agent'])
    console.log(`[push] ${req.user.email} now has ${push.deviceCount(req.user.id)} device(s)`)
    res.json({ devices: push.deviceCount(req.user.id) })
  } catch (e) {
    res.status(400).json({ error: e.message })
  }
})

app.post('/api/push/unsubscribe', auth, (req, res) => {
  push.dropSubscription(req.body?.endpoint)
  res.json({ devices: push.deviceCount(req.user.id) })
})

/** A message on the spot, so the person can see it working before trusting it. */
app.post('/api/push/test', auth, async (req, res) => {
  const sent = await push.sendToUser(req.user.id, {
    title: '🔔 Pay & Plan',
    body: 'Alerts are on. This is what a reminder will look like.',
    tag: 'test'
  })
  res.json({ sent })
})

app.get('/api/health', (_req, res) => res.json({ ok: true, time: now() }))

app.use((err, _req, res, _next) => {
  console.error(err)
  res.status(500).json({ error: String(err.message || err) })
})

push.startRounds()
startBackupRounds()
// the Android build, on the tailnet and nowhere else: its own listener, its own bind
startApkHandout()

app.listen(PORT, () => {
  console.log(`Pay & Plan server listening on http://0.0.0.0:${PORT}`)
})
