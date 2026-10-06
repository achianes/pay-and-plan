package com.payandplan.app.util

/**
 * The day's small change can be written as one entry, with every purchase on a line of its
 * own, and read back out of it. Both directions live here, side by side, because a format
 * that only one of them understands is a day of somebody's money lost.
 */
object SmallChange {

    private const val DOT = " · "

    /** One purchase, as a line of a packed entry: "17:29 · Decathlon · €10.27". */
    fun line(minutes: Int, shop: String, money: String): String =
        Format.time(minutes) + DOT + shop.trim() + DOT + money

    /** That line, read back. Null when it is not one of ours. */
    fun read(line: String): Purchase? {
        val parts = line.split(DOT)
        if (parts.size < 3) return null
        val clock = parts[0].trim().split(":")
        val hours = clock.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minutes = clock.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        val cents = MoneyText.amountCents(parts.last()) ?: return null
        // a shop may carry the dot itself: everything between the clock and the money is name
        val shop = parts.subList(1, parts.size - 1).joinToString(DOT).trim()
        if (shop.isBlank()) return null
        return Purchase(hours * 60 + minutes, shop, cents)
    }

    data class Purchase(val minutes: Int, val shop: String, val cents: Long)
}
