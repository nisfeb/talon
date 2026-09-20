package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutOfCreditTest {
    @Test
    fun `a 402 names the balance and where to top up`() {
        val said = outOfCredit("localhost", "balance: empty")
        assertTrue(said.startsWith("Your Armillary balance is empty. Top up under Settings, AI."), said)
        assertTrue("402" in said && "balance: empty" in said, said)
    }

    @Test
    fun `the raw line is kept for anyone debugging`() {
        assertEquals(
            "Your Armillary balance is empty. Top up under Settings, AI. (vendor.example 402: balance: empty)",
            outOfCredit("vendor.example", "balance: empty"),
        )
    }
}
