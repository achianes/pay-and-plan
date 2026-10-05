package com.payandplan.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Banks write the same figure a dozen ways; the reader has to survive all of them. */
class MoneyTextTest {

    @Test
    fun `reads the figure however the bank writes it`() {
        assertEquals(1234L, MoneyText.amountCents("Pagamento di 12,34 EUR accettato"))
        assertEquals(1234L, MoneyText.amountCents("Addebito di € 12,34"))
        assertEquals(1234L, MoneyText.amountCents("Payment of EUR 12.34 approved"))
        assertEquals(1234L, MoneyText.amountCents("12,34€ pagati"))
        assertEquals(123456L, MoneyText.amountCents("Bonifico di 1.234,56 EUR ricevuto"))
        assertEquals(123456L, MoneyText.amountCents("Transfer of 1,234.56 EUR received"))
        assertEquals(95000L, MoneyText.amountCents("Affitto 950,00 EUR"))
        assertEquals(1234L, MoneyText.amountCents("Importo: -12,34 EUR"))
        assertEquals(123400L, MoneyText.amountCents("Bonifico di 1.234 EUR"))
        assertEquals(123400L, MoneyText.amountCents("Transfer of EUR 1,234"))
        assertEquals(50L, MoneyText.amountCents("Addebito di 0,50 EUR"))
    }

    @Test
    fun `says nothing when there is no money in the line`() {
        assertNull(MoneyText.amountCents("La tua spesa è pronta"))
        assertNull(MoneyText.amountCents(""))
        assertNull(MoneyText.amountCents("Hai 3 nuovi messaggi"))
    }

    @Test
    fun `recognises the wording whatever the case and the accents`() {
        assertTrue(MoneyText.contains("Pagamento ACCETTATO", "pagamento accettato"))
        assertTrue(MoneyText.contains("Nuovo bonifico ricevuto", "bonifico ricevuto"))
        assertTrue(MoneyText.contains("Addebito effettuato però", "addebito effettuato pero"))
        assertTrue(!MoneyText.contains("Promozione del mese", "pagamento accettato"))
    }

    @Test
    fun `tells a bill from another one`() {
        val said = "Pagamento di 78,40 EUR a ENEL ENERGIA SPA accettato"
        assertTrue(MoneyText.similarity("Enel", said) >= 0.34)
        assertTrue(MoneyText.similarity("Electricity", said) < 0.34)
        assertTrue(MoneyText.similarity("Affitto casa", "Bonifico a Rossi per affitto") >= 0.34)
    }
}
