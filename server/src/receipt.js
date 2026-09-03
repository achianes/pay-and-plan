import { config } from './config.js'

const TIMEOUT_MS = 150000

// what we ask the model to hand back; Ollama steers the output towards this shape
const SHAPE = {
  type: 'object',
  properties: {
    store: { type: 'string' },
    date: { type: 'string' },
    currency: { type: 'string' },
    total: { type: 'number' },
    items: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          quantity: { type: 'string' },
          price: { type: 'number' }
        },
        required: ['name', 'price']
      }
    }
  },
  required: ['store', 'total', 'items']
}

const PROMPT =
  'This is a photo of a shop receipt. Answer with exactly this JSON and nothing else:\n' +
  '{"store": "shop name", "date": "YYYY-MM-DD", "currency": "EUR", "total": 0.00, ' +
  '"items": [{"name": "as printed, without product codes", "quantity": "if printed", "price": 0.00}]}\n' +
  'One entry per purchased line, with the price of that line as a number. ' +
  'Leave out subtotals, tax lines, discounts already applied, cash tendered and change. ' +
  '"total" is the grand total actually paid. Use exactly these key names.'

/** The model likes to wrap its answer in a code fence; the JSON is what we want. */
function jsonFrom(text) {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/i)
  const raw = (fenced ? fenced[1] : text).trim()
  const start = raw.indexOf('{')
  const end = raw.lastIndexOf('}')
  if (start < 0 || end < start) throw new Error('the model did not answer with a receipt')
  return JSON.parse(raw.slice(start, end + 1))
}

/** Whatever the model called it, find the field we mean. */
const pick = (obj, ...names) => {
  for (const n of names) if (obj && obj[n] != null && obj[n] !== '') return obj[n]
  return undefined
}

const cents = (value) => {
  const n = typeof value === 'number' ? value : Number(String(value ?? '').replace(',', '.'))
  return Number.isFinite(n) ? Math.round(n * 100) : null
}

/** "PANE CASERECCIO" reads better as "Pane Casereccio" on a list. */
const tidy = (name) => String(name || '')
  .replace(/\s+/g, ' ')
  .trim()
  .toLowerCase()
  .replace(/(^|[\s(])(\p{L})/gu, (_, before, letter) => before + letter.toUpperCase())

/**
 * Sends the picture to the household's Ollama and turns the answer into a shopping list
 * shaped payload: items with prices in cents, the shop, the day and the total.
 */
export async function readReceipt(buffer, outerSignal = null) {
  if (!config.ollamaUrl) throw new Error('no receipt reader configured')

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  // the phone pressed STOP: no point keeping the model busy
  if (outerSignal) outerSignal.addEventListener('abort', () => controller.abort(), { once: true })
  let response
  try {
    response = await fetch(`${config.ollamaUrl.replace(/\/$/, '')}/api/chat`, {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: config.ollamaModel,
        stream: false,
        think: false,
        format: SHAPE,
        options: { temperature: 0 },
        messages: [{ role: 'user', content: PROMPT, images: [buffer.toString('base64')] }]
      })
    })
  } catch (e) {
    if (outerSignal?.aborted) throw new Error('stopped')
    throw new Error(e.name === 'AbortError'
      ? 'the receipt reader took too long'
      : `cannot reach the receipt reader: ${e.message}`)
  } finally {
    clearTimeout(timer)
  }
  if (!response.ok) throw new Error(`the receipt reader answered ${response.status}`)

  const payload = await response.json()
  const parsed = jsonFrom(payload?.message?.content || '')

  const items = (Array.isArray(parsed.items) ? parsed.items : [])
    .map((it) => {
      const quantity = pick(it, 'quantity', 'qty')
      return {
        name: tidy(pick(it, 'name', 'description', 'item', 'product')),
        quantity: quantity == null || String(quantity) === '1' ? '' : String(quantity).trim(),
        priceCents: cents(pick(it, 'price', 'line_price', 'linePrice', 'amount', 'total'))
      }
    })
    .filter((it) => it.name)
  if (!items.length) throw new Error('no items could be read from that picture')

  const summed = items.reduce((s, it) => s + (it.priceCents || 0), 0)
  const rawDate = String(pick(parsed, 'date', 'purchase_date') || '')
  const date = /^\d{4}-\d{2}-\d{2}$/.test(rawDate) ? rawDate : null
  return {
    store: tidy(pick(parsed, 'store', 'shop', 'store_name', 'shop_name', 'merchant')) || 'Receipt',
    date,
    currency: String(pick(parsed, 'currency') || '').toUpperCase().slice(0, 3) || null,
    totalCents: cents(pick(parsed, 'total', 'grand_total', 'grandTotal', 'total_paid', 'amount_paid')) ?? summed,
    items
  }
}
