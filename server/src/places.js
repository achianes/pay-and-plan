import { assertPublicUrl } from './unfurl.js'

const USER_AGENT = 'PayAndPlan/1.0 (https://github.com/achianes/pay-and-plan)'
const NOMINATIM = 'https://nominatim.openstreetmap.org/search'
const TIMEOUT_MS = 12000

// Nominatim asks for at most one request per second; a small queue keeps us polite
let lastCall = 0
async function politely(fn) {
  const wait = Math.max(0, lastCall + 1100 - Date.now())
  if (wait) await new Promise((r) => setTimeout(r, wait))
  lastCall = Date.now()
  return fn()
}

/** A free text address against OpenStreetMap: up to five places with coordinates. */
export async function searchPlaces(query) {
  const q = String(query || '').trim()
  if (q.length < 3) return []
  const url = `${NOMINATIM}?q=${encodeURIComponent(q)}&format=jsonv2&limit=5&addressdetails=0`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const res = await politely(() => fetch(url, {
      signal: controller.signal,
      headers: { 'User-Agent': USER_AGENT, 'Accept-Language': 'it,en' }
    }))
    if (!res.ok) throw new Error(`the map answered ${res.status}`)
    const rows = await res.json()
    return rows.map((r) => ({
      name: String(r.display_name || ''),
      lat: Number(r.lat),
      lon: Number(r.lon)
    })).filter((r) => r.name && Number.isFinite(r.lat) && Number.isFinite(r.lon))
  } finally {
    clearTimeout(timer)
  }
}

const decode = (s) => {
  try { return decodeURIComponent(String(s || '').replace(/\+/g, ' ')) } catch { return String(s || '') }
}

/** "20260904T143000Z" or "20260904T163000" or "20260904" -> { epochDay, minutes } in the server's zone. */
function momentFrom(stamp) {
  const m = String(stamp || '').match(/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/)
  if (!m) return null
  const [, y, mo, d, h, mi, , z] = m
  let date
  if (h == null) {
    date = new Date(Number(y), Number(mo) - 1, Number(d), 9, 0)
  } else if (z) {
    date = new Date(Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi)))
  } else {
    date = new Date(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi))
  }
  const local = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  return {
    epochDay: Math.round((local.getTime() - local.getTimezoneOffset() * 60000) / 86400000),
    minutes: h == null ? 9 * 60 : date.getHours() * 60 + date.getMinutes()
  }
}

/** Google Calendar's "add to calendar" links carry everything in the query string. */
function eventFromTemplateUrl(url) {
  const p = url.searchParams
  const dates = p.get('dates')
  if (!dates && !p.get('text')) return null
  const start = dates ? momentFrom(dates.split('/')[0]) : null
  return {
    title: decode(p.get('text')) || 'Event',
    epochDay: start?.epochDay ?? null,
    minutes: start?.minutes ?? null,
    location: decode(p.get('location')),
    notes: decode(p.get('details'))
  }
}

/**
 * Follows a shared Google Calendar link and tries to recover the event behind it: from the
 * final URL when it is a TEMPLATE link, otherwise from what the page itself gives away.
 * Returns null when nothing usable came back; the caller falls back to the shared text.
 */
export async function resolveEventLink(rawUrl) {
  const url = await assertPublicUrl(rawUrl)
  // a TEMPLATE link already says it all; no need to knock on Google's door
  const upfront = eventFromTemplateUrl(url)
  if (upfront?.epochDay != null) return { ...upfront, source: 'url' }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      redirect: 'follow',
      headers: { 'User-Agent': USER_AGENT, 'Accept-Language': 'it,en', Accept: 'text/html,*/*' }
    })
    let finalUrl = new URL(res.url || url.toString())
    // bounced to the login page: the real address travels in "continue"
    const cont = finalUrl.searchParams.get('continue')
    if (cont) { try { finalUrl = new URL(cont) } catch { /* keep the login url */ } }
    const direct = eventFromTemplateUrl(finalUrl)
    if (direct?.epochDay != null) return { ...direct, source: 'url' }

    const html = (await res.text()).slice(0, 2_000_000)

    // an "add to calendar" link buried in the page is the next best thing
    const template = html.match(/https?:\/\/(?:www\.google\.com|calendar\.google\.com)\/calendar\/(?:render|event|u\/\d+\/r\/eventedit)\?[^"'\s<]*dates=[^"'\s<]*/i)
    if (template) {
      const fromPage = eventFromTemplateUrl(new URL(template[0].replace(/&amp;/g, '&')))
      if (fromPage?.epochDay != null) return { ...fromPage, source: 'page-link' }
    }

    // last resort: a JSON island or a data attribute with an ISO start
    const iso = html.match(/"(?:startDate|dtstart|start_time|startTime)"\s*:\s*"(\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:?\d{2})?)?)"/i)
    const titleTag = html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']*)["']/i)
      || html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)
    if (iso) {
      const stamp = iso[1].replace(/[-:]/g, '').replace(/\+\d{4}$/, '')
      const start = momentFrom(stamp.length === 8 ? stamp : stamp.replace(/(\d{8}T\d{4})(\d{2})?.*/, '$1$2'))
      if (start) {
        return {
          title: (titleTag?.[1] || 'Event').replace(/\s*-\s*Google Calendar\s*$/i, '').trim(),
          epochDay: start.epochDay,
          minutes: start.minutes,
          location: '',
          notes: '',
          source: 'page'
        }
      }
    }
    if (direct) return { ...direct, source: 'url-partial' }
    return null
  } finally {
    clearTimeout(timer)
  }
}
