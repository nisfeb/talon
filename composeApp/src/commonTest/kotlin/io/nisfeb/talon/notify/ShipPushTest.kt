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
