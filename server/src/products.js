import { fetchImage } from './unfurl.js'

/**
 * The Open Facts family. Food is only one of the shelves: detergents, soap and pet food live
 * in databases of their own, and a barcode missing from one is often sitting in another, so a
 * single "not found" from the food database means nothing. Order matters: first hit wins.
 */
const DATABASES = [
  { host: 'it.openfoodfacts.org', name: 'Open Food Facts' },
  { host: 'world.openfoodfacts.org', name: 'Open Food Facts' },
  { host: 'world.openproductsfacts.org', name: 'Open Products Facts' },
  { host: 'world.openbeautyfacts.org', name: 'Open Beauty Facts' },
  { host: 'world.openpetfoodfacts.org', name: 'Open Pet Food Facts' }
]

const FIELDS = [
  'code', 'product_name', 'product_name_it', 'generic_name', 'generic_name_it',
  'abbreviated_product_name', 'brands', 'quantity', 'product_quantity', 'product_quantity_unit',
  'image_front_small_url', 'image_front_thumb_url', 'image_small_url', 'image_url'
].join(',')

const USER_AGENT = 'PayAndPlan/1.0 (https://github.com/achianes/pay-and-plan)'
const TIMEOUT_MS = 8000

const clean = (s) => String(s || '').replace(/\s+/g, ' ').trim()
const capitalise = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : '')
const firstOf = (...values) => values.map(clean).find(Boolean) || ''

/** One question to one database. Null when it does not have the code, throws when it cannot answer. */
async function askOnce(host, code) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const res = await fetch(`https://${host}/api/v2/product/${code}.json?fields=${FIELDS}`, {
      signal: controller.signal,
      headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' }
    })
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`${host} answered ${res.status}`)
    const json = await res.json()
    return json?.status === 1 && json.product ? json.product : null
  } finally {
    clearTimeout(timer)
  }
}

/** The same question again when the first attempt died on the way: one bad moment is not an answer. */
async function ask(host, code) {
  try {
    return await askOnce(host, code)
  } catch {
    try {
      return await askOnce(host, code)
    } catch {
      return null
    }
  }
}

/**
 * Turns whatever the database holds into something a shopping list can show. An entry with no
 * name at all is still an answer: it exists, it has a picture perhaps, and the shopper can
 * name it. Saying "not in the database" for those is how a real product looks missing.
 */
function shape(product, database, barcode) {
  const name = capitalise(firstOf(
    product.product_name_it, product.product_name,
    product.generic_name_it, product.generic_name, product.abbreviated_product_name
  ))
  const brand = clean(product.brands).split(',')[0].trim()
  const quantity = clean(product.quantity) ||
    (product.product_quantity ? `${clean(product.product_quantity)} ${clean(product.product_quantity_unit) || 'g'}`.trim() : '')

  // "Nutella · Ferrero", without repeating the brand when the name already carries it
  const label = [
    name || brand,
    brand && name && !name.toLowerCase().includes(brand.toLowerCase()) ? brand : ''
  ].filter(Boolean).join(' · ')

  return {
    barcode,
    name,
    brand,
    quantity,
    label: label || `Product ${barcode}`,
    /** false when the entry exists but nobody has named it yet: the shopper has to */
    named: Boolean(name || brand),
    source: database.name,
    imageUrl: product.image_front_small_url || product.image_front_thumb_url ||
      product.image_small_url || product.image_url || null
  }
}

/** The check digit that closes a GTIN: alternating weights, then up to the next ten. */
function checkDigit(digits) {
  const sum = [...digits].reverse()
    .reduce((acc, d, i) => acc + Number(d) * (i % 2 === 0 ? 3 : 1), 0)
  return String((10 - (sum % 10)) % 10)
}

/**
 * The shapes the same barcode can take. A multipack wears an ITF-14, which is the shelf code
 * with a packaging digit in front and its own check digit; databases keep the 13. Scanners
 * pad, print short, or hand over a GS1 string with the code inside.
 */
function variantsOf(code) {
  const out = [code]
  if (code.length === 14) {
    const thirteen = code.slice(1, 13)
    out.push(thirteen + checkDigit(thirteen))
  }
  if (code.length < 13) out.push(code.padStart(13, '0'))
  const trimmed = code.replace(/^0+/, '')
  if (trimmed.length >= 8 && trimmed !== code) out.push(trimmed)
  return [...new Set(out)]
}

/** GS1 strings carry the code behind "01", with the rest of the label after it. */
function fromGs1(digits) {
  if (digits.length <= 14 || !digits.startsWith('01')) return digits
  return digits.slice(2, 16)
}

/**
 * Looks a barcode up across every database, and only gives up once they have all said no.
 * Returns what a shopping list wants to know, or null when nobody has ever seen that code.
 */
export async function lookupProduct(rawCode) {
  const code = fromGs1(String(rawCode || '').replace(/\D/g, ''))
  if (code.length < 8 || code.length > 14) throw new Error('that does not look like a product barcode')

  for (const variant of variantsOf(code)) {
    // the food database is the likely one: ask it alone first, so a hit costs a single call
    const first = await ask(DATABASES[0].host, variant)
    if (first) return shape(first, DATABASES[0], code)

    // then the other shelves together, and the earliest database in the list wins
    const rest = DATABASES.slice(1)
    const answers = await Promise.all(rest.map((db) => ask(db.host, variant)))
    const hit = answers.findIndex(Boolean)
    if (hit >= 0) return shape(answers[hit], rest[hit], code)
  }
  return null
}

/** The small front picture, ready to be stored as the item's photo. */
export async function productImage(product) {
  if (!product?.imageUrl) return null
  const image = await fetchImage(product.imageUrl)
  if (!image) return null
  return { ...image, name: `${product.barcode}.jpg` }
}
