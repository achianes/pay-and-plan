/* Pay & Plan - installable web app.
   Offline first: everything lives in localStorage and is pushed to the sync server
   whenever the network is there. */

const API = location.origin
const LS = {
  token: 'pp.token',
  user: 'pp.user',
  calendars: 'pp.calendars',
  calendarId: 'pp.calendarId',
  data: (id) => `pp.data.${id}`,
  since: (id) => `pp.since.${id}`,
  dirty: (id) => `pp.dirty.${id}`
}

/**
 * Bump this whenever the shape of the cached data changes. A mismatch throws away the
 * sync cursors (never the data), so the next sync re-downloads the calendar in full
 * instead of asking for "changes since yesterday" against a stale cache.
 */
const DATA_VERSION = 2

function guardDataVersion() {
  const stored = Number(localStorage.getItem('pp.dataVersion') || 0)
  if (stored === DATA_VERSION) return
  Object.keys(localStorage)
    .filter((k) => k.startsWith('pp.since.'))
    .forEach((k) => localStorage.removeItem(k))
  localStorage.setItem('pp.dataVersion', String(DATA_VERSION))
}
guardDataVersion()

const state = {
  token: localStorage.getItem(LS.token) || '',
  user: safeParse(localStorage.getItem(LS.user)) || null,
  calendars: safeParse(localStorage.getItem(LS.calendars)) || [],
  calendarId: localStorage.getItem(LS.calendarId) || '',
  data: { payments: [], dayNotes: [], attachments: [], shoppingLists: [], shoppingItems: [], notes: [] },
  dirty: new Set(),
  view: 'calendar',
  month: startOfMonth(new Date()),
  selected: today(),
  syncing: false,
  online: navigator.onLine,
  billsOwner: '',            // '' = everybody
  billsFilter: 'open',       // open | incoming | late | paid | all
  openMonths: new Set(),     // which month groups are unfolded
  openSections: new Set(),   // which folded blocks are unfolded
  noteCategory: '',          // notes filter, '' = all
  noteSearch: ''
}

// ---------------------------------------------------------------- tiny utils

function safeParse(s) { try { return JSON.parse(s) } catch { return null } }
function uuid() { return crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random() }
function today() { const d = new Date(); d.setHours(0, 0, 0, 0); return d }
function startOfMonth(d) { return new Date(d.getFullYear(), d.getMonth(), 1) }
function epochDay(d) { return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000) }
function fromEpochDay(n) { const d = new Date(n * 86400000); return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) }
function sameDay(a, b) { return epochDay(a) === epochDay(b) }
/** yyyy-mm-dd in local time: toISOString would shift the day in any positive timezone. */
function inputDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const MONTHS = ['JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'MAY', 'JUNE', 'JULY', 'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER']
const WEEKDAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const COLORS = ['#FF6B6B', '#FFD93D', '#6BCB77', '#4D96FF', '#B983FF', '#FF9F45', '#4ECDC4', '#FF9CEE']
const colorOf = (i) => COLORS[((i | 0) % COLORS.length + COLORS.length) % COLORS.length]

const RECURRENCES = [
  ['NONE', 'One time'], ['DAILY', 'Every day'], ['WEEKLY', 'Every week'],
  ['BIWEEKLY', 'Every 2 weeks'], ['MONTHLY', 'Every month'], ['QUARTERLY', 'Every 3 months'],
  ['SEMIANNUAL', 'Every 6 months'], ['YEARLY', 'Every year']
]
const recurrenceLabel = (r) => (RECURRENCES.find((x) => x[0] === r) || RECURRENCES[0])[1]

function money(cents, currency) {
  const cur = currency || calendarCurrency()
  const symbol = { EUR: '€', USD: '$', GBP: '£' }[cur] || cur + ' '
  const sign = cents < 0 ? '-' : ''
  const abs = Math.abs(cents | 0)
  return `${sign}${symbol}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}
function parseAmount(text) {
  const clean = String(text || '').trim().replace(',', '.').replace(/\s/g, '')
  if (!clean) return null
  const v = Number(clean)
  return Number.isFinite(v) ? Math.round(v * 100) : null
}
const centsToInput = (c) => `${Math.floor((c | 0) / 100)}.${String(Math.abs(c | 0) % 100).padStart(2, '0')}`

function dayLabel(d) {
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
}
function fullDayLabel(d) {
  return d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
}
function relativeLabel(d) {
  const diff = epochDay(d) - epochDay(today())
  if (diff === 0) return 'Today'
  if (diff === 1) return 'Tomorrow'
  if (diff === -1) return 'Yesterday'
  if (diff < 0) return `${-diff} days late`
  if (diff < 7) return `In ${diff} days`
  return dayLabel(d)
}
function toast(text) {
  const el = document.createElement('div')
  el.className = 'toast'
  el.textContent = text
  document.body.appendChild(el)
  setTimeout(() => el.remove(), 2600)
}
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

// ---------------------------------------------------------------- recurrence

function dateAt(anchorEpochDay, recurrence, index) {
  const a = fromEpochDay(anchorEpochDay)
  const d = new Date(a)
  switch (recurrence) {
    case 'DAILY': d.setDate(a.getDate() + index); break
    case 'WEEKLY': d.setDate(a.getDate() + 7 * index); break
    case 'BIWEEKLY': d.setDate(a.getDate() + 14 * index); break
    case 'MONTHLY': return addMonthsClamped(a, index)
    case 'QUARTERLY': return addMonthsClamped(a, 3 * index)
    case 'SEMIANNUAL': return addMonthsClamped(a, 6 * index)
    case 'YEARLY': return addMonthsClamped(a, 12 * index)
    default: break
  }
  return d
}
function addMonthsClamped(anchor, months) {
  const target = new Date(anchor.getFullYear(), anchor.getMonth() + months, 1)
  const lastDay = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
  target.setDate(Math.min(anchor.getDate(), lastDay))
  return target
}
const HORIZON = 24

// ---------------------------------------------------------------- storage

function loadData() {
  const stored = safeParse(localStorage.getItem(LS.data(state.calendarId)))
  state.data = Object.assign(
    { payments: [], dayNotes: [], attachments: [], shoppingLists: [], shoppingItems: [], notes: [] },
    stored || {}
  )
  state.dirty = new Set(safeParse(localStorage.getItem(LS.dirty(state.calendarId))) || [])
}
function persist() {
  if (!state.calendarId) return
  localStorage.setItem(LS.data(state.calendarId), JSON.stringify(state.data))
  localStorage.setItem(LS.dirty(state.calendarId), JSON.stringify([...state.dirty]))
}
function calendar() { return state.calendars.find((c) => c.id === state.calendarId) || null }
function calendarCurrency() { return calendar()?.currency || 'EUR' }
function members() { return calendar()?.members || [] }
function memberById(id) { return members().find((m) => m.id === id) || null }
function isOwnerOfCalendar() { return calendar()?.ownerUserId === state.user?.id }

function touch(collection, row) {
  row.updatedAt = Date.now()
  const list = state.data[collection]
  const i = list.findIndex((x) => x.id === row.id)
  if (i >= 0) list[i] = row; else list.push(row)
  state.dirty.add(row.id)
  persist()
  scheduleSync()
}

// ---------------------------------------------------------------- api

async function api(path, options = {}) {
  const headers = Object.assign({}, options.headers || {})
  if (state.token) headers.Authorization = `Bearer ${state.token}`
  if (options.body && !(options.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const res = await fetch(API + path, Object.assign({}, options, { headers }))
  const text = await res.text()
  const body = text ? safeParse(text) : null
  if (!res.ok) throw new Error(body?.error || `HTTP ${res.status}`)
  return body
}

let syncTimer = null
function scheduleSync() {
  clearTimeout(syncTimer)
  syncTimer = setTimeout(() => sync().catch(() => {}), 900)
}

async function sync() {
  if (!state.token || !state.calendarId || state.syncing) return
  state.syncing = true
  paintSyncDot()
  try {
    const dirtyIds = new Set(state.dirty)
    if (dirtyIds.size) {
      const pick = (coll) => state.data[coll].filter((r) => dirtyIds.has(r.id))
      await api(`/api/calendars/${state.calendarId}/sync`, {
        method: 'POST',
        body: JSON.stringify({
          payments: pick('payments'),
          dayNotes: pick('dayNotes'),
          shoppingLists: pick('shoppingLists'),
          shoppingItems: pick('shoppingItems'),
          notes: pick('notes')
        })
      })
      dirtyIds.forEach((id) => state.dirty.delete(id))
    }

    const since = Number(localStorage.getItem(LS.since(state.calendarId)) || 0)
    const fresh = await api(`/api/calendars/${state.calendarId}/sync?since=${since}`)
    for (const coll of ['payments', 'dayNotes', 'attachments', 'shoppingLists', 'shoppingItems', 'notes']) {
      for (const row of fresh[coll] || []) {
        const list = state.data[coll]
        const i = list.findIndex((x) => x.id === row.id)
        if (i < 0) list.push(row)
        else if (!state.dirty.has(row.id) || (row.updatedAt || 0) > (list[i].updatedAt || 0)) list[i] = row
      }
    }
    if (fresh.calendar) {
      const i = state.calendars.findIndex((c) => c.id === fresh.calendar.id)
      if (i >= 0) state.calendars[i] = fresh.calendar; else state.calendars.push(fresh.calendar)
      localStorage.setItem(LS.calendars, JSON.stringify(state.calendars))
    }
    localStorage.setItem(LS.since(state.calendarId), String(fresh.serverTime))
    persist()
    render()
  } finally {
    state.syncing = false
    paintSyncDot()
  }
}

function paintSyncDot() {
  const dot = document.getElementById('syncdot')
  if (!dot) return
  dot.className = 'syncdot' + (state.syncing ? ' busy' : state.online ? '' : ' off')
  dot.title = state.syncing ? 'Syncing' : state.online ? 'Synced' : 'Offline'
}

// ---------------------------------------------------------------- selectors

const alive = (rows) => rows.filter((r) => !r.deletedAt)
function payments() {
  const me = state.user?.id
  return alive(state.data.payments).filter(
    (p) => p.visibility !== 'PRIVATE' || p.createdByUserId === me || p.ownerUserId === me
  )
}
function paymentsOn(day) {
  const ed = epochDay(day)
  return payments().filter((p) => p.dueDate === ed).sort((a, b) => (a.dueTimeMinutes || 0) - (b.dueTimeMinutes || 0))
}
function attachmentsFor(paymentId) {
  return alive(state.data.attachments).filter((a) => a.paymentId === paymentId)
}
function dayAttachments(day) {
  const ed = epochDay(day)
  return alive(state.data.attachments).filter((a) => a.ownerType === 'DAY' && a.epochDay === ed)
}
function noteFor(day) {
  const ed = epochDay(day)
  return alive(state.data.dayNotes).find((n) => n.epochDay === ed) || null
}
function lists() {
  const me = state.user?.id
  return alive(state.data.shoppingLists).filter(
    (l) => l.visibility !== 'PRIVATE' || l.createdByUserId === me || l.assignedToUserId === me
  )
}
/** Everything anyone on this calendar has ever bought, most used first. */
function itemVocabulary() {
  const counts = new Map()
  for (const item of state.data.shoppingItems) {
    const text = (item.text || '').trim()
    if (!text) continue
    const key = text.toLowerCase()
    const seen = counts.get(key)
    if (seen) { seen.n += 1; seen.at = Math.max(seen.at, item.updatedAt || 0) }
    else counts.set(key, { text, n: 1, at: item.updatedAt || 0 })
  }
  return [...counts.values()].sort((a, b) => b.n - a.n || b.at - a.at).map((v) => v.text)
}

/** Optional photo of a product: the most recent picture attached to that item. */
function itemPhoto(itemId) {
  return alive(state.data.attachments)
    .filter((a) => a.itemId === itemId && (a.mime || '').startsWith('image/'))
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0] || null
}

function itemsOf(listId) {
  return alive(state.data.shoppingItems)
    .filter((i) => i.listId === listId)
    .sort((a, b) => (a.sortIndex || 0) - (b.sortIndex || 0))
}
const isOpen = (p) => p.status === 'PENDING'
const isPaid = (p) => p.status === 'PAID'
const isAppointment = (p) => p.kind === 'APPOINTMENT'
const isIncome = (p) => p.kind === 'INCOME'
const isReminder = (p) => p.kind === 'REMINDER'
const isBill = (p) => !isAppointment(p) && !isIncome(p) && !isReminder(p)
const timeLabel = (minutes) =>
  `${String(Math.floor((minutes || 0) / 60)).padStart(2, '0')}:${String((minutes || 0) % 60).padStart(2, '0')}`

// ---------------------------------------------------------------- mutations

function newPayment(fields) {
  const stamp = Date.now()
  return Object.assign({
    id: uuid(),
    seriesId: uuid(),
    ownerUserId: state.user?.id || null,
    createdByUserId: state.user?.id || null,
    visibility: 'SHARED',
    title: '',
    amountCents: 0,
    currency: calendarCurrency(),
    colorIndex: 0,
    category: '',
    dueDate: epochDay(today()),
    dueTimeMinutes: 540,
    recurrence: 'NONE',
    recurrenceEndDate: null,
    notes: '',
    status: 'PENDING',
    paidAt: null,
    paidAmountCents: null,
    paidByUserId: null,
    remindDaysBefore: 1,
    nagMinutes: 60,
    alarmEnabled: true,
    requireReceipt: true,
    installmentIndex: 0,
    installmentCount: 0,
    shoppingListId: null,
    kind: 'BILL',
    location: '',
    durationMinutes: 0,
    createdAt: stamp,
    updatedAt: stamp,
    deletedAt: null
  }, fields)
}

function createSeries(template, amounts) {
  const seriesId = uuid()
  const rows = []
  if (Array.isArray(amounts) && amounts.length) {
    amounts.forEach((cents, i) => {
      rows.push(Object.assign({}, template, {
        id: uuid(), seriesId,
        dueDate: epochDay(dateAt(template.dueDate, template.recurrence, i)),
        amountCents: cents,
        installmentIndex: i + 1,
        installmentCount: amounts.length
      }))
    })
  } else if (template.recurrence === 'NONE') {
    rows.push(Object.assign({}, template, { id: uuid(), seriesId }))
  } else {
    for (let i = 0; i < HORIZON; i++) {
      const d = dateAt(template.dueDate, template.recurrence, i)
      if (template.recurrenceEndDate && epochDay(d) > template.recurrenceEndDate) break
      rows.push(Object.assign({}, template, { id: uuid(), seriesId, dueDate: epochDay(d) }))
    }
  }
  const stamp = Date.now()
  rows.forEach((r) => { r.createdAt = stamp; r.updatedAt = stamp; state.data.payments.push(r); state.dirty.add(r.id) })
  persist()
  scheduleSync()
  return rows
}

/** Every live row of the same repeating series, in date order. */
function seriesOf(seriesId) {
  return alive(state.data.payments)
    .filter((p) => p.seriesId === seriesId)
    .sort((a, b) => a.dueDate - b.dueDate)
}

/** Closes a series at [lastDay]: later unpaid entries go away, the end date is recorded. */
function stopSeriesAt(payment) {
  const rows = seriesOf(payment.seriesId)
  rows.forEach((row) => {
    if (row.dueDate > payment.dueDate && isOpen(row)) { softDelete('payments', row); return }
    row.recurrenceEndDate = payment.dueDate
    touch('payments', row)
  })
}

/** Pushes a series forward to [endEpochDay], creating the entries that are missing. */
function extendSeriesTo(payment, endEpochDay) {
  const rows = seriesOf(payment.seriesId)
  if (!rows.length) return 0
  const last = rows[rows.length - 1]
  if (last.recurrence === 'NONE') return 0
  rows.forEach((row) => { row.recurrenceEndDate = endEpochDay; touch('payments', row) })

  const stamp = Date.now()
  let added = 0
  for (let i = 1; i <= 600; i++) {
    const day = epochDay(dateAt(last.dueDate, last.recurrence, i))
    if (day > endEpochDay) break
    const row = Object.assign({}, last, {
      id: uuid(), dueDate: day, status: 'PENDING',
      paidAt: null, paidAmountCents: null, paidByUserId: null,
      recurrenceEndDate: endEpochDay, createdAt: stamp, updatedAt: stamp, deletedAt: null
    })
    state.data.payments.push(row)
    state.dirty.add(row.id)
    added++
  }
  persist()
  scheduleSync()
  return added
}

function markPaid(payment) {
  const receipts = attachmentsFor(payment.id).filter((a) => a.isReceipt)
  if (payment.requireReceipt && receipts.length === 0) {
    toast('Attach the receipt first')
    return false
  }
  payment.status = 'PAID'
  payment.paidAt = Date.now()
  payment.paidAmountCents = payment.amountCents
  payment.paidByUserId = state.user?.id || null
  touch('payments', payment)
  topUpSeries(payment.seriesId)
  return true
}

function topUpSeries(seriesId) {
  const rows = alive(state.data.payments).filter((p) => p.seriesId === seriesId)
  if (!rows.length) return
  const last = rows.reduce((a, b) => (a.dueDate > b.dueDate ? a : b))
  if (last.recurrence === 'NONE' || last.installmentCount > 0) return
  const ahead = rows.filter((p) => p.dueDate >= epochDay(today())).length
  if (ahead >= HORIZON) return
  const stamp = Date.now()
  for (let i = 1; i <= HORIZON - ahead; i++) {
    const d = dateAt(last.dueDate, last.recurrence, i)
    if (last.recurrenceEndDate && epochDay(d) > last.recurrenceEndDate) break
    const row = Object.assign({}, last, {
      id: uuid(), dueDate: epochDay(d), status: 'PENDING',
      paidAt: null, paidAmountCents: null, paidByUserId: null,
      createdAt: stamp, updatedAt: stamp
    })
    state.data.payments.push(row)
    state.dirty.add(row.id)
  }
  persist()
  scheduleSync()
}

function softDelete(collection, row) {
  row.deletedAt = Date.now()
  touch(collection, row)
}

async function uploadFile(file, { paymentId = null, day = null, itemId = null, noteId = null, isReceipt = false }) {
  const form = new FormData()
  form.append('file', file)
  if (paymentId) form.append('paymentId', paymentId)
  if (itemId) form.append('itemId', itemId)
  if (noteId) form.append('noteId', noteId)
  if (day != null) form.append('epochDay', String(epochDay(day)))
  form.append('isReceipt', isReceipt ? 'true' : 'false')
  const saved = await api(`/api/calendars/${state.calendarId}/attachments`, { method: 'POST', body: form })
  state.data.attachments.push(saved)
  persist()
  render()
  return saved
}
const fileUrl = (a) => `${API}/api/attachments/${a.id}/raw?token=${encodeURIComponent(state.token)}`

// ---------------------------------------------------------------- rendering

const app = () => document.getElementById('app')
const tabbar = () => document.getElementById('tabbar')

function render() {
  if (!state.token) return renderAuth()
  if (!state.calendarId && state.calendars.length) selectCalendar(state.calendars[0].id, false)
  const views = {
    calendar: viewCalendar, bills: viewBills, lists: viewLists, notes: viewNotes, setup: viewSetup
  }
  app().innerHTML = (views[state.view] || viewCalendar)()
  tabbar().innerHTML = renderTabs()
  wire()
  paintSyncDot()
}

function renderTabs() {
  const tab = (id, ico, label) =>
    `<button data-tab="${id}" class="${state.view === id ? 'on' : ''}">
       <span class="ico">${ico}</span>${label}</button>`
  const fab = state.view === 'calendar' || state.view === 'bills'
    ? `<button class="fab" data-act="new-bill">+</button>`
    : state.view === 'lists' ? `<button class="fab" data-act="new-list">+</button>`
    : state.view === 'notes' ? `<button class="fab" data-act="new-note">+</button>` : ''
  return `${fab}<div class="tabbar"><div class="inner">
      ${tab('calendar', '📅', 'Calendar')}
      ${tab('bills', '🧾', 'Bills')}
      ${tab('lists', '🛒', 'Lists')}
      ${tab('notes', '📝', 'Notes')}
      ${tab('setup', '⚙️', 'Setup')}
    </div></div>`
}

function header(title) {
  const cal = calendar()
  const others = state.calendars.filter((c) => c.id !== state.calendarId)
  return `<div class="topbar">
      <h1>${esc(title)}</h1>
      <span id="syncdot" class="syncdot"></span>
      <button class="small" data-act="switch-cal">${esc(cal?.name || 'Calendar')}${others.length ? ' ▾' : ''}</button>
    </div>`
}

// ---------------------------------------------------------------- auth view

function renderAuth() {
  tabbar().innerHTML = ''
  app().innerHTML = `
    <div style="height:24px"></div>
    <h1 class="center">PAY &amp; PLAN</h1>
    <p class="center muted">Bills, receipts and shopping, shared with the people you live with.</p>
    <div class="card">
      <div id="auth-error" class="hidden card coral tight" style="box-shadow:none;margin-bottom:10px"></div>
      <label>Email</label>
      <input id="auth-email" type="email" autocomplete="email" inputmode="email"
             autocapitalize="off" autocorrect="off" spellcheck="false" />
      <label>Password</label>
      <div class="row">
        <input id="auth-password" class="grow" type="password" autocomplete="current-password"
               autocapitalize="off" autocorrect="off" spellcheck="false" style="margin:0" />
        <button class="small" data-act="peek" type="button">show</button>
      </div>
      <div id="name-wrap" class="hidden">
        <label>Your name</label>
        <input id="auth-name" type="text" autocomplete="name" />
      </div>
      <div class="row" style="margin-top:12px">
        <button class="mint grow" data-act="login">LOG IN</button>
        <button class="yellow grow" data-act="register">SIGN UP</button>
      </div>
    </div>
    <p class="center muted"><small>On iPhone: Share → Add to Home Screen to install it.</small></p>`
  wire()
}

async function doAuth(kind) {
  const email = document.getElementById('auth-email').value.trim()
  const password = document.getElementById('auth-password').value
  const name = document.getElementById('auth-name')?.value?.trim()
  const box = document.getElementById('auth-error')
  if (kind === 'register' && document.getElementById('name-wrap').classList.contains('hidden')) {
    document.getElementById('name-wrap').classList.remove('hidden')
    box.classList.add('hidden')
    return
  }
  try {
    const out = await api(`/api/auth/${kind}`, {
      method: 'POST',
      body: JSON.stringify(kind === 'register' ? { email, password, name } : { email, password })
    })
    state.token = out.token
    state.user = out.user
    localStorage.setItem(LS.token, out.token)
    localStorage.setItem(LS.user, JSON.stringify(out.user))
    await refreshMe()
    render()
  } catch (e) {
    box.textContent = e.message
    box.classList.remove('hidden')
  }
}

async function refreshMe() {
  const me = await api('/api/me')
  state.user = me.user
  state.calendars = me.calendars
  localStorage.setItem(LS.user, JSON.stringify(me.user))
  localStorage.setItem(LS.calendars, JSON.stringify(me.calendars))
  if (!state.calendarId || !state.calendars.some((c) => c.id === state.calendarId)) {
    selectCalendar(state.calendars[0]?.id || '', false)
  }
  await sync()
}

function selectCalendar(id, doRender = true) {
  state.calendarId = id
  localStorage.setItem(LS.calendarId, id)
  loadData()
  if (doRender) render()
  sync().catch(() => {})
}

function logout() {
  localStorage.removeItem(LS.token)
  state.token = ''
  state.user = null
  render()
}

// ---------------------------------------------------------------- calendar view

function viewCalendar() {
  const first = startOfMonth(state.month)
  const offset = (first.getDay() + 6) % 7 // week starts on Monday
  const gridStart = new Date(first); gridStart.setDate(first.getDate() - offset)

  const inMonth = payments().filter((p) => {
    const d = fromEpochDay(p.dueDate)
    return d.getMonth() === state.month.getMonth() && d.getFullYear() === state.month.getFullYear()
  })
  const bills = inMonth.filter(isBill)
  const appointments = inMonth.filter(isAppointment)
  const incomes = inMonth.filter(isIncome)
  const open = bills.filter(isOpen)
  const paid = bills.filter(isPaid)
  const incomeTotal = incomes.reduce((s, p) => s + p.amountCents, 0)
  const outTotal = bills.reduce((s, p) => s + p.amountCents, 0)

  let cells = ''
  for (let i = 0; i < 42; i++) {
    const d = new Date(gridStart); d.setDate(gridStart.getDate() + i)
    const ed = epochDay(d)
    const dayPayments = payments().filter((p) => p.dueDate === ed)
    const late = dayPayments.some((p) => isOpen(p) && isBill(p) && ed < epochDay(today()))
    const allPaid = dayPayments.length > 0 && dayPayments.every((p) => !isOpen(p))
    const classes = ['day']
    if (d.getMonth() !== state.month.getMonth()) classes.push('out')
    if (sameDay(d, today())) classes.push('today')
    if (sameDay(d, state.selected)) classes.push('sel')
    else if (late) classes.push('late')
    else if (allPaid) classes.push('done')
    const dots = dayPayments.slice(0, 3)
      .map((p) => `<i style="background:${colorOf(p.colorIndex)}"></i>`).join('')
    const hasFiles = dayAttachments(d).length > 0
    const hasNote = !!noteFor(d)?.text
    cells += `<div class="${classes.join(' ')}" data-day="${ed}">
        ${d.getDate()}
        <span class="dots">${dots}</span>
        ${hasFiles ? '<span class="mark l">📎</span>' : ''}
        ${hasNote ? '<span class="mark r">📝</span>' : ''}
      </div>`
  }

  const selectedRows = paymentsOn(state.selected)
  return `${header('PAY & PLAN')}
    <div class="row between" style="margin-bottom:10px">
      <button class="coral small" data-act="prev-month">‹</button>
      <h2 class="grow center">${MONTHS[state.month.getMonth()]} ${state.month.getFullYear()}</h2>
      <button class="mint small" data-act="next-month">›</button>
      <button class="yellow small" data-act="today">TODAY</button>
    </div>

    <div class="card sky">
      <div class="row between center">
        <div class="grow"><small>TO PAY</small><br><b class="poster">${money(open.reduce((s, p) => s + p.amountCents, 0))}</b><br><small>${open.length} left</small></div>
        <div class="grow"><small>PAID</small><br><b class="poster">${money(paid.reduce((s, p) => s + (p.paidAmountCents ?? p.amountCents), 0))}</b><br><small>${paid.length} done</small></div>
        <div class="grow"><small>IN</small><br><b class="poster">${money(incomeTotal)}</b><br><small>${incomes.length} incomes</small></div>
      </div>
      <div class="center" style="margin-top:6px">
        <small>balance </small><b class="poster">${money(incomeTotal - outTotal)}</b>
        ${appointments.length ? `<small> · 🗓 ${appointments.length} appointment${appointments.length > 1 ? 's' : ''}</small>` : ''}
      </div>
    </div>

    <div class="card tight">
      <div class="weekdays">${WEEKDAYS.map((w) => `<div>${w}</div>`).join('')}</div>
      <div style="height:6px"></div>
      <div class="weeks">${cells}</div>
    </div>

    <div class="row between">
      <div class="grow">
        <h3>${esc(fullDayLabel(state.selected).toUpperCase())}</h3>
        <small class="muted">${relativeLabel(state.selected)}</small>
      </div>
      <button class="sky small" data-act="open-day" data-day="${epochDay(state.selected)}">OPEN DAY</button>
    </div>
    <div style="height:10px"></div>
    ${selectedRows.length
      ? selectedRows.map(billRow).join('')
      : `<div class="card yellow bubble tap" data-act="new-bill" data-day="${epochDay(state.selected)}">
           <div class="emo">🎉</div>Nothing here. Tap to add something.</div>`}`
}

function billRow(p) {
  const d = fromEpochDay(p.dueDate)
  const appt = isAppointment(p)
  const income = isIncome(p)
  const late = isOpen(p) && p.dueDate < epochDay(today())
  const cls = isPaid(p) ? 'mint'
    : late ? (appt || income ? 'paper' : 'coral')
    : 'paper'
  const owner = memberById(p.ownerUserId)
  const badges = [
    p.installmentCount ? `Installment ${p.installmentIndex}/${p.installmentCount}` : '',
    p.recurrence !== 'NONE' ? '🔁' : '',
    p.visibility === 'PRIVATE' ? '🔒' : '',
    p.shoppingListId ? '🛒' : '',
    appt && p.location ? '📍 ' + esc(p.location) : ''
  ].filter(Boolean).join(' · ')
  const right = (appt || isReminder(p))
    ? `<div class="amount">${timeLabel(p.dueTimeMinutes)}</div>
       ${p.amountCents ? `<small>${money(p.amountCents, p.currency)}</small>`
         : isReminder(p) ? '<small class="muted">to do</small>' : '<small class="muted">no cost</small>'}`
    : `<div class="amount" ${income ? 'style="color:#2f7d3a"' : ''}>${income ? '+' : ''}${money(p.amountCents, p.currency)}</div>
       ${owner ? `<small>${esc(owner.name)}</small>` : ''}`
  return `<div class="card ${cls} tight bill" data-bill="${p.id}">
      <span class="avatar" style="background:${
        income ? 'var(--mint)' : isReminder(p) ? 'var(--tangerine)' : colorOf(p.colorIndex)}">${
        appt ? '🗓' : income ? '💰' : isReminder(p) ? '⏰'
          : esc((p.title || '?').trim()[0] || '?').toUpperCase()}</span>
      <div class="grow">
        <b class="truncate">${esc(p.title)}</b><br>
        <small>${isPaid(p)
          ? (appt || isReminder(p) ? 'Done' : income ? 'Received' : 'Paid')
          : relativeLabel(d)}${badges ? ' · ' + badges : ''}</small>
      </div>
      <div class="center">${right}</div>
    </div>`
}

// ---------------------------------------------------------------- bills view

function viewBills() {
  const everything = payments()
  const owner = state.billsOwner
  const all = owner ? everything.filter((p) => p.ownerUserId === owner) : everything
  const t = epochDay(today())
  const horizon = t + 30

  const within30 = (p) => isOpen(p) && p.dueDate <= horizon && p.dueDate >= t
  const beyond30 = (p) => isOpen(p) && p.dueDate > horizon
  const byTime = (a, b) => a.dueDate - b.dueDate || (a.dueTimeMinutes || 0) - (b.dueTimeMinutes || 0)

  const billsOnly = all.filter(isBill)
  const late = billsOnly.filter((p) => isOpen(p) && p.dueDate < t).sort(byTime)
  const pay30 = billsOnly.filter(within30).sort(byTime)
  const payLater = billsOnly.filter(beyond30)
  const paid = billsOnly.filter(isPaid).sort((a, b) => (b.paidAt || 0) - (a.paidAt || 0))

  const appt30 = all.filter((p) => isAppointment(p) && within30(p)).sort(byTime)
  const apptLater = all.filter((p) => isAppointment(p) && beyond30(p))

  const in30 = all.filter((p) => isIncome(p) && within30(p)).sort(byTime)
  const inLater = all.filter((p) => isIncome(p) && beyond30(p))

  const rem30 = all.filter((p) => isReminder(p) && (within30(p) || p.dueDate < t)).sort(byTime)
  const remLater = all.filter((p) => isReminder(p) && beyond30(p))

  const outTotal30 = pay30.reduce((sum, p) => sum + p.amountCents, 0)
  const inTotal30 = in30.reduce((sum, p) => sum + p.amountCents, 0)

  // everything of the next 30 days in one chronological run, whatever its type
  const next30 = [...late, ...pay30, ...appt30, ...in30, ...rem30].sort(byTime)

  const plain = (title, emoji, rows) =>
    rows.length ? `<h3>${emoji} ${title.toUpperCase()}</h3>${rows.map(billRow).join('')}` : ''

  /** A folded block: one line with the count and the total, tap to unfold. */
  const fold = (key, title, emoji, rows, withTotal = true) => {
    if (!rows.length) return ''
    const open = state.openSections.has(key)
    const total = rows.reduce((sum, p) => sum + p.amountCents, 0)
    return `<div class="card tight tap" data-act="toggle-section" data-key="${key}">
        <div class="row between">
          <b>${open ? '▾' : '▸'} ${emoji} ${title}</b>
          <span><small>${rows.length}${withTotal ? ' · ' : ''}</small>${
            withTotal ? `<b class="amount">${money(total)}</b>` : ''}</span>
        </div>
      </div>${open ? rows.map(billRow).join('') : ''}`
  }

  const byMonth = (rows) => {
    const groups = new Map()
    for (const row of rows) {
      const d = fromEpochDay(row.dueDate)
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
      if (!groups.has(key)) {
        groups.set(key, { key, label: `${MONTHS[d.getMonth()]} ${d.getFullYear()}`, rows: [] })
      }
      groups.get(key).rows.push(row)
    }
    return [...groups.values()].sort((a, b) => a.key.localeCompare(b.key))
  }

  const monthSection = (title, emoji, rows) => {
    if (!rows.length) return ''
    return `<h3>${emoji} ${title.toUpperCase()}</h3>` + byMonth(rows).map((g) => {
      const groupKey = title + g.key
      const open = state.openMonths.has(groupKey)
      const total = g.rows.reduce((sum, r) => sum + r.amountCents, 0)
      return `<div class="card tight tap" data-act="toggle-month" data-key="${groupKey}">
          <div class="row between">
            <b>${open ? '▾' : '▸'} ${g.label}</b>
            <span><small>${g.rows.length} · </small><b class="amount">${money(total)}</b></span>
          </div>
        </div>${open ? g.rows.sort(byTime).map(billRow).join('') : ''}`
    }).join('')
  }

  const ownerChips = members().length > 1
    ? `<div class="row wrap" style="margin-bottom:10px">
         <span class="chip ${owner ? '' : 'on'}" data-act="bills-owner" data-owner=""
               style="background:${owner ? 'var(--paper)' : 'var(--yellow)'}">Everyone</span>
         ${members().map((m) => `<span class="chip ${owner === m.id ? 'on' : ''}"
              data-act="bills-owner" data-owner="${m.id}"
              style="background:${owner === m.id ? colorOf(m.colorIndex) : 'var(--paper)'}">${esc(m.name)}</span>`).join('')}
       </div>`
    : ''

  const FILTERS = [
    ['open', 'To pay', 'var(--yellow)'],
    ['incoming', 'Incoming', 'var(--mint)'],
    ['appointments', 'Appointments', 'var(--aqua)'],
    ['reminders', 'Reminders', 'var(--tangerine)'],
    ['late', 'Late', 'var(--coral)'],
    ['paid', 'Paid', 'var(--mint)'],
    ['all', 'Everything', 'var(--sky)']
  ]
  const filter = state.billsFilter || 'open'
  const filterChips = `<div class="row wrap" style="margin-bottom:12px">
      ${FILTERS.map(([id, label, colour]) => `<span class="chip ${filter === id ? 'on' : ''}"
           data-act="bills-filter" data-filter="${id}"
           style="background:${filter === id ? colour : 'var(--paper)'}">${label}</span>`).join('')}
    </div>`

  let body = ''
  if (filter === 'open') {
    // money going out only: income and appointments have their own filters
    body = plain('Late', '🔥', late) +
      fold('pay30', 'Payments (30 days)', '🧾', pay30) +
      monthSection('Later', '🌙', payLater)
    if (!late.length && !pay30.length && !payLater.length) {
      body = `<div class="card mint bubble tap" data-act="new-bill">
          <div class="emo">🥳</div>Nothing to pay. Tap here to add a bill.</div>`
    }
  } else if (filter === 'appointments') {
    const been = all.filter((p) => isAppointment(p) && isPaid(p))
      .sort((a, b) => b.dueDate - a.dueDate)
    body = fold('appt30open', 'Appointments (30 days)', '🗓', appt30, false) +
      monthSection('Appointments later', '🗓', apptLater) +
      plain('Already been', '✅', been.slice(0, 20))
    if (!appt30.length && !apptLater.length && !been.length) {
      body = `<div class="card mint bubble tap" data-act="new-bill">
          <div class="emo">🗓</div>No appointments. Tap here to add one.</div>`
    }
  } else if (filter === 'reminders') {
    const done = all.filter((p) => isReminder(p) && isPaid(p)).sort((a, b) => b.dueDate - a.dueDate)
    body = fold('rem30', 'To do (30 days)', '⏰', rem30, false) +
      monthSection('Reminders later', '⏰', remLater) +
      plain('Done', '✅', done.slice(0, 20))
    if (!rem30.length && !remLater.length && !done.length) {
      body = `<div class="card mint bubble tap" data-act="new-bill">
          <div class="emo">⏰</div>No reminders. Tap here to add one.</div>`
    }
  } else if (filter === 'incoming') {
    const received = all.filter((p) => isIncome(p) && isPaid(p)).slice(0, 20)
    body = fold('in30open', 'Incoming (30 days)', '💰', in30) +
      monthSection('Income later', '💰', inLater) +
      plain('Already received', '✅', received)
    if (!in30.length && !inLater.length && !received.length) {
      body = `<div class="card mint bubble tap" data-act="new-bill">
          <div class="emo">💰</div>No income planned. Tap here to add one.</div>`
    }
  } else if (filter === 'late') {
    body = late.length
      ? plain('Late', '🔥', late)
      : `<div class="card mint bubble"><div class="emo">💪</div>Nothing late. You rock!</div>`
  } else if (filter === 'paid') {
    body = paid.length
      ? monthSection('Paid', '✅', paid)
      : `<div class="card yellow bubble"><div class="emo">🧾</div>No receipts yet.</div>`
  } else {
    // everything of the next 30 days together, in time order
    body = next30.length
      ? plain('Next 30 days', '📆', next30)
      : `<div class="card mint bubble tap" data-act="new-bill">
           <div class="emo">🥳</div>Nothing in the next 30 days.</div>`
    body += monthSection('Further ahead', '🌙', [...payLater, ...inLater, ...apptLater])
  }

  return `${header('MY BILLS')}
    ${ownerChips}
    <div class="card yellow">
      <div class="row between center">
        <div class="grow"><small>LATE</small><br><b class="poster">${late.length}</b><br><small>${money(late.reduce((s, p) => s + p.amountCents, 0))}</small></div>
        <div class="grow"><small>OUT 30d</small><br><b class="poster">${money(outTotal30)}</b><br><small>${pay30.length} bills</small></div>
        <div class="grow"><small>IN 30d</small><br><b class="poster">${money(inTotal30)}</b><br><small>${in30.length} incomes</small></div>
      </div>
      <div class="center" style="margin-top:6px">
        <small>net </small><b class="poster">${money(inTotal30 - outTotal30)}</b>
      </div>
    </div>
    ${filterChips}
    ${body}`
}

// ---------------------------------------------------------------- notes view

const NOTE_CATEGORIES = ['Recipes', 'Prompts', 'Thoughts', 'Articles', 'Links', 'Photos', 'Other']

function notes() {
  const me = state.user?.id
  return alive(state.data.notes || []).filter(
    (n) => n.visibility !== 'PRIVATE' || n.createdByUserId === me || n.ownerUserId === me
  )
}

function noteAttachments(noteId) {
  return alive(state.data.attachments).filter((a) => a.noteId === noteId)
}

/** Categories actually in use, plus the standard ones, so the filter row stays useful. */
/** Categories already used on bills, appointments, income and reminders. */
function entryCategories() {
  return [...new Set(
    payments().map((p) => (p.category || '').trim()).filter(Boolean)
  )].sort()
}

function noteCategories() {
  const used = new Set(notes().map((n) => (n.category || '').trim()).filter(Boolean))
  return [...new Set([...NOTE_CATEGORIES, ...used])]
}

function viewNotes() {
  const category = state.noteCategory || ''
  const search = (state.noteSearch || '').trim().toLowerCase()
  let rows = notes()
  if (category) rows = rows.filter((n) => (n.category || '') === category)
  if (search) {
    rows = rows.filter((n) =>
      (n.title || '').toLowerCase().includes(search) || (n.body || '').toLowerCase().includes(search))
  }
  rows = rows.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (b.updatedAt || 0) - (a.updatedAt || 0))

  const counts = new Map()
  for (const n of notes()) {
    const key = (n.category || '').trim() || 'Other'
    counts.set(key, (counts.get(key) || 0) + 1)
  }

  const chips = `<div class="row wrap" style="margin-bottom:10px">
      <span class="chip ${category ? '' : 'on'}" data-act="note-cat" data-cat=""
            style="background:${category ? 'var(--paper)' : 'var(--yellow)'}">All (${notes().length})</span>
      ${noteCategories().map((c) => `<span class="chip ${category === c ? 'on' : ''}"
           data-act="note-cat" data-cat="${esc(c)}"
           style="background:${category === c ? 'var(--grape)' : 'var(--paper)'}">${esc(c)}${
             counts.get(c) ? ' ' + counts.get(c) : ''}</span>`).join('')}
    </div>`

  return `${header('NOTES')}
    <p class="muted"><small>Anything without a date: recipes, prompts, thoughts, links, photos,
      voice memos. Shared with the calendar unless you mark it private.</small></p>
    <input id="note-search" placeholder="Search" value="${esc(state.noteSearch || '')}"
           data-live="note-search" />
    ${chips}
    ${rows.length
      ? rows.map(noteCard).join('')
      : `<div class="card yellow bubble tap" data-act="new-note">
           <div class="emo">📝</div>${search || category ? 'Nothing here.' : 'No notes yet. Tap to write one.'}</div>`}`
}

function noteCard(n) {
  const files = noteAttachments(n.id)
  const images = files.filter((a) => (a.mime || '').startsWith('image/'))
  const others = files.length - images.length
  const owner = memberById(n.ownerUserId)
  return `<div class="card" style="background:${colorOf(n.colorIndex)}" data-act="open-note" data-id="${n.id}">
      <div class="row between">
        <b>${n.pinned ? '📌 ' : ''}${esc(n.title || 'Untitled')}</b>
        <small>${esc(n.category || '')}</small>
      </div>
      ${n.body ? `<div class="truncate-3"><small>${esc(n.body).replace(/\n/g, '<br>')}</small></div>` : ''}
      ${images.length ? `<div class="files" style="margin-top:8px">
          ${images.slice(0, 4).map((a) => `<img class="note-thumb" src="${fileUrl(a)}" alt="">`).join('')}
        </div>` : ''}
      <small class="muted">${others ? `📎 ${others} file${others > 1 ? 's' : ''} · ` : ''}${
        owner ? 'for ' + esc(owner.name) + ' · ' : ''}${
        n.visibility === 'PRIVATE' ? '🔒 private' : 'shared'}</small>
    </div>`
}

function noteEditor(existing) {
  const n = existing || {
    id: uuid(), title: '', body: '', category: '', colorIndex: 4, pinned: false,
    ownerUserId: state.user?.id || null, createdByUserId: state.user?.id || null,
    visibility: 'SHARED', createdAt: Date.now(), updatedAt: Date.now(), deletedAt: null
  }
  const files = noteAttachments(n.id)
  openModal(`
    <h2>${existing ? 'EDIT NOTE' : 'NEW NOTE'}</h2>
    <label>Title</label>
    <input id="n-title" value="${esc(n.title)}" placeholder="Grandma's ragu, that prompt, a link..." />
    <label>For</label>
    <select id="n-owner">
      <option value="">Everyone in the calendar</option>
      ${members().map((m) => `<option value="${m.id}" ${n.ownerUserId === m.id ? 'selected' : ''}>${esc(m.name)}</option>`).join('')}
    </select>
    <label>Category</label>
    <input id="n-category" value="${esc(n.category)}" list="note-categories" placeholder="Recipes, Prompts, Links..." />
    <datalist id="note-categories">
      ${noteCategories().map((c) => `<option value="${esc(c)}"></option>`).join('')}
    </datalist>
    <label>Note</label>
    <textarea id="n-body" style="min-height:140px">${esc(n.body)}</textarea>
    <label>Colour</label>
    <div class="row wrap" id="n-colors">
      ${COLORS.map((c, i) => `<span class="dot" data-note-color="${i}"
          style="width:28px;height:28px;background:${c};border-width:${n.colorIndex === i ? 4 : 2}px"></span>`).join('')}
    </div>
    <label><input type="checkbox" id="n-pinned" ${n.pinned ? 'checked' : ''} style="width:auto;margin-right:8px">Pin to the top</label>
    <label><input type="checkbox" id="n-private" ${n.visibility === 'PRIVATE' ? 'checked' : ''} style="width:auto;margin-right:8px">Private (only you can see it)</label>

    <div class="card" style="margin-top:12px">
      <h3>📎 ATTACHED</h3>
      <div class="files">${files.map(fileTile).join('') || '<small class="muted">Photos, video, audio, documents: anything.</small>'}</div>
      <div style="height:10px"></div>
      <div class="row wrap">
        <label class="chip" style="background:var(--sky)">📷 Photo / video
          <input type="file" accept="image/*,video/*" capture="environment" data-note="${n.id}" style="display:none"></label>
        <label class="chip" style="background:var(--grape)">📁 File
          <input type="file" data-note="${n.id}" style="display:none"></label>
        <button class="chip" data-act="record-audio" data-id="${n.id}" style="background:var(--coral)">🎙 Record</button>
        <button class="chip" data-act="fetch-link" data-id="${n.id}" style="background:var(--yellow)">🔗 Read the link</button>
      </div>
      <small class="muted">A shared chat or article: the server reads the page and drops the
        text and the pictures in here.</small>
      <div id="rec-status"></div>
    </div>

    <div class="row" style="margin-top:10px">
      <button class="mint grow" data-act="store-note" data-id="${existing ? n.id : ''}">${existing ? 'SAVE' : 'CREATE'}</button>
      ${existing ? `<button class="coral" data-act="del-note" data-id="${n.id}">DELETE</button>` : ''}
      <button data-act="close">CANCEL</button>
    </div>`)
  window.__editingNote = n
}

/**
 * Pulls a shared link apart on the server and pours it into the note: the conversation or
 * the article as text, the pictures as attachments.
 */
async function fetchLinkIntoNote(noteId) {
  const titleField = document.getElementById('n-title')
  const bodyField = document.getElementById('n-body')
  const status = document.getElementById('rec-status')
  const haystack = `${titleField?.value || ''} ${bodyField?.value || ''}`
  const link = (haystack.match(/https?:\/\/[^\s"'<>]+/) || [])[0]
  if (!link) return toast('No link in this note')

  // the note has to exist before the pictures can hang off it
  let note = state.data.notes.find((x) => x.id === noteId)
  if (!note) {
    note = window.__editingNote
    note.title = titleField?.value.trim() || note.title
    note.body = bodyField?.value || note.body
    state.data.notes.push(note)
    touch('notes', note)
    await sync().catch(() => {})
  }

  if (status) status.innerHTML = '<small class="muted">reading the page…</small>'
  try {
    const page = await api(`/api/calendars/${state.calendarId}/unfurl`, {
      method: 'POST',
      body: JSON.stringify({ url: link, noteId })
    })
    for (const a of page.savedImages || []) {
      if (!state.data.attachments.some((x) => x.id === a.id)) state.data.attachments.push(a)
    }
    const heading = page.kind === 'conversation' ? 'Shared conversation' : page.title
    const merged = [
      bodyField?.value?.trim(),
      `--- ${heading} ---`,
      page.url,
      '',
      page.text
    ].filter(Boolean).join('\n')

    note.title = (titleField?.value.trim() || page.title || '').slice(0, 200)
    note.body = merged
    if (titleField) titleField.value = note.title
    if (bodyField) bodyField.value = merged
    touch('notes', note)
    persist()
    toast(`Read it${page.savedImages?.length ? `, ${page.savedImages.length} image(s) saved` : ''}`)
    closeModals()
    noteEditor(state.data.notes.find((x) => x.id === noteId))
  } catch (e) {
    if (status) status.innerHTML = ''
    toast(e.message)
  }
}

/** Voice memo recorded straight in the page, saved as a normal attachment. */
async function toggleRecording(noteId) {
  const status = document.getElementById('rec-status')
  if (window.__recorder && window.__recorder.state === 'recording') {
    window.__recorder.stop()
    return
  }
  if (!navigator.mediaDevices?.getUserMedia) return toast('Recording not supported here')
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const chunks = []
    const recorder = new MediaRecorder(stream)
    window.__recorder = recorder
    recorder.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
    recorder.onstop = async () => {
      stream.getTracks().forEach((t) => t.stop())
      const type = recorder.mimeType || 'audio/webm'
      const ext = type.includes('mp4') ? 'm4a' : type.includes('ogg') ? 'ogg' : 'webm'
      const file = new File(chunks, `memo-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-')}.${ext}`, { type })
      if (status) status.innerHTML = '<small class="muted">saving…</small>'
      try {
        await uploadFile(file, { noteId })
        toast('Voice memo saved')
        closeModals()
        const note = state.data.notes.find((x) => x.id === noteId)
        if (note) noteEditor(note)
      } catch (e) { toast(e.message) }
      window.__recorder = null
    }
    recorder.start()
    if (status) status.innerHTML = '<small style="color:#c0392b">● recording… tap Record again to stop</small>'
  } catch (e) {
    toast('Microphone blocked')
  }
}

// ---------------------------------------------------------------- lists view

function viewLists() {
  const rows = lists().sort((a, b) => (a.status === b.status ? (b.updatedAt || 0) - (a.updatedAt || 0) : a.status === 'OPEN' ? -1 : 1))
  return `${header('SHOPPING')}
    <p class="muted"><small>Make a list, hand it to someone else. When they are done they type in what it cost and it lands in the calendar.</small></p>
    ${rows.length ? rows.map(listRow).join('') : `<div class="card yellow bubble tap" data-act="new-list">
         <div class="emo">🛒</div>No lists yet. Tap here to make one.</div>`}`
}

function listRow(l) {
  const items = itemsOf(l.id)
  const done = items.filter((i) => i.checked).length
  const who = memberById(l.assignedToUserId)
  const cls = l.status === 'DONE' ? 'mint' : 'paper'
  return `<div class="card ${cls}" data-list="${l.id}">
      <div class="row between">
        <div class="grow">
          <b>${esc(l.title)}</b><br>
          <small>${done}/${items.length} items${who ? ' · for ' + esc(who.name) : ''}${l.dueDate ? ' · ' + dayLabel(fromEpochDay(l.dueDate)) + ' ' + timeLabel(l.dueTimeMinutes ?? 1080) : ''}</small>
        </div>
        <div class="center">
          ${l.status === 'DONE'
            ? `<span class="stamp" style="background:var(--mint)">${money(l.actualCents || 0)}</span>`
            : l.budgetCents ? `<small>budget</small><br><b>${money(l.budgetCents)}</b>` : ''}
        </div>
      </div>
    </div>`
}

// ---------------------------------------------------------------- setup view

function viewSetup() {
  const cal = calendar()
  const isOwner = isOwnerOfCalendar()
  return `${header('SETUP')}
    <div class="card">
      <h3>👤 YOU</h3>
      <label>Name</label>
      <input id="me-name" value="${esc(state.user?.name || '')}" />
      <div class="row">
        <button class="mint small" data-act="save-me">SAVE</button>
        <button class="small" data-act="logout">LOG OUT</button>
      </div>
      <small class="muted">${esc(state.user?.email || '')}</small>
    </div>

    <div class="card sky">
      <h3>📚 CALENDAR</h3>
      <label>Name</label>
      <input id="cal-name" value="${esc(cal?.name || '')}" ${isOwner ? '' : 'disabled'} />
      <label>Currency</label>
      <select id="cal-currency" ${isOwner ? '' : 'disabled'}>
        ${['EUR', 'USD', 'GBP', 'CHF'].map((c) => `<option ${cal?.currency === c ? 'selected' : ''}>${c}</option>`).join('')}
      </select>
      ${isOwner ? `<button class="mint small" data-act="save-cal">SAVE</button>` : ''}
      <div style="height:10px"></div>
      <small class="muted">Missing something? Pull the whole calendar again.</small><br>
      <button class="coral small" data-act="reload-all">RELOAD ALL</button>
      <div style="height:12px"></div>
      <h3>👥 PEOPLE</h3>
      ${members().map((m) => `<div class="row" style="margin:6px 0">
          <span class="avatar" style="background:${colorOf(m.colorIndex)}">${esc(m.name[0] || '?').toUpperCase()}</span>
          <div class="grow"><b>${esc(m.name)}</b><br><small>${esc(m.email)} · ${m.role}</small></div>
          ${isOwner && m.id !== state.user.id ? `<button class="coral small" data-act="kick" data-user="${m.id}">Remove</button>` : ''}
        </div>`).join('')}
      <div style="height:10px"></div>
      <div class="card paper tight">
        <b>Invite code</b><br>
        <span class="poster" style="font-size:26px;letter-spacing:3px">${esc(cal?.inviteCode || '------')}</span><br>
        <small class="muted">The other person signs up, then enters this code below.</small>
        ${isOwner ? `<div style="height:8px"></div><button class="small" data-act="rotate-code">New code</button>` : ''}
      </div>
    </div>

    <div class="card yellow">
      <h3>➕ ANOTHER CALENDAR</h3>
      <label>Join with a code</label>
      <input id="join-code" placeholder="ABC123" style="text-transform:uppercase" />
      <button class="mint small" data-act="join">JOIN</button>
      <div style="height:12px"></div>
      <label>Or create a new one</label>
      <input id="new-cal-name" placeholder="Home, Work, Holiday..." />
      <button class="small" data-act="create-cal">CREATE</button>
      <div style="height:12px"></div>
      ${state.calendars.map((c) => `<button class="chip ${c.id === state.calendarId ? 'on' : ''}" data-act="pick-cal" data-cal="${c.id}">${esc(c.name)}</button>`).join(' ')}
    </div>

    <div class="card" style="border-color:var(--coral)">
      <h3>⚠️ DANGER ZONE</h3>
      ${isOwner
        ? `<small class="muted">Deleting <b>${esc(cal?.name || '')}</b> removes its bills, appointments,
             receipts and shopping lists for everyone in it. There is no undo.</small>
           <div style="height:10px"></div>
           <button class="coral small" data-act="delete-cal"
             ${state.calendars.length > 1 ? '' : 'disabled'}>DELETE THIS CALENDAR</button>
           ${state.calendars.length > 1 ? '' : '<br><small class="muted">This is your only calendar: create another one first.</small>'}`
        : `<small class="muted">You are a guest here. Leaving removes the calendar from your app;
             the others keep it.</small>
           <div style="height:10px"></div>
           <button class="coral small" data-act="leave-cal">LEAVE THIS CALENDAR</button>`}
    </div>

    <div class="card">
      <h3>📱 INSTALL</h3>
      <small class="muted">iPhone: Share → <b>Add to Home Screen</b>. Android: menu → <b>Install app</b>.
      Once installed it opens full screen and keeps working offline; changes sync as soon as there is signal.</small>
    </div>`
}

// ---------------------------------------------------------------- modals

function openModal(html) {
  const holder = document.createElement('div')
  holder.className = 'modal'
  holder.innerHTML = `<div class="sheet">${html}</div>`
  holder.addEventListener('click', (e) => { if (e.target === holder) holder.remove() })
  document.body.appendChild(holder)
  wire(holder)
  return holder
}
const closeModals = () => document.querySelectorAll('.modal').forEach((m) => m.remove())

function billEditor(existing, presetDay) {
  const p = existing || newPayment({ dueDate: epochDay(presetDay || state.selected) })
  const isNew = !existing
  const memberOptions = members()
    .map((m) => `<option value="${m.id}" ${p.ownerUserId === m.id ? 'selected' : ''}>${esc(m.name)}</option>`).join('')
  const appt = p.kind === 'APPOINTMENT'
  const income = p.kind === 'INCOME'
  const reminder = p.kind === 'REMINDER'
  const chipBg = (k, colour) => p.kind === k ? colour : 'var(--paper)'
  openModal(`
    <h2>${isNew ? 'NEW ENTRY' : 'EDIT'}</h2>
    <div class="row wrap" style="margin-bottom:10px">
      <button class="chip ${p.kind === 'BILL' ? 'on' : ''} grow" data-act="kind" data-kind="BILL"
        style="background:${chipBg('BILL', 'var(--yellow)')}">🧾 Bill</button>
      <button class="chip ${appt ? 'on' : ''} grow" data-act="kind" data-kind="APPOINTMENT"
        style="background:${chipBg('APPOINTMENT', 'var(--aqua)')}">🗓 Appointment</button>
      <button class="chip ${income ? 'on' : ''} grow" data-act="kind" data-kind="INCOME"
        style="background:${chipBg('INCOME', 'var(--mint)')}">💰 Income</button>
      <button class="chip ${reminder ? 'on' : ''} grow" data-act="kind" data-kind="REMINDER"
        style="background:${chipBg('REMINDER', 'var(--tangerine)')}">⏰ Reminder</button>
    </div>
    <label>What is it?</label>
    <input id="f-title" value="${esc(p.title)}" placeholder="${
      appt ? 'Doctor, gym, hairdresser...'
      : income ? 'Salary, rent, refund...'
      : reminder ? 'Write the report for..., renew the passport...'
      : 'Rent, Netflix, school trip...'}" />
    <div id="amount-wrap" class="${reminder ? 'hidden' : ''}">
    <label>${
      appt ? 'Cost (leave empty if there is none)'
      : income ? 'How much comes in'
      : 'How much'} (${calendarCurrency()})</label>
    <input id="f-amount" inputmode="decimal" value="${p.amountCents ? centsToInput(p.amountCents) : ''}" />
    </div>
    <datalist id="entry-categories">
      ${entryCategories().map((c) => `<option value="${esc(c)}"></option>`).join('')}
    </datalist>
    <div id="where-wrap" class="${appt ? '' : 'hidden'}">
      <label>Where</label>
      <input id="f-location" value="${esc(p.location || '')}" placeholder="Address, clinic, studio..." />
    </div>
    <label id="date-label">${reminder ? 'Complete by' : 'Day'}</label>
    <input id="f-date" type="date" value="${inputDate(fromEpochDay(p.dueDate))}" />
    <label>Time</label>
    <input id="f-time" type="time" value="${String(Math.floor(p.dueTimeMinutes / 60)).padStart(2, '0')}:${String(p.dueTimeMinutes % 60).padStart(2, '0')}" />
    <label id="owner-label">${
      income ? 'Who receives it' : reminder ? 'Who has to do it' : 'Who pays it'}</label>
    <select id="f-owner"><option value="">Nobody in particular</option>${memberOptions}</select>
    <label>Repeats</label>
    <select id="f-recurrence">
      ${RECURRENCES.map(([v, l]) => `<option value="${v}" ${p.recurrence === v ? 'selected' : ''}>${l}</option>`).join('')}
    </select>
    <div id="until-wrap" class="${p.recurrence === 'NONE' ? 'hidden' : ''}">
      <label>Repeat until (leave empty to go on forever)</label>
      <div class="row">
        <input id="f-until" type="date" class="grow" style="margin:0"
               value="${p.recurrenceEndDate ? inputDate(fromEpochDay(p.recurrenceEndDate)) : ''}" />
        <button class="small" data-act="clear-until" type="button">No end</button>
      </div>
    </div>
    <label>Category</label>
    <input id="f-category" value="${esc(p.category || '')}" list="entry-categories"
           placeholder="Rent, School, Car..." />
    ${entryCategories().length ? `<div class="row wrap" style="margin-bottom:10px">
        ${entryCategories().slice(0, 9).map((c) => `<span class="chip" data-act="pick-category"
             data-value="${esc(c)}" style="background:var(--sky)">${esc(c)}</span>`).join('')}
      </div>` : ''}
    <label>Colour</label>
    <div class="row wrap" id="f-colors">
      ${COLORS.map((c, i) => `<span class="dot" data-color="${i}" style="width:28px;height:28px;background:${c};border-width:${p.colorIndex === i ? 4 : 2}px"></span>`).join('')}
    </div>
    <div id="receipt-wrap" class="${appt ? 'hidden' : ''}">
      <label><input type="checkbox" id="f-receipt" ${p.requireReceipt ? 'checked' : ''} style="width:auto;margin-right:8px">Receipt required to close it</label>
    </div>
    <label><input type="checkbox" id="f-private" ${p.visibility === 'PRIVATE' ? 'checked' : ''} style="width:auto;margin-right:8px">Private (only you can see it)</label>
    ${isNew ? `
    <div class="card grape tight" id="plan-card" style="margin-top:10px${appt ? ';display:none' : ''}">
      <label><input type="checkbox" id="f-plan" style="width:auto;margin-right:8px">Installment plan with different amounts</label>
      <div id="plan-box" class="hidden">
        <div class="row">
          <input id="f-plan-count" inputmode="numeric" value="12" class="grow" data-live="plan-count" />
          <button class="yellow small" data-act="plan-fill">Fill</button>
        </div>
        <div class="row">
          <input id="f-plan-total" inputmode="decimal" placeholder="Split a total" class="grow" />
          <button class="mint small" data-act="plan-split">Split</button>
        </div>
        <div id="plan-rows"></div>
      </div>
    </div>` : ''}
    <label>Notes</label>
    <textarea id="f-notes">${esc(p.notes)}</textarea>
    <div class="row" style="margin-top:10px">
      <button class="mint grow" data-act="save-bill" data-id="${existing ? p.id : ''}">${isNew ? 'CREATE' : 'SAVE'}</button>
      <button class="grow" data-act="close">CANCEL</button>
    </div>`)
  window.__editing = p
}

function planRowsHtml(values) {
  const rec = document.getElementById('f-recurrence')?.value || 'NONE'
  const anchor = epochDay(new Date(document.getElementById('f-date').value))
  return values.map((v, i) => `<div class="row">
      <b style="width:34px">#${i + 1}</b>
      <input class="plan-amount grow" inputmode="decimal" value="${esc(v)}" />
      <small style="width:88px">${dayLabel(dateAt(anchor, rec, i))}</small>
    </div>`).join('')
}
function currentPlanValues() {
  return [...document.querySelectorAll('.plan-amount')].map((i) => i.value)
}

function billDetail(id) {
  const p = state.data.payments.find((x) => x.id === id)
  if (!p) return
  const files = attachmentsFor(p.id)
  const receipts = files.filter((a) => a.isReceipt)
  const others = files.filter((a) => !a.isReceipt)
  const d = fromEpochDay(p.dueDate)
  const late = isOpen(p) && p.dueDate < epochDay(today())
  const canClose = !p.requireReceipt || receipts.length > 0
  const owner = memberById(p.ownerUserId)
  openModal(`
    <div class="card" style="background:${colorOf(p.colorIndex)}">
      <h2>${esc(p.title)}</h2>
      <div class="poster" style="font-size:34px">${isAppointment(p) && !p.amountCents ? timeLabel(p.dueTimeMinutes) : money(p.amountCents, p.currency)}</div>
      <div>${esc(fullDayLabel(d))}${isAppointment(p) ? ' · ' + timeLabel(p.dueTimeMinutes) : ''}</div>
      ${isAppointment(p) && p.location ? `<div>📍 ${esc(p.location)}</div>` : ''}
      <small>${p.installmentCount ? `Installment ${p.installmentIndex}/${p.installmentCount} · ` : ''}${recurrenceLabel(p.recurrence)}${owner ? ' · ' + esc(owner.name) : ''}${p.visibility === 'PRIVATE' ? ' · 🔒 private' : ''}</small>
      <div style="height:8px"></div>
      <span class="stamp" style="background:${isPaid(p) ? 'var(--mint)' : late ? 'var(--coral)' : 'var(--yellow)'}">
        ${isPaid(p) ? 'PAID' : late ? relativeLabel(d) : 'WAITING'}</span>
      ${p.notes ? `<p>${esc(p.notes)}</p>` : ''}
    </div>

    <div class="card ${canClose ? 'mint' : 'paper'}">
      ${isPaid(p)
        ? `<h3>🎉 ALL DONE</h3><button class="yellow small" data-act="reopen" data-id="${p.id}">REOPEN</button>`
        : `<h3>${canClose
             ? (isAppointment(p) ? 'Been there?' : isReminder(p) ? 'Done with it?'
                : isIncome(p) ? 'Money arrived?' : 'Ready to close it?')
             : 'Receipt required first'}</h3>
           <div class="row">
             <button class="mint" data-act="pay" data-id="${p.id}" ${canClose ? '' : 'disabled'}>${
               isAppointment(p) || isReminder(p) ? 'MARK DONE ✓'
               : isIncome(p) ? 'MARK RECEIVED ✓' : 'MARK PAID ✓'}</button>
             <button class="small" data-act="edit-bill" data-id="${p.id}">EDIT</button>
             <button class="coral small" data-act="del-bill" data-id="${p.id}">DELETE</button>
           </div>`}
    </div>

    ${p.recurrence !== 'NONE' ? `<div class="card sky">
      <h3>🔁 THE SERIES</h3>
      <small class="muted">${recurrenceLabel(p.recurrence)}${p.recurrenceEndDate
        ? ' · until ' + esc(dayLabel(fromEpochDay(p.recurrenceEndDate)))
        : ' · no end date'} · ${seriesOf(p.seriesId).length} entries</small>
      <div style="height:10px"></div>
      <div class="row wrap">
        <button class="small" data-act="extend-series" data-id="${p.id}">EXTEND UNTIL…</button>
        <button class="coral small" data-act="stop-series" data-id="${p.id}">STOP AFTER THIS ONE</button>
      </div>
    </div>` : ''}

    <div class="card">
      <h3>🧾 RECEIPT</h3>
      <div class="files">${receipts.map(fileTile).join('') || '<small class="muted">Nothing attached yet.</small>'}</div>
      <div style="height:10px"></div>
      <input type="file" id="up-receipt" data-payment="${p.id}" data-receipt="1" accept="image/*,application/pdf" capture="environment" />
    </div>

    <div class="card">
      <h3>📎 OTHER FILES</h3>
      <div class="files">${others.map(fileTile).join('') || '<small class="muted">Invoices, contracts, anything.</small>'}</div>
      <div style="height:10px"></div>
      <input type="file" id="up-file" data-payment="${p.id}" />
    </div>
    <button data-act="close">CLOSE</button>`)
}

function fileTile(a) {
  const isImage = (a.mime || '').startsWith('image/')
  return `<div class="file ${a.isReceipt ? 'receipt' : ''}" data-file="${a.id}">
      <div class="thumb">${isImage ? `<img loading="lazy" src="${fileUrl(a)}" alt="">` : (a.mime || '').includes('pdf') ? '📕' : '📄'}</div>
      <div class="name">${esc(a.fileName)}</div>
      <div class="x" data-act="del-file" data-id="${a.id}">×</div>
    </div>`
}

function daySheet(ed) {
  const day = fromEpochDay(ed)
  const rows = paymentsOn(day)
  const files = dayAttachments(day)
  const note = noteFor(day)
  openModal(`
    <h2>${esc(fullDayLabel(day).toUpperCase())}</h2>
    <div class="card sky">
      <div class="row between">
        <div>
          <small>OUT</small><br>
          <b class="poster" style="font-size:24px">${money(rows.filter(isBill).reduce((s, p) => s + p.amountCents, 0))}</b>
          ${rows.some(isIncome) ? `<br><small>in </small><b style="color:#2f7d3a">${
            money(rows.filter(isIncome).reduce((s, p) => s + p.amountCents, 0))}</b>` : ''}
        </div>
        <button class="mint small" data-act="new-bill" data-day="${ed}">ADD BILL</button>
      </div>
    </div>
    ${rows.map(billRow).join('') || `<div class="card yellow bubble tap" data-act="new-bill" data-day="${ed}">
         <div class="emo">😌</div>Nothing on this day. Tap to add.</div>`}
    <div class="card">
      <h3>📎 DAY FILES</h3>
      <div class="files">${files.map(fileTile).join('') || '<small class="muted">Nothing pinned to this day.</small>'}</div>
      <div style="height:10px"></div>
      <input type="file" id="up-day" data-day="${ed}" />
    </div>
    <div class="card yellow">
      <h3>📝 DAY NOTE</h3>
      <textarea id="day-note">${esc(note?.text || '')}</textarea>
      <button class="mint small" data-act="save-note" data-day="${ed}">SAVE NOTE</button>
    </div>
    <button data-act="close">CLOSE</button>`)
}

function listEditor(existing) {
  const l = existing || {
    id: uuid(), title: '', notes: '', colorIndex: 2, dueDate: epochDay(today()), dueTimeMinutes: 1080,
    assignedToUserId: null, createdByUserId: state.user?.id || null,
    budgetCents: null, actualCents: null, status: 'OPEN', doneAt: null, doneByUserId: null,
    paymentId: null, visibility: 'SHARED', createdAt: Date.now(), updatedAt: Date.now(), deletedAt: null
  }
  openModal(`
    <h2>${existing ? 'EDIT LIST' : 'NEW SHOPPING LIST'}</h2>
    <label>Title</label>
    <input id="l-title" value="${esc(l.title)}" placeholder="Weekly shop, pharmacy..." />
    <label>Who does it</label>
    <select id="l-owner">
      <option value="">Anyone</option>
      ${members().map((m) => `<option value="${m.id}" ${l.assignedToUserId === m.id ? 'selected' : ''}>${esc(m.name)}</option>`).join('')}
    </select>
    <label>Day</label>
    <input id="l-date" type="date" value="${inputDate(fromEpochDay(l.dueDate || epochDay(today())))}" />
    <label>Time</label>
    <input id="l-time" type="time" value="${timeLabel(l.dueTimeMinutes ?? 1080)}" />
    <label>Budget (optional)</label>
    <input id="l-budget" inputmode="decimal" value="${l.budgetCents ? centsToInput(l.budgetCents) : ''}" />
    <div class="row" style="margin-top:10px">
      <button class="mint grow" data-act="save-list" data-id="${existing ? l.id : ''}">${existing ? 'SAVE' : 'CREATE'}</button>
      <button class="grow" data-act="close">CANCEL</button>
    </div>`)
  window.__editingList = l
}

function listDetail(id) {
  const l = state.data.shoppingLists.find((x) => x.id === id)
  if (!l) return
  const items = itemsOf(l.id)
  const who = memberById(l.assignedToUserId)
  const spent = items.reduce((s, i) => s + (i.priceCents || 0), 0)
  const onList = new Set(items.map((i) => (i.text || '').toLowerCase()))
  const vocabulary = itemVocabulary()
  const usual = vocabulary.filter((v) => !onList.has(v.toLowerCase())).slice(0, 8)
  openModal(`
    <h2>${esc(l.title)}</h2>
    <small class="muted">${who ? 'For ' + esc(who.name) : 'Anyone can do it'}${l.dueDate ? ' · ' + dayLabel(fromEpochDay(l.dueDate)) : ''}${l.budgetCents ? ' · budget ' + money(l.budgetCents) : ''}</small>
    <div class="card">
      <ul class="items">
        ${items.map((i) => {
          const photo = itemPhoto(i.id)
          return `<li class="${i.checked ? 'done' : ''}">
            <input type="checkbox" data-act="check-item" data-id="${i.id}" ${i.checked ? 'checked' : ''}>
            ${photo
              ? `<img class="item-photo" data-act="open-photo" data-id="${photo.id}"
                      src="${fileUrl(photo)}" alt="${esc(i.text)}">`
              : ''}
            <span class="txt grow">${esc(i.text)}${i.quantity ? ' <small>· ' + esc(i.quantity) + '</small>' : ''}</span>
            <label class="chip" style="padding:6px 8px" title="Photo of the product">
              📷<input type="file" accept="image/*" capture="environment"
                       data-item="${i.id}" style="display:none">
            </label>
            <input class="item-price" data-id="${i.id}" inputmode="decimal" placeholder="€"
                   value="${i.priceCents != null ? centsToInput(i.priceCents) : ''}"
                   style="width:80px;margin:0" />
            <button class="small ghost" data-act="del-item" data-id="${i.id}">×</button>
          </li>`
        }).join('') || '<li><small class="muted">Empty list. Add what is needed.</small></li>'}
      </ul>
      <div class="row" style="margin-top:10px">
        <input id="new-item" class="grow" placeholder="Add something" style="margin:0"
               list="item-vocabulary" autocomplete="off" autocapitalize="sentences" />
        <datalist id="item-vocabulary">
          ${vocabulary.map((v) => `<option value="${esc(v)}"></option>`).join('')}
        </datalist>
        <button class="mint small" data-act="add-item" data-list="${l.id}">ADD</button>
      </div>
      ${usual.length ? `<div class="row wrap" style="margin-top:10px">
        ${usual.map((v) => `<span class="chip" data-act="quick-item" data-list="${l.id}"
             data-text="${esc(v)}" style="background:var(--sky)">+ ${esc(v)}</span>`).join('')}
      </div>` : ''}
    </div>

    ${l.status === 'DONE'
      ? `<div class="card mint">
           <h3>✅ DONE</h3>
           <div class="poster" style="font-size:28px">${money(l.actualCents || 0)}</div>
           <small>${l.doneByUserId ? 'Paid by ' + esc(memberById(l.doneByUserId)?.name || '?') : ''}</small>
           <div style="height:8px"></div>
           <button class="yellow small" data-act="reopen-list" data-id="${l.id}">REOPEN</button>
         </div>`
      : `<div class="card yellow">
           <h3>💶 FINISHED SHOPPING?</h3>
           <small class="muted">Type what it actually cost. It becomes a paid bill in the calendar, in the name of whoever did it.</small>
           <label>Total spent</label>
           <input id="l-actual" inputmode="decimal" value="${spent ? centsToInput(spent) : ''}" />
           <button class="mint" data-act="complete-list" data-id="${l.id}">DONE, THIS IS THE COST</button>
         </div>`}

    <div class="row">
      <button class="small" data-act="edit-list" data-id="${l.id}">EDIT</button>
      <button class="coral small" data-act="del-list" data-id="${l.id}">DELETE</button>
      <button class="small grow" data-act="close">CLOSE</button>
    </div>`)
}

// ---------------------------------------------------------------- events

function wire(root = document) {
  root.querySelectorAll('[data-tab]').forEach((el) => {
    el.onclick = () => { state.view = el.dataset.tab; render() }
  })
  root.querySelectorAll('[data-day]').forEach((el) => {
    if (el.classList.contains('day')) {
      el.onclick = () => { state.selected = fromEpochDay(Number(el.dataset.day)); render() }
      el.ondblclick = () => daySheet(Number(el.dataset.day))
    }
  })
  root.querySelectorAll('[data-bill]').forEach((el) => {
    el.onclick = () => billDetail(el.dataset.bill)
  })
  root.querySelectorAll('[data-list]:not([data-act])').forEach((el) => {
    if (el.classList.contains('card')) el.onclick = () => listDetail(el.dataset.list)
  })
  root.querySelectorAll('[data-note-color]').forEach((el) => {
    el.onclick = () => {
      window.__editingNote.colorIndex = Number(el.dataset.noteColor)
      root.querySelectorAll('[data-note-color]').forEach((o) => { o.style.borderWidth = '2px' })
      el.style.borderWidth = '4px'
    }
  })
  const noteSearch = root.querySelector('[data-live="note-search"]')
  if (noteSearch) {
    noteSearch.oninput = () => {
      state.noteSearch = noteSearch.value
      render()
      const again = document.querySelector('[data-live="note-search"]')
      if (again) { again.focus(); again.setSelectionRange(again.value.length, again.value.length) }
    }
  }
  root.querySelectorAll('[data-color]').forEach((el) => {
    el.onclick = () => {
      window.__editing.colorIndex = Number(el.dataset.color)
      root.querySelectorAll('[data-color]').forEach((o) => { o.style.borderWidth = '2px' })
      el.style.borderWidth = '4px'
    }
  })
  root.querySelectorAll('[data-act]').forEach((el) => { el.onclick = (e) => handle(el.dataset.act, el, e) })

  const planToggle = root.querySelector('#f-plan')
  if (planToggle) {
    planToggle.onchange = () => {
      const box = document.getElementById('plan-box')
      box.classList.toggle('hidden', !planToggle.checked)
      if (planToggle.checked && !document.querySelectorAll('.plan-amount').length) {
        document.getElementById('plan-rows').innerHTML =
          planRowsHtml(Array(12).fill(document.getElementById('f-amount').value || '0'))
      }
    }
  }
  root.querySelectorAll('input[type=file]').forEach((el) => {
    el.onchange = async () => {
      const file = el.files[0]
      if (!file) return
      try {
        await uploadFile(file, {
          paymentId: el.dataset.payment || null,
          itemId: el.dataset.item || null,
          noteId: el.dataset.note || null,
          day: el.dataset.day ? fromEpochDay(Number(el.dataset.day)) : null,
          isReceipt: el.dataset.receipt === '1'
        })
        toast('Attached')
        const openBill = el.dataset.payment
        const openDay = el.dataset.day
        const openItem = el.dataset.item
        const openNote = el.dataset.note
        const listId = openItem
          ? state.data.shoppingItems.find((i) => i.id === openItem)?.listId
          : null
        closeModals()
        if (openBill) billDetail(openBill)
        else if (openDay) daySheet(Number(openDay))
        else if (listId) listDetail(listId)
        else if (openNote) {
          const note = state.data.notes.find((x) => x.id === openNote)
          if (note) noteEditor(note)
        }
      } catch (err) { toast(err.message) }
    }
  })
  const recurrenceSelect = root.querySelector('#f-recurrence')
  if (recurrenceSelect) {
    recurrenceSelect.onchange = () => {
      const wrap = document.getElementById('until-wrap')
      if (wrap) wrap.classList.toggle('hidden', recurrenceSelect.value === 'NONE')
      const planCard = document.getElementById('plan-card')
      if (planCard && recurrenceSelect.value === 'NONE') planCard.style.display = 'none'
      else if (planCard && window.__editing?.kind !== 'APPOINTMENT') planCard.style.display = ''
    }
  }
  const planCount = root.querySelector('[data-live="plan-count"]')
  if (planCount) {
    // the rows follow the number straight away, no need to press Fill
    planCount.oninput = () => {
      const button = document.querySelector('[data-act="plan-fill"]')
      if (button) handle('plan-fill', button)
      planCount.focus()
    }
  }
  const newItemInput = root.querySelector('#new-item')
  if (newItemInput) {
    newItemInput.onkeydown = (event) => {
      if (event.key !== 'Enter') return
      event.preventDefault()
      const button = document.querySelector('[data-act="add-item"]')
      if (button) handle('add-item', button)
    }
  }
  root.querySelectorAll('.item-price').forEach((el) => {
    el.onchange = () => {
      const item = state.data.shoppingItems.find((i) => i.id === el.dataset.id)
      if (!item) return
      item.priceCents = parseAmount(el.value)
      touch('shoppingItems', item)
    }
  })
  root.querySelectorAll('.file [data-act="del-file"]').forEach((el) => {
    el.onclick = (e) => { e.stopPropagation(); handle('del-file', el, e) }
  })
  root.querySelectorAll('.file').forEach((el) => {
    el.onclick = () => {
      const a = state.data.attachments.find((x) => x.id === el.dataset.file)
      if (a) window.open(fileUrl(a), '_blank')
    }
  })
}

async function handle(act, el) {
  const id = el.dataset.id
  switch (act) {
    case 'login': return doAuth('login')
    case 'register': return doAuth('register')
    case 'logout': return logout()
    case 'close': return closeModals()
    case 'peek': {
      const field = document.getElementById('auth-password')
      const shown = field.type === 'text'
      field.type = shown ? 'password' : 'text'
      el.textContent = shown ? 'show' : 'hide'
      return
    }
    case 'kind': {
      const p = window.__editing
      p.kind = el.dataset.kind
      const appt = p.kind === 'APPOINTMENT'
      const income = p.kind === 'INCOME'
      const reminder = p.kind === 'REMINDER'
      const ownerLabel = document.getElementById('owner-label')
      if (ownerLabel) {
        ownerLabel.textContent =
          income ? 'Who receives it' : reminder ? 'Who has to do it' : 'Who pays it'
      }
      const amountWrap = document.getElementById('amount-wrap')
      if (amountWrap) amountWrap.classList.toggle('hidden', reminder)
      const dateLabel = document.getElementById('date-label')
      if (dateLabel) dateLabel.textContent = reminder ? 'Complete by' : 'Day'
      const colours = {
        BILL: 'var(--yellow)', APPOINTMENT: 'var(--aqua)',
        INCOME: 'var(--mint)', REMINDER: 'var(--tangerine)'
      }
      document.querySelectorAll('[data-act="kind"]').forEach((b) => {
        const on = b.dataset.kind === p.kind
        b.classList.toggle('on', on)
        b.style.background = on ? colours[p.kind] : 'var(--paper)'
      })
      document.getElementById('where-wrap').classList.toggle('hidden', !appt)
      document.getElementById('receipt-wrap').classList.toggle('hidden', appt)
      const planCard = document.getElementById('plan-card')
      if (planCard) planCard.style.display = appt ? 'none' : ''
      if (appt || income || reminder) document.getElementById('f-receipt').checked = false
      const receiptWrap = document.getElementById('receipt-wrap')
      if (receiptWrap) receiptWrap.classList.toggle('hidden', appt || income || reminder)
      return
    }

    case 'prev-month': state.month = new Date(state.month.getFullYear(), state.month.getMonth() - 1, 1); return render()
    case 'next-month': state.month = new Date(state.month.getFullYear(), state.month.getMonth() + 1, 1); return render()
    case 'today': state.month = startOfMonth(new Date()); state.selected = today(); return render()
    case 'open-day': return daySheet(Number(el.dataset.day))

    case 'new-bill': {
      const day = el.dataset.day ? fromEpochDay(Number(el.dataset.day)) : state.selected
      closeModals()
      return billEditor(null, day)
    }
    case 'edit-bill': {
      const p = state.data.payments.find((x) => x.id === id)
      closeModals()
      return billEditor(p)
    }
    case 'save-bill': return saveBill(id)
    case 'pay': {
      const p = state.data.payments.find((x) => x.id === id)
      if (markPaid(p)) { closeModals(); toast('Closed 🎉'); render() }
      return
    }
    case 'reopen': {
      const p = state.data.payments.find((x) => x.id === id)
      p.status = 'PENDING'; p.paidAt = null; p.paidAmountCents = null
      touch('payments', p); closeModals(); return render()
    }
    case 'del-bill': {
      const p = state.data.payments.find((x) => x.id === id)
      if (!p) return
      const series = alive(state.data.payments).filter((x) => x.seriesId === p.seriesId)

      // An installment plan is one agreement: a single instalment cannot be dropped alone.
      if (p.installmentCount > 0) {
        if (!confirm(
          `"${p.title}" is a plan of ${p.installmentCount} installments.

` +
          `Deleting it removes all ${series.length} of them, paid ones included.`
        )) return
        series.forEach((row) => softDelete('payments', row))
        closeModals(); return render()
      }

      if (p.recurrence !== 'NONE' && series.length > 1) {
        const all = confirm(
          `"${p.title}" repeats (${series.length} entries).

` +
          `OK deletes the whole series, Cancel deletes only this one.`
        )
        if (all) series.forEach((row) => softDelete('payments', row))
        else softDelete('payments', p)
        closeModals(); return render()
      }

      if (!confirm('Delete this one?')) return
      softDelete('payments', p); closeModals(); return render()
    }
    case 'del-file': {
      const a = state.data.attachments.find((x) => x.id === id)
      try { await api(`/api/attachments/${id}`, { method: 'DELETE' }) } catch {}
      if (a) a.deletedAt = Date.now()
      persist(); closeModals(); return render()
    }
    case 'save-note': {
      const ed = Number(el.dataset.day)
      const text = document.getElementById('day-note').value
      let note = state.data.dayNotes.find((n) => n.epochDay === ed)
      if (!note) {
        note = { id: uuid(), epochDay: ed, text, createdAt: Date.now(), updatedAt: Date.now(), deletedAt: null }
        state.data.dayNotes.push(note)
      } else note.text = text
      touch('dayNotes', note)
      toast('Saved'); closeModals(); return render()
    }

    case 'plan-fill': {
      const n = Math.max(1, Math.min(120, Number(document.getElementById('f-plan-count').value) || 12))
      const base = document.getElementById('f-amount').value || '0'
      const old = currentPlanValues()
      document.getElementById('plan-rows').innerHTML =
        planRowsHtml(Array.from({ length: n }, (_, i) => old[i] ?? base))
      return
    }
    case 'plan-split': {
      const total = parseAmount(document.getElementById('f-plan-total').value)
      const n = Math.max(1, Math.min(120, Number(document.getElementById('f-plan-count').value) || 12))
      if (total == null) return toast('Type a total first')
      const each = Math.floor(total / n)
      const values = Array.from({ length: n }, (_, i) => centsToInput(i === n - 1 ? total - each * (n - 1) : each))
      document.getElementById('plan-rows').innerHTML = planRowsHtml(values)
      return
    }

    case 'new-list': closeModals(); return listEditor(null)
    case 'new-note': closeModals(); return noteEditor(null)
    case 'open-note': {
      const note = state.data.notes.find((x) => x.id === id)
      if (note) noteEditor(note)
      return
    }
    case 'store-note': {
      const n = window.__editingNote
      Object.assign(n, {
        title: document.getElementById('n-title').value.trim(),
        category: document.getElementById('n-category').value.trim(),
        body: document.getElementById('n-body').value,
        pinned: document.getElementById('n-pinned').checked,
        ownerUserId: document.getElementById('n-owner').value || null,
        visibility: document.getElementById('n-private').checked ? 'PRIVATE' : 'SHARED'
      })
      if (!n.title && !n.body) return toast('Write something first')
      if (!id) state.data.notes.push(n)
      touch('notes', n)

      const shared = window.__pendingShareFiles || []
      if (shared.length) {
        window.__pendingShareFiles = []
        toast(`Saving ${shared.length} file(s)…`)
        for (const file of shared) {
          try { await uploadFile(file, { noteId: n.id }) } catch (e) { toast(e.message) }
        }
      }
      closeModals(); toast('Saved'); return render()
    }
    case 'del-note': {
      const note = state.data.notes.find((x) => x.id === id)
      if (!note || !confirm(`Delete "${note.title || 'this note'}"?`)) return
      note.deletedAt = Date.now()
      touch('notes', note)
      closeModals(); return render()
    }
    case 'note-cat': {
      state.noteCategory = el.dataset.cat || ''
      return render()
    }
    case 'record-audio': return toggleRecording(id)
    case 'fetch-link': return fetchLinkIntoNote(id)
    case 'edit-list': { const l = state.data.shoppingLists.find((x) => x.id === id); closeModals(); return listEditor(l) }
    case 'save-list': return saveList(id)
    case 'del-list': {
      const l = state.data.shoppingLists.find((x) => x.id === id)
      if (!confirm('Delete this list?')) return
      softDelete('shoppingLists', l); closeModals(); return render()
    }
    case 'pick-category': {
      const field = document.getElementById('f-category')
      if (!field) return
      const value = el.dataset.value
      field.value = field.value.trim().toLowerCase() === value.toLowerCase() ? '' : value
      return
    }
    case 'clear-until': {
      const field = document.getElementById('f-until')
      if (field) field.value = ''
      return
    }
    case 'stop-series': {
      const p = state.data.payments.find((x) => x.id === id)
      if (!p) return
      const later = seriesOf(p.seriesId).filter((r) => r.dueDate > p.dueDate && isOpen(r))
      if (!confirm(
        `Stop "${p.title}" after ${dayLabel(fromEpochDay(p.dueDate))}?

` +
        `${later.length} future entr${later.length === 1 ? 'y' : 'ies'} will be removed. ` +
        `Everything up to that date, paid or not, stays.`
      )) return
      stopSeriesAt(p)
      closeModals(); toast('Series closed'); return render()
    }
    case 'extend-series': {
      const p = state.data.payments.find((x) => x.id === id)
      if (!p) return
      const rows = seriesOf(p.seriesId)
      const last = rows[rows.length - 1]
      const suggestion = inputDate(fromEpochDay(last.dueDate + 365))
      const answer = prompt(
        `Repeat "${p.title}" until which date? (YYYY-MM-DD)
` +
        `Now it ends on ${dayLabel(fromEpochDay(last.dueDate))}.`,
        suggestion
      )
      if (!answer) return
      const end = epochDay(new Date(answer))
      if (!Number.isFinite(end)) return toast('Date not understood')
      if (end <= last.dueDate) return toast('Pick a date after the last one')
      const added = extendSeriesTo(p, end)
      closeModals(); toast(`${added} more added`); return render()
    }
    case 'toggle-month': {
      const key = el.dataset.key
      if (state.openMonths.has(key)) state.openMonths.delete(key)
      else state.openMonths.add(key)
      return render()
    }
    case 'toggle-section': {
      const key = el.dataset.key
      if (state.openSections.has(key)) state.openSections.delete(key)
      else state.openSections.add(key)
      return render()
    }
    case 'bills-filter': {
      state.billsFilter = el.dataset.filter
      return render()
    }
    case 'bills-owner': {
      state.billsOwner = el.dataset.owner || ''
      return render()
    }
    case 'open-photo': {
      const photo = state.data.attachments.find((a) => a.id === id)
      if (photo) window.open(fileUrl(photo), '_blank')
      return
    }
    case 'quick-item': {
      const listId = el.dataset.list
      const item = {
        id: uuid(), listId, text: el.dataset.text, quantity: '', checked: false,
        priceCents: null, sortIndex: itemsOf(listId).length,
        createdAt: Date.now(), updatedAt: Date.now(), deletedAt: null
      }
      state.data.shoppingItems.push(item)
      touch('shoppingItems', item)
      closeModals(); return listDetail(listId)
    }
    case 'add-item': {
      const input = document.getElementById('new-item')
      const text = input.value.trim()
      if (!text) return
      const item = {
        id: uuid(), listId: el.dataset.list, text, quantity: '', checked: false,
        priceCents: null, sortIndex: itemsOf(el.dataset.list).length,
        createdAt: Date.now(), updatedAt: Date.now(), deletedAt: null
      }
      state.data.shoppingItems.push(item)
      touch('shoppingItems', item)
      const listId = el.dataset.list
      closeModals()
      listDetail(listId)
      // straight back into the field so the next product can just be typed
      const field = document.getElementById('new-item')
      if (field) { field.value = ''; field.focus() }
      return
    }
    case 'check-item': {
      const item = state.data.shoppingItems.find((i) => i.id === id)
      item.checked = !item.checked
      touch('shoppingItems', item)
      const listId = item.listId
      closeModals(); return listDetail(listId)
    }
    case 'del-item': {
      const item = state.data.shoppingItems.find((i) => i.id === id)
      const listId = item.listId
      softDelete('shoppingItems', item)
      closeModals(); return listDetail(listId)
    }
    case 'complete-list': return completeList(id)
    case 'reopen-list': {
      const l = state.data.shoppingLists.find((x) => x.id === id)
      l.status = 'OPEN'; l.doneAt = null; l.actualCents = null
      touch('shoppingLists', l); closeModals(); return render()
    }

    case 'switch-cal': case 'pick-cal': {
      if (el.dataset.cal) { selectCalendar(el.dataset.cal); return }
      if (state.calendars.length < 2) { state.view = 'setup'; return render() }
      const i = state.calendars.findIndex((c) => c.id === state.calendarId)
      selectCalendar(state.calendars[(i + 1) % state.calendars.length].id)
      return
    }
    case 'save-me': {
      await api('/api/me', { method: 'PATCH', body: JSON.stringify({ name: document.getElementById('me-name').value }) })
      await refreshMe(); toast('Saved'); return render()
    }
    case 'save-cal': {
      await api(`/api/calendars/${state.calendarId}`, {
        method: 'PATCH',
        body: JSON.stringify({
          name: document.getElementById('cal-name').value,
          currency: document.getElementById('cal-currency').value
        })
      })
      await refreshMe(); toast('Saved'); return render()
    }
    case 'rotate-code': {
      await api(`/api/calendars/${state.calendarId}/rotate-code`, { method: 'POST' })
      await refreshMe(); return render()
    }
    case 'kick': {
      await api(`/api/calendars/${state.calendarId}/members/${el.dataset.user}`, { method: 'DELETE' })
      await refreshMe(); return render()
    }
    case 'join': {
      const code = document.getElementById('join-code').value.trim().toUpperCase()
      try {
        const cal = await api('/api/calendars/join', { method: 'POST', body: JSON.stringify({ code }) })
        await refreshMe(); selectCalendar(cal.id); toast('Joined ' + cal.name)
      } catch (e) { toast(e.message) }
      return
    }
    case 'delete-cal': {
      const cal = calendar()
      if (!cal) return
      // destructive and shared: warn first, then make them type the name
      if (!confirm(
        `Delete "${cal.name}"?

Every bill, appointment, receipt, file and shopping list in it ` +
        `disappears for you and for everyone you shared it with. There is no undo.`
      )) return
      const typed = prompt(`Type the calendar name to confirm:`, '')
      if (!typed || typed.trim().toLowerCase() !== cal.name.trim().toLowerCase()) {
        return toast('Name did not match, nothing deleted')
      }
      try {
        await api(`/api/calendars/${cal.id}`, { method: 'DELETE' })
        localStorage.removeItem(LS.data(cal.id))
        localStorage.removeItem(LS.since(cal.id))
        localStorage.removeItem(LS.dirty(cal.id))
        await refreshMe()
        selectCalendar(state.calendars[0]?.id || '')
        toast('Calendar deleted')
      } catch (e) { toast(e.message) }
      return
    }
    case 'leave-cal': {
      const cal = calendar()
      if (!cal) return
      if (!confirm(`Leave "${cal.name}"? It disappears from your app, the others keep it.`)) return
      try {
        await api(`/api/calendars/${cal.id}/leave`, { method: 'POST', body: JSON.stringify({}) })
        localStorage.removeItem(LS.data(cal.id))
        await refreshMe()
        selectCalendar(state.calendars[0]?.id || '')
        toast('You left the calendar')
      } catch (e) { toast(e.message) }
      return
    }
    case 'reload-all': {
      Object.keys(localStorage)
        .filter((k) => k.startsWith('pp.since.'))
        .forEach((k) => localStorage.removeItem(k))
      toast('Reloading everything…')
      await sync()
      return render()
    }
    case 'create-cal': {
      const name = document.getElementById('new-cal-name').value.trim() || 'Calendar'
      const cal = await api('/api/calendars', { method: 'POST', body: JSON.stringify({ name }) })
      await refreshMe(); selectCalendar(cal.id); return
    }
    default: return
  }
}

function saveBill(existingId) {
  const p = window.__editing
  const title = document.getElementById('f-title').value.trim()
  const cents = parseAmount(document.getElementById('f-amount').value)
  const planOn = document.getElementById('f-plan')?.checked
  const planValues = planOn ? currentPlanValues().map(parseAmount) : null
  const appt = p.kind === 'APPOINTMENT'
  const reminder = p.kind === 'REMINDER'
  if (!title) return toast('Give it a name')
  if (!appt && !reminder && cents == null && !(planValues && planValues.every((v) => v != null))) {
    return toast('Type an amount')
  }

  const [hh, mm] = (document.getElementById('f-time').value || '09:00').split(':').map(Number)
  Object.assign(p, {
    title,
    amountCents: reminder ? 0 : (cents ?? (planValues ? planValues[0] : 0) ?? 0),
    location: document.getElementById('f-location')?.value.trim() || '',
    category: document.getElementById('f-category')?.value.trim() || '',
    dueDate: epochDay(new Date(document.getElementById('f-date').value)),
    dueTimeMinutes: hh * 60 + mm,
    ownerUserId: document.getElementById('f-owner').value || null,
    recurrence: document.getElementById('f-recurrence').value,
    recurrenceEndDate: (() => {
      const raw = document.getElementById('f-until')?.value
      return raw ? epochDay(new Date(raw)) : null
    })(),
    requireReceipt: (appt || reminder) ? false : document.getElementById('f-receipt').checked,
    visibility: document.getElementById('f-private').checked ? 'PRIVATE' : 'SHARED',
    notes: document.getElementById('f-notes').value.trim(),
    currency: calendarCurrency()
  })

  if (existingId) {
    touch('payments', p)
  } else if (planOn && planValues.every((v) => v != null)) {
    createSeries(p, planValues)
  } else {
    createSeries(p, null)
  }
  closeModals()
  toast('Saved')
  render()
}

function saveList(existingId) {
  const l = window.__editingList
  const title = document.getElementById('l-title').value.trim()
  if (!title) return toast('Give it a title')
  Object.assign(l, {
    title,
    assignedToUserId: document.getElementById('l-owner').value || null,
    dueDate: epochDay(new Date(document.getElementById('l-date').value)),
    dueTimeMinutes: (() => {
      const [h, m] = (document.getElementById('l-time').value || '18:00').split(':').map(Number)
      return h * 60 + m
    })(),
    budgetCents: parseAmount(document.getElementById('l-budget').value)
  })
  if (!existingId) state.data.shoppingLists.push(l)
  touch('shoppingLists', l)
  closeModals()
  render()
}

/** The shopper types what it really cost: the list closes and a paid bill appears. */
function completeList(id) {
  const l = state.data.shoppingLists.find((x) => x.id === id)
  const actual = parseAmount(document.getElementById('l-actual').value)
  if (actual == null) return toast('Type what it cost')
  const payer = l.assignedToUserId || state.user?.id || null
  const bill = newPayment({
    title: l.title,
    amountCents: actual,
    dueDate: l.dueDate || epochDay(today()),
    ownerUserId: payer,
    colorIndex: l.colorIndex ?? 2,
    category: 'Shopping',
    status: 'PAID',
    paidAt: Date.now(),
    paidAmountCents: actual,
    paidByUserId: payer,
    requireReceipt: false,
    alarmEnabled: false,
    shoppingListId: l.id,
    visibility: l.visibility
  })
  state.data.payments.push(bill)
  state.dirty.add(bill.id)

  l.status = 'DONE'
  l.actualCents = actual
  l.doneAt = Date.now()
  l.doneByUserId = state.user?.id || null
  l.paymentId = bill.id
  touch('shoppingLists', l)

  closeModals()
  toast('Logged ' + money(actual))
  render()
}

// ---------------------------------------------------------------- boot

window.addEventListener('online', () => { state.online = true; paintSyncDot(); sync().catch(() => {}) })
window.addEventListener('offline', () => { state.online = false; paintSyncDot() })
setInterval(() => { if (state.token && state.online) sync().catch(() => {}) }, 30000)
document.addEventListener('visibilitychange', () => { if (!document.hidden) sync().catch(() => {}) })

/** Picks up whatever another app shared into us and opens a note with it. */
async function collectShare() {
  if (!/[?&]share=/.test(location.search)) return
  history.replaceState({}, '', location.pathname)
  try {
    const cache = await caches.open('payplan-share')
    const metaResponse = await cache.match('/__share/meta')
    if (!metaResponse) return
    const meta = await metaResponse.json()

    const files = []
    for (const entry of meta.files || []) {
      const stored = await cache.match(entry.key)
      if (!stored) continue
      const blob = await stored.blob()
      files.push(new File([blob], entry.name, { type: entry.type || blob.type }))
      await cache.delete(entry.key)
    }
    await cache.delete('/__share/meta')

    const pieces = [meta.text, meta.url].filter(Boolean)
    window.__pendingShareFiles = files
    state.view = 'notes'
    render()
    noteEditor(null)
    const titleField = document.getElementById('n-title')
    const bodyField = document.getElementById('n-body')
    if (titleField) {
      titleField.value = meta.title || (pieces[0] && pieces[0].length <= 60 ? pieces[0] : '')
    }
    if (bodyField) bodyField.value = pieces.join('\n')
    if (files.length) {
      const status = document.getElementById('rec-status')
      if (status) {
        status.innerHTML =
          `<small class="muted">${files.length} shared file(s) will be attached when you save.</small>`
      }
    }
  } catch (e) {
    console.warn('share pickup failed', e)
  }
}

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js?v=15').catch(() => {})
}

if (state.calendarId) loadData()
render()
if (state.token) refreshMe().catch(() => {})
collectShare()
