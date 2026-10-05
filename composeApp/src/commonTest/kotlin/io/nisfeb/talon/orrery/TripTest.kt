package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip mode's window: from the leave alert until a quarter hour past the time to be there. */
class TripTest {
    private val plan = LeavePlan(
        "activity/fencing-lesson@1791230400000", "Fencing lesson",
        leaveByMs = 1_791_228_757_000, alertAtMs = 1_791_228_157_000, minutes = 23, startsMs = 1_791_230_400_000,
    )

    @Test
    fun `a trip runs from the alert to fifteen minutes past the time to be there`() {
        val trip = tripOf(plan)!!
        assertEquals(Trip(plan.key, "Fencing lesson", plan.alertAtMs, 1_791_230_400_000 + 15 * 60_000), trip)
        assertTrue(trip.live(plan.alertAtMs))
        assertTrue(trip.live(plan.alertAtMs - 8_000), "the push beat its own alert_at by 8 s on the first live alert")
        assertFalse(trip.live(plan.alertAtMs - TRIP_LEAD_MS - 1), "not long before")
        assertTrue(trip.live(trip.untilMs - 1))
        assertFalse(trip.live(trip.untilMs), "over a quarter hour past the start")
    }

    @Test
    fun `a ship that does not say when to be there arms no trip`() {
        assertNull(tripOf(plan.copy(startsMs = null)))
    }

    @Test
    fun `a trip asks for a fix each minute, against ten`() {
        assertEquals(60_000L, TRIP_GAP_MS)
        assertEquals(100f, TRIP_MOVE_M)
    }
}
