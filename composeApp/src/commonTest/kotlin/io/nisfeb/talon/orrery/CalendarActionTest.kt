package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarTask
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A calendar action, from the message that fixed a plan in time to the
 * proposal Talon files: orrery-utils' reader rule, rule 14. Putting an
 * approved one on the calendar is the ship's, as of orrery 34.
 */
class CalendarActionTest {
    private val ny = TimeZone.of("America/New_York")

    private fun action(id: String, status: String, vararg payload: Pair<String, String>) = OrreryAction(
        id, "calendar", "Dinner", buildJsonObject { payload.forEach { (k, v) -> put(k, v) } }, emptyList(), null, status, "reader",
    )

    // Placing an approved calendar action was Talon's until orrery 34.
    // The ship does it now, on its own executor fiber, so the tests for
    // placeBody and calendarMoves went with the code.

    // ---- the reader ----

    private val at = Instant.parse("2026-09-16T18:00:00Z").toEpochMilliseconds() // 14:00 in New York, a Wednesday

    private fun plan(title: String, starts: String, text: String, extra: String = "") =
        ModelExtractor.planOf("""{"claims":[],"plan":{"title":"$title","starts":"$starts"$extra}}""", text, at)

    @Test
    fun `a plan fixed in time stands, and what is not in the words goes`() {
        val p = assertNotNull(plan("Dinner at Luigi's", "2026-09-18T20:00:00-04:00", "dinner at Luigi's Friday at 8 then", ""","location":"Luigi's","ends":null"""))
        assertEquals(Instant.parse("2026-09-19T00:00:00Z").toEpochMilliseconds(), p.startMs)
        assertEquals("Luigi's", p.location)
        assertNull(p.endMs)
        assertNull(plan("Dinner", "2026-09-18T20:00:00-04:00", "we should get dinner sometime"), "no day, no hour: no plan")
        assertNull(plan("Dentist", "2026-09-18T20:00:00-04:00", "dinner Friday at 8"), "a title not in the words")
        assertNull(plan("Dinner", "2026-09-10T20:00:00-04:00", "dinner Friday at 8"), "behind the message")
        assertNull(plan("Dinner", "2027-12-01T20:00:00-04:00", "dinner Friday at 8"), "beyond the year")
        assertNull(plan("Dinner", "Friday at 8", "dinner Friday at 8"), "not a time")
        assertNull(plan("Dinner", "2026-09-18T20:00:00-04:00", "dinner Friday at 8", ""","location":"Mario's"""")?.location, "a place it did not say")
    }

    @Test
    fun `a plan is filed in the schema's shape, and the model is told when it is`() {
        val a = ModelExtractor.planAction(ModelExtractor.Plan("Dinner", at, null, "Luigi's"), listOf("person/sarah"))
        assertEquals("calendar", a["kind"]!!.jsonPrimitive.content)
        val payload = a["payload"]!!.jsonObject
        assertEquals("2026-09-16T18:00:00Z", payload["starts"]!!.jsonPrimitive.content)
        assertEquals(setOf("title", "starts", "location"), payload.keys)
        assertEquals("2026-09-16T14:00-04:00, a Wednesday", ModelExtractor.whenLine(at, ny))
        assertTrue("plan ::=" in ModelExtractor.GRAMMAR)
    }

    @Test
    fun `a plan from the model reaches the reader beside its claims`() = kotlinx.coroutines.test.runTest {
        val fake = object : LocalModel {
            override val rung = "fake"
            var asked = ""
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
                asked = user
                return """{"claims":[],"plan":{"title":"Dinner","starts":"2026-09-18T20:00:00-04:00","ends":null,"location":null}}"""
            }
            override fun close() = Unit
        }
        var got: ModelExtractor.Plan? = null
        val index = NameIndex(listOf(KnownBody("person/me", "me", listOf("me"), "~zod")))
        ModelExtractor.extract(fake, index, emptyList(), "dinner Friday at 8", "~bus", at, "~zod", onPlan = { got = it }, zone = ny)
        assertEquals("Dinner", got?.title)
        assertTrue("When: 2026-09-16T14:00-04:00, a Wednesday" in fake.asked, fake.asked)
    }

    @Test
    fun `an action's event reads the schema's names and the old ones`() {
        val e = assertNotNull(action("a", "approved", "starts" to "2026-10-02T00:00:00Z", "ends" to "2026-10-02T02:00:00Z", "location" to "Luigi's").eventToAdd())
        assertEquals(Instant.parse("2026-10-02T02:00:00Z").toEpochMilliseconds(), e.endMs)
        assertEquals("Luigi's", e.location)
        assertNotNull(action("a", "approved", "start" to "2026-10-02T00:00:00Z").eventToAdd())
    }
}
