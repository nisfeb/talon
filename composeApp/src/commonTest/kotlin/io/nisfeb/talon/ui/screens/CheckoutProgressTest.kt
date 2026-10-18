package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.armillary.Checkout
import io.nisfeb.talon.armillary.Payment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Armillary card says while a checkout is made, and when Top up
 * stays shut. A Continue looked like it did nothing: a small grey line,
 * then nothing, and a second order made a checkout nobody was sent to.
 */
class CheckoutProgressTest {
    private fun waiting(url: String = "") = Payment("n1", "stripe", 0L, Payment.Phase.WAITING, url = url)

    @Test
    fun `the asking line names the vendor and counts the seconds`() {
        assertEquals(
            "Asking ~nisfeb for a checkout, 14 s. From some ships this takes a minute or two.",
            askingLine("~nisfeb", 14_400),
        )
        assertTrue(askingLine("", 0).startsWith("Asking the vendor for a checkout, 0 s."))
        assertTrue(askingLine(null, -5).contains(", 0 s."), "a clock behind is not negative")
    }

    @Test
    fun `top up stays shut while a checkout is asked for or has no page yet`() {
        assertTrue(orderingShut(asking = 1L, p = null, row = null), "asked for")
        assertTrue(orderingShut(null, waiting(), null), "made, no page yet")
        assertTrue(orderingShut(null, waiting(), Checkout("n1", "", "pending", "stripe", 5_000_000L)), "its row has no page either")
        assertFalse(orderingShut(null, waiting("https://pay.example/c"), null), "its page came")
        assertFalse(orderingShut(null, waiting(), Checkout("n1", "https://pay.example/c", "pending", "stripe", 5_000_000L)), "the row brought it")
        assertFalse(orderingShut(null, waiting(), Checkout("n1", "", "expired", "stripe", 5_000_000L)), "it is over")
        assertFalse(orderingShut(null, waiting().copy(phase = Payment.Phase.UNSEEN), null), "the watch gave up")
        assertFalse(orderingShut(null, null, null), "nothing ordered")
    }
}
