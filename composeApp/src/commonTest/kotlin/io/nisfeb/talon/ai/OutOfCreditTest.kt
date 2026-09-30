package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutOfCreditTest {

    @Test
    fun `the raw line is kept for anyone debugging`() {
        assertEquals(
            "Your Armillary balance is empty. Top up under Settings, AI. (vendor.example 402: balance: empty)",
            outOfCredit("vendor.example", "balance: empty", canBuy = true),
        )
    }

    // Where the app cannot take the payment (iOS outside the US
    // storefront), it names no place to pay, and is still known for
    // what it is, so no Top up is offered beside it either.
    @Test
    fun `where credit cannot be bought here, it says only that the balance is empty`() {
        val line = outOfCredit("vendor.example", "balance: empty", canBuy = false)
        assertEquals("Your Armillary balance is empty. (vendor.example 402: balance: empty)", line)
        assertTrue(isOutOfCredit(line) && isOutOfCredit(outOfCredit("h", "m", canBuy = true)))
    }
}
