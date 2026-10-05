package com.payandplan.app.util

import java.text.Normalizer

/**
 * Reading money and meaning out of a line written by somebody else's app. Banks write the
 * same thing a dozen ways, so this stays deliberately forgiving: accents off, case off, and
 * both ways of writing a thousand.
 */
object MoneyText {

    /** Accents off, lower case, one space between words. */
    fun plain(text: String?): String =
        Normalizer.normalize(text.orEmpty().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun contains(haystack: String?, needle: String?): Boolean {
        val n = plain(needle)
        return n.isNotBlank() && plain(haystack).contains(n)
    }

    // 1.234,56 the Italian way, 1,234.56 the English one, 12.34 or 12,34 plain. Whichever
    // branch matches has to take the whole figure, never the first two digits of it.
    private val NUMBER = listOf(
        """\d{1,3}(?:\.\d{3})+,\d{1,2}""",
        """\d{1,3}(?:,\d{3})+\.\d{1,2}""",
        """\d{1,3}(?:\.\d{3})+""",
        """\d{1,3}(?:,\d{3})+""",
        """\d+(?:[.,]\d{1,2})?"""
    ).joinToString("|")

    private val AMOUNT = Regex("""(?:eur|€)\s*(-?(?:$NUMBER))|(-?(?:$NUMBER))\s*(?:eur|€)""")

    /**
     * The figure in a line like "Pagamento di 12,34 EUR accettato". When both separators are
     * there the last one is the decimal point; when there is only one and three digits follow
     * it, it was separating thousands. Null when there is no money in the line at all.
     */
    fun amountCents(text: String?): Long? {
        val line = plain(text)
        val match = AMOUNT.find(line) ?: return null
        val raw = (match.groupValues[1].ifBlank { match.groupValues[2] }).replace(" ", "")
        if (raw.isBlank()) return null

        val comma = raw.lastIndexOf(',')
        val dot = raw.lastIndexOf('.')
        val cleaned = when {
            comma < 0 && dot < 0 -> raw
            comma < 0 && raw.length - dot - 1 == 3 -> raw.replace(".", "")
            dot < 0 && raw.length - comma - 1 == 3 -> raw.replace(",", "")
            comma > dot -> raw.replace(".", "").replace(',', '.')
            else -> raw.replace(",", "")
        }
        val value = cleaned.toDoubleOrNull() ?: return null
        val cents = Math.round(Math.abs(value) * 100.0)
        return if (cents in 1..100_000_000) cents else null
    }

    private val NOISE = setOf(
        "il", "lo", "la", "i", "gli", "le", "di", "da", "del", "della", "dei", "con", "per",
        "su", "in", "a", "e", "un", "una", "euro", "eur", "pagamento", "pagato", "addebito",
        "accettato", "autorizzato", "bonifico", "ricevuto", "accredito", "importo", "carta",
        "conto", "the", "and", "for", "payment", "paid", "from", "your", "has", "been"
    )

    /** The words worth comparing: no accents, no short words, no banking boilerplate. */
    fun words(text: String?): Set<String> =
        plain(text).split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 4 && it !in NOISE && it.toDoubleOrNull() == null }
            .toSet()

    /**
     * How much two descriptions have in common, 0 to 1. Enough for "ENEL ENERGIA" against
     * "Enel", not enough to confuse a water bill with a phone bill.
     */
    fun similarity(a: String?, b: String?): Double {
        val left = words(a)
        val right = words(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.count { word -> right.any { it.startsWith(word) || word.startsWith(it) } }
        return shared.toDouble() / minOf(left.size, right.size)
    }

    /** What to call the movement in a list: the shortest line that says something. */
    fun describe(title: String?, text: String?): String {
        val t = title.orEmpty().trim()
        val b = text.orEmpty().trim()
        return when {
            t.isNotBlank() && b.isNotBlank() -> "$t · ${b.take(80)}"
            t.isNotBlank() -> t
            else -> b.take(90)
        }
    }
}
