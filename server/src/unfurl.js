import dns from 'node:dns/promises'
import net from 'node:net'

const MAX_BYTES = 4 * 1024 * 1024
const TIMEOUT_MS = 15000
const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/125.0 Safari/537.36 PayAndPlan/1.0'

/**
 * The server can reach the whole home network, so a URL handed to it by a client is only
 * fetched once we know it does not point back inside. Public addresses only, http(s) only.
 */
function isPrivateAddress(ip) {
  if (net.isIPv4(ip)) {
    const [a, b] = ip.split('.').map(Number)
    return (
      a === 0 || a === 10 || a === 127 ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 100 && b >= 64 && b <= 127) ||
      a >= 224
    )
  }
  if (net.isIPv6(ip)) {
    const low = ip.toLowerCase()
    if (low === '::1' || low === '::') return true
    if (low.startsWith('fe80') || low.startsWith('fc') || low.startsWith('fd')) return true
    // ::ffff:10.0.0.1 and friends
    const mapped = low.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/)
    if (mapped) return isPrivateAddress(mapped[1])
  }
  return false
}

async function assertPublicUrl(raw) {
  let url
  try {
    url = new URL(raw)
  } catch {
    throw new Error('that does not look like a link')
  }
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('only http and https links')

  const host = url.hostname.replace(/^\[|\]$/g, '')
  if (net.isIP(host)) {
    if (isPrivateAddress(host)) throw new Error('that address is inside the local network')
    return url
  }
  const records = await dns.lookup(host, { all: true }).catch(() => [])
  if (!records.length) throw new Error('cannot resolve that address')
  if (records.some((r) => isPrivateAddress(r.address))) {
    throw new Error('that address is inside the local network')
  }
  return url
}

const decodeEntities = (text) => text
  .replace(/&nbsp;/g, ' ')
  .replace(/&amp;/g, '&')
  .replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>')
  .replace(/&quot;/g, '"')
  .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
  .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCharCode(parseInt(code, 16)))

const meta = (html, property) => {
  const pattern = new RegExp(
    `<meta[^>]+(?:property|name)=["']${property}["'][^>]*content=["']([^"']*)["']`, 'i')
  const alt = new RegExp(
    `<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']${property}["']`, 'i')
  const found = html.match(pattern) || html.match(alt)
  return found ? decodeEntities(found[1]) : ''
}

/** Prefers the real content region, so menus and footers stay out of the note. */
function mainRegion(html) {
  const candidates = [
    /<article[^>]*>([\s\S]*?)<\/article>/i,
    /<main[^>]*>([\s\S]*?)<\/main>/i,
    /<div[^>]+id=["']mw-content-text["'][^>]*>([\s\S]*)/i,
    /<div[^>]+(?:id|class)=["'][^"']*(?:content|post|entry)[^"']*["'][^>]*>([\s\S]*?)<\/div>/i
  ]
  for (const pattern of candidates) {
    const found = html.match(pattern)
    if (found && found[1] && found[1].length > 400) return found[1]
  }
  return html
}

// navigation furniture that survives the tag stripping on most sites
const NOISE = new RegExp(
  '^(jump to content|main menu|move to sidebar|hide|show|navigation|toggle|search|edit|' +
  'contents|references|external links|see also|categories?:|retrieved from|privacy policy|' +
  'cookies?|sign in|log in|subscribe|share|print|download|menu|skip to main content)$', 'i')

function readableText(html) {
  const body = mainRegion(html)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<\/(p|div|section|article|li|h[1-6]|tr|br)>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')

  return decodeEntities(body)
    .replace(/[ \t\u00a0]+/g, ' ')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line && !NOISE.test(line))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/** Walks any JSON blob and collects what looks like conversation turns. */
function harvestConversation(node, out = [], depth = 0) {
  if (!node || depth > 40 || out.length > 400) return out
  if (Array.isArray(node)) {
    node.forEach((child) => harvestConversation(child, out, depth + 1))
    return out
  }
  if (typeof node !== 'object') return out

  const role = node.author?.role || node.role
  const parts = node.content?.parts || node.parts
  if (role && Array.isArray(parts)) {
    const text = parts
      .map((p) => (typeof p === 'string' ? p : p?.text || ''))
      .filter(Boolean)
      .join('\n')
      .trim()
    if (text) out.push({ role, text })
  }
  for (const value of Object.values(node)) harvestConversation(value, out, depth + 1)
  return out
}

/**
 * Shared chat pages (ChatGPT and friends) render client side, so the readable words live
 * in a JSON island rather than in the markup. Pull the turns out of it when we can.
 */
function conversationFrom(html) {
  const blocks = [...html.matchAll(
    /<script[^>]*type=["']application\/json["'][^>]*>([\s\S]*?)<\/script>/gi)]
  const inline = [...html.matchAll(
    /<script[^>]*id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/gi)]

  const seen = new Set()
  const turns = []
  for (const [, blob] of [...inline, ...blocks]) {
    let parsed
    try {
      parsed = JSON.parse(blob.trim())
    } catch {
      continue
    }
    for (const turn of harvestConversation(parsed)) {
      const key = turn.role + '|' + turn.text.slice(0, 200)
      if (seen.has(key)) continue
      seen.add(key)
      turns.push(turn)
    }
  }
  if (turns.length < 2) return ''

  const label = (role) => {
    if (/user|human/i.test(role)) return 'You'
    if (/assistant|model|ai/i.test(role)) return 'Assistant'
    if (/system|tool/i.test(role)) return null   // plumbing, not conversation
    return role
  }
  return turns
    .map((t) => ({ who: label(t.role), text: t.text }))
    .filter((t) => t.who)
    .map((t) => `${t.who}:\n${t.text}`)
    .join('\n\n---\n\n')
}

function imagesFrom(html, base) {
  const found = new Set()
  const push = (src) => {
    if (!src || src.startsWith('data:')) return
    try {
      const absolute = new URL(src, base).toString()
      if (/^https?:/.test(absolute)) found.add(absolute)
    } catch { /* a malformed src is not worth a failure */ }
  }
  push(meta(html, 'og:image'))
  for (const [, src] of html.matchAll(/<img[^>]+src=["']([^"']+)["']/gi)) push(src)
  return [...found].slice(0, 12)
}

/** Fetches a public page and returns what is worth keeping in a note. */
export async function unfurl(rawUrl) {
  const url = await assertPublicUrl(rawUrl)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      redirect: 'follow',
      headers: { 'User-Agent': USER_AGENT, Accept: 'text/html,*/*' }
    })
    if (!response.ok) throw new Error(`the page answered ${response.status}`)

    const type = response.headers.get('content-type') || ''
    const buffer = Buffer.from(await response.arrayBuffer())
    if (buffer.length > MAX_BYTES) throw new Error('that page is too big')
    const html = buffer.toString('utf8')

    if (!type.includes('html')) {
      return { url: url.toString(), title: url.pathname.split('/').pop() || url.hostname,
        text: type.startsWith('text/') ? html.slice(0, 20000) : '', images: [], kind: 'file' }
    }

    const titleTag = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)
    const title = meta(html, 'og:title') || (titleTag ? decodeEntities(titleTag[1]).trim() : '')
    const conversation = conversationFrom(html)
    const description = meta(html, 'og:description')
    const body = conversation || readableText(html).slice(0, 40000)

    return {
      url: url.toString(),
      title: title || url.hostname,
      text: [description, body].filter(Boolean).join('\n\n').slice(0, 60000),
      images: imagesFrom(html, url.toString()),
      kind: conversation ? 'conversation' : 'page'
    }
  } finally {
    clearTimeout(timer)
  }
}

/** Downloads one image, refusing anything that is not actually an image. */
export async function fetchImage(rawUrl) {
  const url = await assertPublicUrl(rawUrl)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: { 'User-Agent': USER_AGENT }
    })
    if (!response.ok) return null
    const type = response.headers.get('content-type') || ''
    if (!type.startsWith('image/')) return null
    const buffer = Buffer.from(await response.arrayBuffer())
    if (!buffer.length || buffer.length > MAX_BYTES) return null
    const name = decodeURIComponent(url.pathname.split('/').pop() || 'image')
    return { buffer, mime: type.split(';')[0], name: name.includes('.') ? name : `${name}.jpg` }
  } catch {
    return null
  } finally {
    clearTimeout(timer)
  }
}
