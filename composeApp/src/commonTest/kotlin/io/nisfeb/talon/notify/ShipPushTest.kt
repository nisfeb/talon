package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A push as grubbery's lib/web-push.hoon writes it: title and body, then icon, url and tag where set. */
class ShipPushTest {
    @Test
    fun `a leave push reads whole`() {
        assertEquals(
            ShipPushMessage("Leave in 10 min for Dentist", "23 min with traffic; leave by 15:02", "orrery-leave-situation/dentist@1791127800000"),
            parseShipPush("""{"title":"Leave in 10 min for Dentist","body":"23 min with traffic; leave by 15:02","url":"/apps/orrery","tag":"orrery-leave-situation/dentist@1791127800000"}"""),
        )
    }

    // Orrery 110's nag, as its +notify-owner sends it through grubbery's
    // web push: the tag comes whole, for the action's own slot.
    @Test
    fun `a late nag reads whole with its tag`() {
        val push = parseShipPush("""{"title":"Late: Check in for DL 2627","body":"Due 19:05, 2 h 10 late.","url":"/apps/orrery","tag":"orrery-overdue-1791915693-e8f6ea94"}""")
        assertEquals(ShipPushMessage("Late: Check in for DL 2627", "Due 19:05, 2 h 10 late.", "orrery-overdue-1791915693-e8f6ea94"), push)
        assertEquals("1791915693-e8f6ea94", io.nisfeb.talon.orrery.actionIdOfTag(push?.tag))
    }

    @Test
    fun `the optional keys may be absent`() {
        assertEquals(ShipPushMessage("Standup", "in 30 min", null), parseShipPush("""{"title":"Standup","body":"in 30 min"}"""))
    }

    @Test
    fun `no title, or not JSON, is not a push`() {
        assertNull(parseShipPush("""{"body":"x"}"""))
        assertNull(parseShipPush("""{"title":"","body":"x"}"""))
        assertNull(parseShipPush("not json"))
        assertNull(parseShipPush("[]"))
    }
}
