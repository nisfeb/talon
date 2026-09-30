package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS takes isArmillaryPurchaseSupported from this, with StoreKit's
 * storefront: a button to an outside checkout on the US App Store only
 * (App Review guideline 3.1.1(a)).
 */
class OutsideCheckoutTest {
    @Test
    fun `only the United States storefront may send a buyer to an outside checkout`() {
        assertTrue(outsideCheckoutAllowedOnAppStore("USA"))
        listOf("DEU", "FRA", "GBR", "CAN", "JPN", "KOR", "US", "usa", "").forEach {
            assertFalse(outsideCheckoutAllowedOnAppStore(it), it)
        }
    }

    @Test
    fun `no storefront known is not the US one`() {
        assertFalse(outsideCheckoutAllowedOnAppStore(null))
    }
}
