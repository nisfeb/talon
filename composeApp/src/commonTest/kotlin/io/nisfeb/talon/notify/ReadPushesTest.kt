package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** sneagan, 2026-10-07: a phone notified a DM already read on desktop;
 *  the read came 3 s after the DM, while its notification waited. */
class ReadPushesTest {
    @Test
    fun a_read_after_the_message_came_holds_its_notification() {
        ReadPushes.read("~zod", "~bus", atMs = 3_000)
        assertTrue(ReadPushes.readSince("~zod", "~bus", sinceMs = 1_000), "read after it came: not posted")
        assertFalse(ReadPushes.readSince("~zod", "~bus", sinceMs = 5_000), "a newer message still notifies")
    }

    @Test
    fun only_that_ships_chat() {
        ReadPushes.read("~zod", "~nec", atMs = 3_000)
        assertFalse(ReadPushes.readSince("~wes", "~nec", sinceMs = 1_000), "the same whom on another ship is another chat")
        assertFalse(ReadPushes.readSince("~zod", "~sampel", sinceMs = 1_000))
    }
}
