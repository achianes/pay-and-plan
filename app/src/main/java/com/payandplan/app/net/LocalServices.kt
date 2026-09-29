package com.payandplan.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** A barcode looked up straight from the phone; [image] is already saved in private storage. */
data class LocalProduct(val label: String, val quantity: String, val named: Boolean, val image: File?)

/**
 * What the server used to do for the app, done from the phone itself with public open data
 * services, so Pay & Plan works with no server of ours at all. Nothing personal is sent: a
 * barcode, an address typed in a search box, a link the user asked to read.
 */
class LocalServices(private val context: Context) {

    private val userAgent = "PayAndPlan/1.0 (Android; ${context.packageName})"

    private fun get(url: String, timeoutMs: Int = 8000, accept: String = "application/json"): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", accept)
        return try {
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------ products (Open Facts)

    private val databases = listOf(
        "world.openfoodfacts.org", "world.openproductsfacts.org",
        "world.openbeautyfacts.org", "world.openpetfoodfacts.org"
    )
    private val fields = listOf(
        "product_name", "generic_name", "abbreviated_product_name", "brands", "quantity",
        "product_quantity", "product_quantity_unit",
        "image_front_small_url", "image_front_thumb_url", "image_small_url", "image_url"
    ).joinToString(",")

    /** Same idea as the server: every Open Facts shelf, a few shapes of the same code. */
    suspend fun lookupProduct(raw: String): LocalProduct? = withContext(Dispatchers.IO) {
        var code = raw.filter { it.isDigit() }
        if (code.length > 14 && code.startsWith("01")) code = code.substring(2, 16)
        if (code.length !in 8..14) return@withContext null
        for (variant in variantsOf(code)) {
            for (host in databases) {
                val body = runCatching { get("https://$host/api/v2/product/$variant.json?fields=$fields") }.getOrNull()
                    ?: continue
                val j = runCatching { JSONObject(body) }.getOrNull() ?: continue
                if (j.optInt("status") != 1) continue
                val p = j.optJSONObject("product") ?: continue
                return@withContext shape(p, code)
            }
        }
        null
    }

    private fun shape(p: JSONObject, code: String): LocalProduct {
        fun s(k: String) = p.optString(k).replace(Regex("\\s+"), " ").trim()
        val name = listOf(s("product_name"), s("generic_name"), s("abbreviated_product_name"))
            .firstOrNull { it.isNotBlank() }.orEmpty().replaceFirstChar { it.uppercase() }
        val brand = s("brands").substringBefore(',').trim()
        val quantity = s("quantity").ifBlank {
            if (s("product_quantity").isNotBlank()) "${s("product_quantity")} ${s("product_quantity_unit").ifBlank { "g" }}" else ""
        }
        val label = listOf(
            name.ifBlank { brand },
            if (brand.isNotBlank() && name.isNotBlank() && !name.contains(brand, true)) brand else ""
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val imageUrl = listOf("image_front_small_url", "image_front_thumb_url", "image_small_url", "image_url")
            .map { s(it) }.firstOrNull { it.startsWith("http") }
        return LocalProduct(
            label = label.ifBlank { "Product $code" },
            quantity = quantity,
            named = name.isNotBlank() || brand.isNotBlank(),
            image = imageUrl?.let { download(it, "$code.jpg") }
        )
    }

    private fun variantsOf(code: String): List<String> {
        val out = mutableListOf(code)
        if (code.length == 14) {
            val t = code.substring(1, 13)
            val sum = t.reversed().mapIndexed { i, ch -> (ch - '0') * (if (i % 2 == 0) 3 else 1) }.sum()
            out += t + ((10 - sum % 10) % 10)
        }
        if (code.length < 13) out += code.padStart(13, '0')
        val trimmed = code.trimStart('0')
        if (trimmed.length >= 8 && trimmed != code) out += trimmed
        return out.distinct()
    }

    /** Saves a picture into the attachments folder; null when it cannot be fetched. */
    fun download(url: String, name: String): File? = runCatching {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", userAgent)
        try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        target.takeIf { it.length() > 0 }
    }.getOrNull()

    // ------------------------------------------------------------ places (OpenStreetMap)

    /** Free text address -> places, asked straight to OpenStreetMap's Nominatim. */
    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=" +
            URLEncoder.encode(query, "UTF-8")
        val body = get(url) ?: return@withContext emptyList()
        val a = JSONArray(body)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(o.optString("display_name"), o.optString("lat").toDouble(), o.optString("lon").toDouble())
        }
    }

    // ------------------------------------------------------------ links

    /** Title and a short text of a public page: the page's own title and description. */
    suspend fun unfurl(url: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val html = runCatching { get(url, 10000, "text/html") }.getOrNull() ?: return@withContext null
        fun meta(key: String): String? = Regex(
            "<meta[^>]+(?:property|name)=[\"']$key[\"'][^>]*content=[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.let(::decode)
        val title = meta("og:title")
            ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::decode)
            ?: ""
        val text = meta("og:description") ?: meta("description") ?: ""
        title.trim() to text.trim()
    }

    private fun decode(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
}
