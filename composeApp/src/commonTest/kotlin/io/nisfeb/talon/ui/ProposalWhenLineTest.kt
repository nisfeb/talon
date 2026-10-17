package io.nisfeb.talon.ui

import io.nisfeb.talon.orrery.OrreryAction
import io.nisfeb.talon.orrery.eventToAdd
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** When a proposed event happens, as the proposal says it, west of UTC included. */
class ProposalWhenLineTest {
    private val ny = TimeZone.of("America/New_York")

    private fun event(payload: String) = OrreryAction(
        "a1", "calendar", "Dentist", Json.parseToJsonElement(payload).jsonObject, emptyList(), null, "proposed", "generator",
    ).eventToAdd()!!

    // Read as local time, "2026-10-20" showed as 2026-10-19 20:00 in New York.
    @Test
    fun `a day with no time is that day, wherever the phone is`() {
        assertEquals("Dentist, 2026-10-20", whenLine(event("""{"starts":"2026-10-20"}"""), ny))
        assertEquals("Dentist, 2026-10-20", whenLine(event("""{"starts":"2026-10-20"}"""), TimeZone.of("Asia/Tokyo")))
        assertEquals(
            "Dentist, 2026-10-20 to 2026-10-22, Main St",
            whenLine(event("""{"starts":"2026-10-20","ends":"2026-10-22","location":"Main St"}"""), ny),
        )
        assertEquals("Dentist, 2026-10-20", whenLine(event("""{"starts":"2026-10-20","ends":"2026-10-20T18:00:00Z"}"""), ny), "an end on the same day names no last day")
    }

    @Test
    fun `a time is the owner's local time`() {
        assertEquals("Dentist, 2026-10-20 10:00 to 11:00", whenLine(event("""{"starts":"2026-10-20T14:00:00Z","ends":"2026-10-20T15:00:00Z"}"""), ny))
    }
}
