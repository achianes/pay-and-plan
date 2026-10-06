package com.payandplan.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Taking a day apart and putting it back together has to come out the same both ways, or
 * somebody's money quietly changes shape.
 */
class SmallChangeTest {

    @Test
    fun `a line written is a line read`() {
        val written = SmallChange.line(17 * 60 + 29, "Decathlon 00000410", "€10.27")
        assertEquals("17:29 · Decathlon 00000410 · €10.27", written)

        val back = SmallChange.read(written)!!
        assertEquals(17 * 60 + 29, back.minutes)
        assertEquals("Decathlon 00000410", back.shop)
        assertEquals(1027L, back.cents)
    }

    @Test
    fun `reads the lines the old grouped entries were written with`() {
        val old = SmallChange.read("08:10 · Pagamento accettato 👍💳 · €2.20")!!
        assertEquals(8 * 60 + 10, old.minutes)
        assertEquals("Pagamento accettato 👍💳", old.shop)
        assertEquals(220L, old.cents)
    }

    @Test
    fun `a shop with a dot in its name keeps it`() {
        val back = SmallChange.read("09:05 · Bar A · B · €1.70")!!
        assertEquals("Bar A · B", back.shop)
        assertEquals(170L, back.cents)
    }

    @Test
    fun `everything that is not one of ours is left alone`() {
        assertNull(SmallChange.read("just a note somebody typed"))
        assertNull(SmallChange.read(""))
        assertNull(SmallChange.read("17:29 · Decathlon"))
        assertNull(SmallChange.read("17:29 · Decathlon · nothing"))
        assertNull(SmallChange.read("99:99 · Decathlon · €1.00"))
        assertNull(SmallChange.read("17:29 ·  · €1.00"))
    }

    @Test
    fun `the whole day adds up to what it did before`() {
        val day = listOf(
            SmallChange.line(8 * 60 + 10, "Bar", "€2.20"),
            SmallChange.line(10 * 60 + 4, "Decathlon", "€10.27"),
            SmallChange.line(14 * 60 + 4, "Christian", "€8.00")
        )
        assertEquals(2047L, day.mapNotNull { SmallChange.read(it) }.sumOf { it.cents })
    }
}
