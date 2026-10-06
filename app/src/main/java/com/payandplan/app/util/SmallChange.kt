package com.payandplan.app.util

/**
 * The day's small change can be written as one entry, with every purchase on a line of its
 * own, and read back out of it. Both directions live here, side by side, because a format
 * that only one of them understands is a day of somebody's money lost.
 */
object SmallChange {

    private const val DOT = " · "

    /**
     * One purchase, as a line of a packed entry: "17:29 · Decathlon · €10.27", and with the
     * category it was given on the end, so putting a day together never loses the sorting.
     */
    fun line(minutes: Int, shop: String, money: String, category: String = ""): String =
        Format.time(minutes) + DOT + shop.trim() + DOT + money +
            if (category.isBlank()) "" else DOT + category.trim()

    /** That line, read back. Null when it is not one of ours. */
    fun read(line: String): Purchase? {
        val parts = line.split(DOT)
        if (parts.size < 3) return null
        val clock = parts[0].trim().split(":")
        val hours = clock.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minutes = clock.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        // the money is whichever part reads as money; a shop may carry the dot in its name,
        // and whatever follows the figure is the category it was sorted into
        val where = parts.indices.drop(1).lastOrNull { MoneyText.amountCents(parts[it]) != null }
            ?: return null
        val cents = MoneyText.amountCents(parts[where]) ?: return null
        val shop = parts.subList(1, where).joinToString(DOT).trim()
        if (shop.isBlank()) return null
        val category = parts.drop(where + 1).joinToString(DOT).trim()
        return Purchase(hours * 60 + minutes, shop, cents, category)
    }

    data class Purchase(
        val minutes: Int,
        val shop: String,
        val cents: Long,
        val category: String = ""
    )

    /**
     * Whether a whole entry is a day put together: its notes read as these lines and they
     * add up to exactly what it says. Nothing else can tell us — a flag does not survive a
     * server that has never heard of it, and a lump mistaken for small change gets chewed.
     */
    fun isPacked(notes: String, amountCents: Long): Boolean {
        val lines = notes.lines().mapNotNull { read(it) }
        return lines.isNotEmpty() && lines.sumOf { it.cents } == amountCents
    }
}
