package com.payandplan.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A movement's name is unique in the database. These check it is also never empty and
 * never accidentally shared, which is how the same card payment ended up in a day five
 * times: every notification was called "" and the newest one replaced the one before it.
 */
class MovementNameTest {

    private val posted = 1_762_000_000_000L

    @Test
    fun `the same notification read twice answers the same name`() {
        val first = BankMovement.shadeName("it.bank", 5850, "Pagamento accettato TERRA FELIX", posted)
        val again = BankMovement.shadeName("it.bank", 5850, "Pagamento accettato TERRA FELIX", posted)
        assertEquals(first, again)
    }

    @Test
    fun `a name is never empty`() {
        assertTrue(BankMovement.shadeName("", 0, "", 0).isNotBlank())
    }

    @Test
    fun `two different payments are not the same payment`() {
        val coffee = BankMovement.shadeName("it.bank", 150, "Pagamento accettato BAR", posted)
        val another = BankMovement.shadeName("it.bank", 150, "Pagamento accettato BAR", posted + 3_600_000L)
        assertNotEquals(coffee, another)
        assertNotEquals(coffee, BankMovement.shadeName("it.bank", 320, "Pagamento accettato BAR", posted))
        assertNotEquals(coffee, BankMovement.shadeName("it.other", 150, "Pagamento accettato BAR", posted))
    }

    @Test
    fun `a movement read from a notification carries that name`() {
        val name = BankMovement.shadeName("it.bank", 220, "Pedaggio", posted)
        val movement = BankMovement(
            externalId = name, packageName = "it.bank", text = "Pedaggio",
            amountCents = 220, happenedAt = posted
        )
        assertEquals(name, movement.externalId)
        assertTrue(movement.isPending)
    }
}
