import crypto from 'node:crypto'
import fs from 'node:fs'
import { config } from './config.js'

/**
 * Enable Banking: the bank itself tells us what was spent, instead of the phone reading over
 * the shoulder of a notification. The notifications stay — they arrive in seconds and this
 * does not — but only this knows about the card payment made while the phone was in a bag.
 *
 * Everything here lives on the server and nowhere else. The private key signs a short-lived
 * assertion for every call; the phone only ever sees the movements that come out the far end.
 */

const BASE = 'https://api.enablebanking.com'
const TIMEOUT_MS = 30000

/** Whether an application id and a key are in place; without them the feature stays off. */
export function configured() {
  return Boolean(config.enableBankingAppId && readKey())
}

let cachedKey = null
function readKey() {
  if (cachedKey !== null) return cachedKey
  const at = config.enableBankingKeyPath
  cachedKey = at && fs.existsSync(at) ? fs.readFileSync(at, 'utf8') : ''
  return cachedKey
}

/**
 * The assertion every call carries. It is signed with our own key and says who we are; it
 * is not a token anybody handed us, so it is minted fresh and kept short.
 */
function assertion(seconds = 3600) {
  const key = readKey()
  if (!key) throw new Error('no Enable Banking private key')
  const now = Math.floor(Date.now() / 1000)
  const head = { typ: 'JWT', alg: 'RS256', kid: config.enableBankingAppId }
  const body = {
    iss: 'enablebanking.com',
    aud: 'api.enablebanking.com',
    iat: now,
    exp: now + Math.min(seconds, 86400)
  }
  const part = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const signing = `${part(head)}.${part(body)}`
  const signature = crypto.createSign('RSA-SHA256').update(signing).sign(key).toString('base64url')
  return `${signing}.${signature}`
}

async function call(path, { method = 'GET', body, psu } = {}) {
  const headers = {
    Authorization: `Bearer ${assertion()}`,
    Accept: 'application/json',
    ...(body ? { 'Content-Type': 'application/json' } : {}),
    // the bank may insist on knowing it is a person asking, not a machine on its own
    ...(psu?.ip ? { 'Psu-Ip-Address': psu.ip } : {}),
    ...(psu?.agent ? { 'Psu-User-Agent': psu.agent } : {})
  }
  const stop = AbortSignal.timeout(TIMEOUT_MS)
  const res = await fetch(BASE + path, {
    method,
    headers,
    signal: stop,
    ...(body ? { body: JSON.stringify(body) } : {})
  })
  const text = await res.text()
  let parsed = null
  try { parsed = text ? JSON.parse(text) : null } catch { /* the error text is the answer */ }
  if (!res.ok) {
    const said = parsed?.message || parsed?.error || text.slice(0, 200)
    const error = new Error(`Enable Banking ${res.status}: ${said}`)
    error.status = res.status
    throw error
  }
  return parsed
}

/** The banks of a country this application may talk to. */
export async function banks(country = 'IT') {
  const out = await call(`/aspsps?country=${encodeURIComponent(country)}`)
  return (out?.aspsps || []).map((a) => ({
    name: a.name,
    country: a.country,
    logo: a.logo || '',
    psuTypes: a.psu_types || ['personal'],
    beta: Boolean(a.beta)
  }))
}

/**
 * Where to send the owner so their bank can ask them, in their own bank's app, whether we
 * may read the account. Nothing is granted here: the answer comes back to the redirect.
 */
export async function startAuth({ bank, country, psuType = 'personal', redirectUrl, state, days = 90 }) {
  const until = new Date(Date.now() + days * 24 * 60 * 60 * 1000)
  const out = await call('/auth', {
    method: 'POST',
    body: {
      access: { valid_until: until.toISOString().replace(/\.\d{3}Z$/, '.000+00:00') },
      aspsp: { name: bank, country },
      state,
      redirect_url: redirectUrl,
      psu_type: psuType
    }
  })
  return { url: out?.url, authorizationId: out?.authorization_id, validUntil: until.getTime() }
}

/** The code the bank sent back becomes a session, and the session names the accounts. */
export async function completeAuth(code) {
  const out = await call('/sessions', { method: 'POST', body: { code } })
  return {
    sessionId: out?.session_id,
    aspsp: out?.aspsp?.name || '',
    country: out?.aspsp?.country || '',
    validUntil: out?.access?.valid_until ? Date.parse(out.access.valid_until) : 0,
    accounts: (out?.accounts || []).map((a) => ({
      uid: a.uid,
      name: a.name || a.product || '',
      iban: a.account_id?.iban || '',
      currency: a.currency || 'EUR'
    }))
  }
}

export async function session(sessionId) {
  return call(`/sessions/${encodeURIComponent(sessionId)}`)
}

export async function dropSession(sessionId) {
  return call(`/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
}

/**
 * Everything the account did since a date, however many pages the bank wants to use. Only
 * what the bank calls booked: a payment still pending can change its figure or vanish.
 */
export async function transactions(accountUid, since, { psu } = {}) {
  const from = new Date(since).toISOString().slice(0, 10)
  let path = `/accounts/${encodeURIComponent(accountUid)}/transactions?date_from=${from}`
  const all = []
  for (let page = 0; page < 20; page++) {
    const out = await call(path, { psu })
    all.push(...(out?.transactions || []))
    const next = out?.continuation_key
    if (!next) break
    path = `/accounts/${encodeURIComponent(accountUid)}/transactions` +
      `?date_from=${from}&continuation_key=${encodeURIComponent(next)}`
  }
  return all
}

/**
 * A bank's transaction, said the way the rest of this house says it. The name is the other
 * party — the shop for money going out, the payer for money coming in — because that is
 * what somebody recognises in a list, not the bank's own wording.
 */
export function plainMovement(t, accountUid) {
  const out = t.credit_debit_indicator === 'DBIT'
  const cents = Math.round(Math.abs(Number(t.transaction_amount?.amount || 0)) * 100)
  if (!cents) return null

  const party = (out ? t.creditor?.name : t.debtor?.name) || ''
  const said = (t.remittance_information || []).filter(Boolean).join(' ').trim()
  const when = t.transaction_date || t.booking_date || t.value_date

  return {
    // the bank's own reference is not unique across accounts, so the account goes in too
    externalId: fingerprint(accountUid, t),
    name: party || said.slice(0, 60) || t.bank_transaction_code?.description || 'Payment',
    text: said,
    amountCents: cents,
    kind: out ? 'OUT' : 'IN',
    happenedAt: when ? Date.parse(`${when}T12:00:00Z`) : Date.now(),
    currency: t.transaction_amount?.currency || 'EUR',
    status: t.status || 'OTHR'
  }
}

function fingerprint(accountUid, t) {
  const parts = [
    accountUid,
    t.entry_reference || t.transaction_id || '',
    t.booking_date || t.value_date || t.transaction_date || '',
    t.transaction_amount?.amount || '',
    t.credit_debit_indicator || '',
    (t.creditor?.name || t.debtor?.name || '') + (t.remittance_information || []).join('|')
  ]
  return crypto.createHash('sha1').update(parts.join('\u0000')).digest('hex').slice(0, 32)
}
