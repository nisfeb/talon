package io.nisfeb.talon.orrery

import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Taking a called-off occurrence off the calendar: what the triage proposes. */
class OrreryCalendarTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    // Rule 14: the fact says the evening is off, and the calendar goes
    // on showing it until somebody takes it off. Talon knows the event
    // because the ship signs the body's facts with it, so it offers.
    @Test
    fun `a cancel names the event, the mode and the occurrence`() {
        val a = cancelAction("activity/pirates-practice", "Pirates practice", "evt-1", ms("2026-09-22T22:00:00Z"))
        assertEquals("calendar", a["kind"]!!.jsonPrimitive.content)
        val p = a["payload"]!!.jsonObject
        assertEquals("cancel", p["mode"]!!.jsonPrimitive.content)
        assertEquals("evt-1", p["event"]!!.jsonPrimitive.content)
        // title and starts stay as an add has them: the ship's payload
        // shape refuses one that lacks a required key, mode or no mode.
        assertEquals("Pirates practice", p["title"]!!.jsonPrimitive.content)
        assertEquals("2026-09-22T22:00:00Z", p["starts"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("activity/pirates-practice"),
            a["about"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    // The ship matches the occurrence by the moment it really starts
    // and silently drops a skip it cannot place, so the calendar's own
    // instant beats the model's reading of "tonight".
    @Test
    fun `the occurrence matched is the calendar's own, within the day`() {
        val tonight = ms("2026-09-22T22:00:00Z")
        val nextWeek = ms("2026-09-29T22:00:00Z")
        val read = ms("2026-09-22T22:30:00Z") // the model said half past
        assertEquals(tonight, occurrenceNear(listOf(tonight, nextWeek), read))
        // Nothing within a day is no match: skipping the wrong evening
        // is worse than skipping none.
        assertNull(occurrenceNear(listOf(nextWeek), read))
        assertNull(occurrenceNear(emptyList(), read))
        // The nearer of two on the same day, for an event that meets twice.
        val morning = ms("2026-09-22T13:00:00Z")
        assertEquals(tonight, occurrenceNear(listOf(morning, tonight), read))
    }
}
