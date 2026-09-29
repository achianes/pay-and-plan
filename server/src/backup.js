/**
 * Nightly backup to Google Drive: the database (a consistent VACUUM INTO copy) and the attachments, as one
 * .tar.gz in the "PayAndPlan Backup" folder of the owner's Drive. The last KEEP copies stay, older ones go.
 *
 * Google access: an OAuth "Desktop app" client (DATA_DIR/gdrive-client.json, as downloaded from the Google
 * Cloud console) with the drive.file scope: the server sees only the files it creates. The one-time consent
 * is given from this machine at http://127.0.0.1:<port>/api/backup/connect; the refresh token is kept in
 * DATA_DIR/gdrive-token.json, encrypted with a key derived from the server secret.
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import zlib from 'node:zlib'
import { DatabaseSync } from 'node:sqlite'
import { config } from './config.js'
import { FILES_DIR } from './db.js'

const FOLDER = 'PayAndPlan Backup'
const KEEP = 30
const HOUR = 3 // local time of the nightly run
const SCOPE = 'https://www.googleapis.com/auth/drive.file'
const CLIENT_FILE = path.join(config.dataDir, 'gdrive-client.json')
const TOKEN_FILE = path.join(config.dataDir, 'gdrive-token.json')
const DB_FILE = path.join(config.dataDir, 'payandplan.sqlite')

const status = { lastRun: null, lastOk: null, lastError: null, lastFile: null }

// ---------------------------------------------------------------- Google OAuth

function client() {
  if (!fs.existsSync(CLIENT_FILE)) return null
  const j = JSON.parse(fs.readFileSync(CLIENT_FILE, 'utf8'))
  const c = j.installed || j.web
  return c ? { id: c.client_id, secret: c.client_secret } : null
}
const tokenKey = () => crypto.createHash('sha256').update('gdrive-token:' + config.jwtSecret).digest()
function saveRefreshToken(token) {
  const iv = crypto.randomBytes(12)
  const c = crypto.createCipheriv('aes-256-gcm', tokenKey(), iv)
  const box = Buffer.concat([iv, c.update(token, 'utf8'), c.final(), c.getAuthTag()])
  fs.writeFileSync(TOKEN_FILE, JSON.stringify({ refresh: box.toString('base64'), savedAt: new Date().toISOString() }), { mode: 0o600 })
}
function refreshToken() {
  if (!fs.existsSync(TOKEN_FILE)) return null
  const box = Buffer.from(JSON.parse(fs.readFileSync(TOKEN_FILE, 'utf8')).refresh, 'base64')
  const d = crypto.createDecipheriv('aes-256-gcm', tokenKey(), box.subarray(0, 12))
  d.setAuthTag(box.subarray(box.length - 16))
  return Buffer.concat([d.update(box.subarray(12, box.length - 16)), d.final()]).toString('utf8')
}
const redirectUri = () => `http://127.0.0.1:${config.port}/api/backup/callback`

let access = { token: null, until: 0 }
async function accessToken() {
  if (access.token && Date.now() < access.until - 60000) return access.token
  const c = client(), r = refreshToken()
  if (!c || !r) throw new Error('Google Drive not connected')
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: c.id, client_secret: c.secret, refresh_token: r, grant_type: 'refresh_token' })
  })
  const j = await res.json()
  if (!res.ok) throw new Error(`Google refused the token: ${j.error || res.status}${j.error === 'invalid_grant' ? ' (connect again)' : ''}`)
  access = { token: j.access_token, until: Date.now() + j.expires_in * 1000 }
  return access.token
}
async function drive(url, opts = {}) {
  const res = await fetch(url, { ...opts, headers: { authorization: `Bearer ${await accessToken()}`, ...(opts.headers || {}) } })
  if (!res.ok) throw new Error(`Drive ${res.status}: ${(await res.text()).slice(0, 200)}`)
  return res.status === 204 ? null : res.json()
}

// ---------------------------------------------------------------- the archive

/** a minimal ustar writer: [{ name, data }] -> gzip */
function tarGz(entries) {
  const blocks = []
  for (const e of entries) {
    const h = Buffer.alloc(512)
    const name = Buffer.from(e.name, 'utf8')
    if (name.length > 100) throw new Error(`name too long for tar: ${e.name}`)
    name.copy(h, 0)
    h.write('0000644\0', 100); h.write('0000000\0', 108); h.write('0000000\0', 116)
    h.write(e.data.length.toString(8).padStart(11, '0') + '\0', 124)
    h.write(Math.floor((e.mtime || Date.now()) / 1000).toString(8).padStart(11, '0') + '\0', 136)
    h.write('        ', 148); h.write('0', 156); h.write('ustar\0' + '00', 257)
    let sum = 0; for (const b of h) sum += b
    h.write(sum.toString(8).padStart(6, '0') + '\0 ', 148)
    blocks.push(h, e.data, Buffer.alloc((512 - (e.data.length % 512)) % 512))
  }
  blocks.push(Buffer.alloc(1024))
  return zlib.gzipSync(Buffer.concat(blocks), { level: 9 })
}

export function archive() {
  const tmp = path.join(os.tmpdir(), `payplan-backup-${process.pid}-${Date.now()}.sqlite`)
  try {
    const src = new DatabaseSync(DB_FILE, { readOnly: true })
    src.exec(`VACUUM INTO '${tmp.replace(/'/g, "''")}'`)
    src.close()
    const entries = [{ name: 'payandplan.sqlite', data: fs.readFileSync(tmp) }]
    if (fs.existsSync(FILES_DIR)) {
      for (const f of fs.readdirSync(FILES_DIR)) {
        const p = path.join(FILES_DIR, f)
        const st = fs.statSync(p)
        if (st.isFile()) entries.push({ name: `files/${f}`, data: fs.readFileSync(p), mtime: st.mtimeMs })
      }
    }
    return { bytes: tarGz(entries), count: entries.length }
  } finally { fs.rmSync(tmp, { force: true }) }
}

// ---------------------------------------------------------------- upload and rotation

async function folderId() {
  const q = encodeURIComponent(`name = '${FOLDER}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false`)
  const found = await drive(`https://www.googleapis.com/drive/v3/files?q=${q}&fields=files(id)`)
  if (found.files?.length) return found.files[0].id
  const made = await drive('https://www.googleapis.com/drive/v3/files?fields=id', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: FOLDER, mimeType: 'application/vnd.google-apps.folder' })
  })
  return made.id
}

async function upload(name, bytes, parent) {
  // resumable: one session, then the bytes in one PUT (a few MB)
  const start = await fetch('https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size', {
    method: 'POST',
    headers: { authorization: `Bearer ${await accessToken()}`, 'content-type': 'application/json', 'x-upload-content-type': 'application/gzip', 'x-upload-content-length': String(bytes.length) },
    body: JSON.stringify({ name, parents: [parent], mimeType: 'application/gzip' })
  })
  if (!start.ok) throw new Error(`Drive upload refused: ${start.status} ${(await start.text()).slice(0, 200)}`)
  const put = await fetch(start.headers.get('location'), { method: 'PUT', headers: { 'content-type': 'application/gzip' }, body: bytes })
  if (!put.ok) throw new Error(`Drive upload failed: ${put.status} ${(await put.text()).slice(0, 200)}`)
  return put.json()
}

async function rotate(parent) {
  const q = encodeURIComponent(`'${parent}' in parents and trashed = false and name contains 'payandplan-'`)
  const list = await drive(`https://www.googleapis.com/drive/v3/files?q=${q}&orderBy=createdTime desc&fields=files(id,name)&pageSize=200`)
  for (const f of (list.files || []).slice(KEEP)) await drive(`https://www.googleapis.com/drive/v3/files/${f.id}`, { method: 'DELETE' })
}

export async function runBackup() {
  status.lastRun = new Date().toISOString()
  try {
    const { bytes, count } = archive()
    const stamp = new Date().toISOString().slice(0, 16).replace('T', '_').replace(':', '-')
    const name = `payandplan-${stamp}.tar.gz`
    const parent = await folderId()
    await upload(name, bytes, parent)
    await rotate(parent)
    Object.assign(status, { lastOk: status.lastRun, lastError: null, lastFile: name })
    console.log(`[backup] ${name}: ${count} item(s), ${(bytes.length / 1e6).toFixed(1)} MB to Google Drive / ${FOLDER}`)
    return status
  } catch (e) {
    status.lastError = e.message
    console.error(`[backup] failed: ${e.message}`)
    throw e
  }
}

// ---------------------------------------------------------------- routes (from this machine only)

const isLocal = (req) => ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress) && !req.headers['cf-connecting-ip']
let pendingState = null

export function mountBackup(app) {
  app.get('/api/backup/status', (req, res) => {
    if (!isLocal(req)) return res.status(403).json({ error: 'from the server machine only' })
    res.json({ clientFile: !!client(), connected: fs.existsSync(TOKEN_FILE), ...status })
  })
  app.get('/api/backup/connect', (req, res) => {
    if (!isLocal(req)) return res.status(403).send('from the server machine only')
    const c = client()
    if (!c) return res.status(400).send(`missing ${CLIENT_FILE}`)
    pendingState = crypto.randomBytes(16).toString('hex')
    const q = new URLSearchParams({ client_id: c.id, redirect_uri: redirectUri(), response_type: 'code', scope: SCOPE, access_type: 'offline', prompt: 'consent', state: pendingState })
    res.redirect(`https://accounts.google.com/o/oauth2/v2/auth?${q}`)
  })
  app.get('/api/backup/callback', async (req, res) => {
    if (!isLocal(req)) return res.status(403).send('from the server machine only')
    if (!pendingState || req.query.state !== pendingState) return res.status(400).send('unexpected answer: start again from /api/backup/connect')
    pendingState = null
    if (req.query.error) return res.status(400).send(`Google said: ${req.query.error}`)
    const c = client()
    const r = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ code: String(req.query.code || ''), client_id: c.id, client_secret: c.secret, redirect_uri: redirectUri(), grant_type: 'authorization_code' })
    })
    const j = await r.json()
    if (!r.ok || !j.refresh_token) return res.status(400).send(`no access: ${j.error || 'no refresh token'}`)
    saveRefreshToken(j.refresh_token)
    access = { token: j.access_token, until: Date.now() + j.expires_in * 1000 }
    console.log('[backup] Google Drive connected')
    try { await runBackup(); res.send(`<p>Google Drive connected. First backup done: ${status.lastFile} in "${FOLDER}".</p>`) } catch (e) { res.send(`<p>Google Drive connected, but the first backup failed: ${e.message}</p>`) }
  })
  app.post('/api/backup/run', async (req, res) => {
    if (!isLocal(req)) return res.status(403).json({ error: 'from the server machine only' })
    try { res.json(await runBackup()) } catch (e) { res.status(500).json({ error: e.message }) }
  })
}

/** once a day at HOUR, when connected; a day missed (machine off) is done at the next start */
export function startBackupRounds() {
  let lastDay = null
  const tick = () => {
    if (!fs.existsSync(TOKEN_FILE)) return
    const d = new Date()
    const day = d.toISOString().slice(0, 10)
    if (lastDay === day || d.getHours() < HOUR) return
    lastDay = day
    runBackup().catch(() => {})
  }
  setInterval(tick, 10 * 60 * 1000).unref()
  setTimeout(tick, 60 * 1000).unref()
}
