package com.payandplan.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refusal carries the same words as an acceptance: the rule has to tell them apart. */
class BankRuleTest {

    private val accepted = BankRule(phrase = "Pagamento accettato", butNot = "non accettato")

    @Test
    fun `takes the payment that went through`() {
        assertTrue(
            accepted.matches(
                "Pagamento accettato 👍💳",
                "Il pagamento di 10,27 EUR in data DECATHLON effettuato con la tua carta"
            )
        )
    }

    @Test
    fun `leaves the one that did not`() {
        assertFalse(accepted.matches("Pagamento NON accettato", "Il pagamento di 10,27 EUR"))
        assertFalse(accepted.matches("Pagamento non accettato ❌", ""))
        // the denial may be in the body rather than in the heading
        assertFalse(accepted.matches("Pagamento accettato", "Il pagamento non accettato per saldo"))
    }

    @Test
    fun `a rule without a denial behaves as before`() {
        val plain = BankRule(phrase = "bonifico ricevuto")
        assertTrue(plain.matches("Nuovo bonifico ricevuto", "1.200,00 EUR"))
        assertFalse(plain.matches("Promozione del mese", ""))
    }
}
