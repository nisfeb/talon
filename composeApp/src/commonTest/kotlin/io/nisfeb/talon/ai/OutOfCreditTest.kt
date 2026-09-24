package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class OutOfCreditTest {

    @Test
    fun `the raw line is kept for anyone debugging`() {
        assertEquals(
            "Your Armillary balance is empty. Top up under Settings, AI. (vendor.example 402: balance: empty)",
            outOfCredit("vendor.example", "balance: empty"),
        )
    }
}
