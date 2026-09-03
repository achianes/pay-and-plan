/* Pay & Plan service worker: shell cached so the app opens offline,
   API calls always go to the network (the app keeps its own local copy). */

const CACHE = 'payplan-v20'
const SHELL = [
  './',
  'index.html',
  'app.js?v=20',
  'styles.css?v=20',
  'manifest.webmanifest?v=20',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/apple-touch-icon.png',
  'fonts/luckiestguy_regular.ttf',
  'fonts/comicneue_regular.ttf',
  'fonts/comicneue_bold.ttf'
]

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  )
})

const SHARE_CACHE = 'payplan-share'

/**
 * Anything shared into the app from elsewhere is parked in a cache, then the app is
 * reopened with ?share=1 and picks it up. A share POST never reaches the network.
 */
async function stashShare(request) {
  const form = await request.formData()
  const cache = await caches.open(SHARE_CACHE)
  const meta = {
    title: form.get('title') || '',
    text: form.get('text') || '',
    url: form.get('url') || '',
    files: []
  }
  const files = form.getAll('files').filter((f) => f && f.size)
  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    const key = `/__share/${Date.now()}-${i}`
    await cache.put(key, new Response(file, {
      headers: { 'Content-Type': file.type || 'application/octet-stream' }
    }))
    meta.files.push({ key, name: file.name || `shared-${i}`, type: file.type || '' })
  }
  await cache.put('/__share/meta', new Response(JSON.stringify(meta), {
    headers: { 'Content-Type': 'application/json' }
  }))
  return Response.redirect('./?share=1', 303)
}

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)

  if (event.request.method === 'POST' && url.pathname.endsWith('/share')) {
    event.respondWith(stashShare(event.request))
    return
  }
  if (url.pathname.startsWith('/api/')) return // never cache data
  if (event.request.method !== 'GET') return

  event.respondWith(
    caches.match(event.request).then((hit) => {
      const fresh = fetch(event.request)
        .then((res) => {
          if (res.ok && url.origin === location.origin) {
            const copy = res.clone()
            caches.open(CACHE).then((c) => c.put(event.request, copy))
          }
          return res
        })
        .catch(() => hit || caches.match('index.html'))
      return hit || fresh
    })
  )
})
