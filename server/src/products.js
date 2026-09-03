import { fetchImage } from './unfurl.js'

// Open Food Facts, Italian edition: the open database of what is on supermarket shelves
const OFF_BASE = 'https://it.openfoodfacts.org/api/v2/product/'
const FIELDS = 'product_name,product_name_it,brands,quantity,image_front_small_url,image_front_thumb_url'
const USER_AGENT = 'PayAndPlan/1.0 (https://github.com/achianes/pay-and-plan)'
const TIMEOUT_MS = 12000

const clean = (s) => String(s || '').replace(/\s+/g, ' ').trim()

/**
 * Looks a barcode up and returns what a shopping list wants to know: a readable name,
 * the brand, the pack size and a small picture. Null when the code is unknown.
 */
export async function lookupProduct(rawCode) {
  const code = String(rawCode || '').replace(/\D/g, '')
  if (code.length < 8 || code.length > 14) throw new Error('that does not look like a product barcode')

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  let json
  try {
    const res = await fetch(`${OFF_BASE}${code}.json?fields=${FIELDS}`, {
      signal: controller.signal,
      headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' }
    })
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`the product database answered ${res.status}`)
    json = await res.json()
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('the product database took too long')
    throw e
  } finally {
    clearTimeout(timer)
  }
  if (json?.status !== 1 || !json.product) return null

  const p = json.product
  const raw = clean(p.product_name_it) || clean(p.product_name)
  const name = raw ? raw.charAt(0).toUpperCase() + raw.slice(1) : ''   // "bueno" -> "Bueno"
  const brand = clean(p.brands).split(',')[0].trim()
  const quantity = clean(p.quantity)
  if (!name && !brand) return null

  // "Nutella · Ferrero · 400 g", without repeating the brand when the name already has it
  const label = [
    name || brand,
    brand && !name.toLowerCase().includes(brand.toLowerCase()) ? brand : '',
    quantity
  ].filter(Boolean).join(' · ')

  return {
    barcode: code,
    name,
    brand,
    quantity,
    label,
    imageUrl: p.image_front_small_url || p.image_front_thumb_url || null
  }
}

/** The small front picture, ready to be stored as the item's photo. */
export async function productImage(product) {
  if (!product?.imageUrl) return null
  const image = await fetchImage(product.imageUrl)
  if (!image) return null
  return { ...image, name: `${product.barcode}.jpg` }
}
