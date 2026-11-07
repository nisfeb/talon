package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InviteFriendTest {
    @Test
    fun `the invite carries where to get the app and who to add`() {
        val m = groundwireInviteMessage("~sampel-palnet", installUrl = "https://example.test/get")
        assertTrue("https://example.test/get" in m, m)
        assertTrue("Add me: ~sampel-palnet" in m, m)
        assertTrue("App Store" in m, "an iPhone gets it from the store, not the downloads page: $m")
        assertFalse("talon://" in m, "a link no messages app can open is not sent: $m")
        assertTrue("Groundwire" in m, m)
    }

    @Test
    fun `a name opens the message, and a blank or ship-only name does not`() {
        assertTrue(groundwireInviteMessage("~zod", "Christian").startsWith("It's Christian. "))
        assertFalse(groundwireInviteMessage("~zod", "  ").startsWith("It's"))
        assertFalse(groundwireInviteMessage("~zod", "~zod").startsWith("It's"))
    }

    @Test
    fun `with no share sheet the sharer says so, so the caller copies`() {
        assertEquals(false, NoopTextSharer.share("hi", "subject"))
    }
}
