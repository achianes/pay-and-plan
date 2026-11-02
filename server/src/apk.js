import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import http from 'node:http'
import crypto from 'node:crypto'
import { config } from './config.js'

/**
 * Hands out the Android build — to the tailnet and to nowhere else.
 *
 * The main server sits behind a public tunnel, so anything mounted on it is on the open
 * internet the moment it exists. This has a listener of its own, bound to this machine's
 * tailnet addresses only: the tunnel points at the other port and the kernel will not
 * even accept a connection here from any other road.
 *
 * An APK is a signed thing that a phone will install, so being careful about who can
 * fetch it is not pedantry.
 */

/** Tailscale's own ranges: 100.64.0.0/10 for IPv4, fd7a:115c:a1e0::/48 for IPv6. */
export function isTailnet(address) {
  if (!address) return false
  const bare = String(address).replace(/^::ffff:/, '').replace(/%.*$/, '')
  const four = bare.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/)
  if (four) {
    const first = Number(four[1])
    const second = Number(four[2])
    return first === 100 && second >= 64 && second <= 127
  }
  return bare.toLowerCase().startsWith('fd7a:115c:a1e0:')
}

/** The addresses this machine answers on inside the tailnet. Empty when it is not on one. */
export function tailnetAddresses() {
  const found = []
  for (const list of Object.values(os.networkInterfaces())) {
    for (const entry of list || []) {
      if (!entry.internal && isTailnet(entry.address)) found.push(entry.address)
    }
  }
  return found
}

/** The build on offer: the newest .apk in the folder, or nothing yet. */
export function latest() {
  const dir = config.apkDir
  if (!dir || !fs.existsSync(dir)) return null
  const files = fs.readdirSync(dir)
    .filter((name) => name.toLowerCase().endsWith('.apk'))
    .map((name) => {
      const full = path.join(dir, name)
      const stat = fs.statSync(full)
      return { name, path: full, bytes: stat.size, builtAt: stat.mtimeMs }
    })
    .sort((a, b) => b.builtAt - a.builtAt)
  return files[0] || null
}

const megabytes = (bytes) => (bytes / 1024 / 1024).toFixed(1)

function page(build) {
  if (!build) {
    return `<!doctype html><meta charset="utf-8"><title>Pay &amp; Plan</title>
<style>body{font:16px system-ui;margin:3rem auto;max-width:34rem;padding:0 1rem}</style>
<h1>Pay &amp; Plan</h1><p>No build has been put here yet.</p>`
  }
  const when = new Date(build.builtAt).toLocaleString('it-IT')
  return `<!doctype html><html lang="it"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pay &amp; Plan — installa</title>
<style>
  :root{color-scheme:light dark}
  body{font:16px/1.5 system-ui,sans-serif;margin:0;min-height:100vh;display:grid;place-items:center;padding:1.5rem}
  .card{max-width:30rem;width:100%;border:3px solid currentColor;border-radius:1rem;padding:1.5rem}
  h1{margin:0 0 .25rem;font-size:1.5rem}
  dl{display:grid;grid-template-columns:auto 1fr;gap:.25rem 1rem;margin:1.25rem 0}
  dt{opacity:.7}
  dd{margin:0;word-break:break-all}
  a.get{display:block;text-align:center;padding:.9rem;border:3px solid currentColor;border-radius:.75rem;
        font-weight:700;text-decoration:none;color:inherit}
  p.small{opacity:.7;font-size:.875rem}
</style>
<div class="card">
  <h1>🗓️ Pay &amp; Plan</h1>
  <p class="small">Solo sul tailnet. Questa pagina non esiste su internet.</p>
  <dl>
    <dt>File</dt><dd>${build.name}</dd>
    <dt>Dimensione</dt><dd>${megabytes(build.bytes)} MB</dd>
    <dt>Compilato</dt><dd>${when}</dd>
    <dt>SHA-256</dt><dd>${build.sha256 || '—'}</dd>
  </dl>
  <a class="get" href="/payandplan.apk">Scarica e installa</a>
  <p class="small">Android chiederà il permesso di installare da questa origine: è la prima volta e basta dirgli sì.</p>
</div></html>`
}

function digest(file) {
  return new Promise((resolve) => {
    const hash = crypto.createHash('sha256')
    fs.createReadStream(file)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('hex')))
      .on('error', () => resolve(''))
  })
}

async function handle(req, res) {
  // the bind already makes this unreachable from elsewhere; this is the belt, in case a
  // future change ever widens the bind by accident
  if (!isTailnet(req.socket?.remoteAddress) || !isTailnet(req.socket?.localAddress)) {
    res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' })
    res.end('Only on the tailnet.\n')
    return
  }

  const url = new URL(req.url, 'http://tailnet')
  const build = latest()

  if (url.pathname === '/payandplan.apk' || url.pathname === '/apk') {
    if (!build) {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
      res.end('No build here yet.\n')
      return
    }
    res.writeHead(200, {
      'content-type': 'application/vnd.android.package-archive',
      'content-length': build.bytes,
      'content-disposition': `attachment; filename="${build.name}"`,
      'cache-control': 'no-store'
    })
    fs.createReadStream(build.path).pipe(res)
    return
  }

  if (url.pathname === '/latest.json') {
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    res.end(JSON.stringify(build
      ? { name: build.name, bytes: build.bytes, builtAt: build.builtAt, sha256: await digest(build.path) }
      : {}))
    return
  }

  if (url.pathname !== '/' && url.pathname !== '/index.html') {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
    res.end('Nothing here.\n')
    return
  }

  if (build) build.sha256 = await digest(build.path)
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
  res.end(page(build))
}

/**
 * Starts the handout, one listener per tailnet address. Says out loud what it did and,
 * when there is no tailnet, that it did nothing: a silence here looks like it worked.
 */
export function startApkHandout() {
  const port = config.apkPort
  if (!port) {
    console.log('[apk] handout off (apkPort is 0)')
    return []
  }
  const addresses = tailnetAddresses()
  if (addresses.length === 0) {
    console.log('[apk] no tailnet address on this machine: the build is not handed out')
    return []
  }
  fs.mkdirSync(config.apkDir, { recursive: true })

  const servers = []
  for (const address of addresses) {
    const server = http.createServer((req, res) => {
      handle(req, res).catch((e) => {
        console.error(`[apk] ${e.message}`)
        if (!res.headersSent) res.writeHead(500)
        res.end()
      })
    })
    server.on('error', (e) => console.error(`[apk] ${address}:${port} — ${e.message}`))
    const host = address.includes(':') ? address.replace(/%.*$/, '') : address
    server.listen(port, host, () => {
      const shown = address.includes(':') ? `[${host}]` : host
      console.log(`[apk] handing out ${config.apkDir} on http://${shown}:${port} (tailnet only)`)
    })
    servers.push(server)
  }
  return servers
}
