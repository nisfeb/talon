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
 * event on the calendar: orrery-utils' reader rule and client rule 11.
 */
class CalendarActionTest {
    private val ny = TimeZone.of("America/New_York")

    private fun action(id: String, status: String, vararg payload: Pair<String, String>) = OrreryAction(
        id, "calendar", "Dinner", buildJsonObject { payload.forEach { (k, v) -> put(k, v) } }, emptyList(), null, status, "reader",
    )

    private fun event(id: String, actionId: String, cat: String = "timed") =
        CalendarTask(id, meta = buildJsonObject { put("name", "Dinner"); put("orrery", actionId) }, cat = cat)

    @Test
    fun `an approved one is placed once, said once, and a twin goes`() {
        val starts = "starts" to "2026-10-02T00:00:00Z"
        val moves = calendarMoves(
            listOf(
                action("a", "approved", starts),
                action("b", "approved", starts),
                action("c", "approved"),
                action("d", "proposed", starts),
                action("e", "dismissed", starts),
            ),
            listOf(event("ev2", "b"), event("ev1", "b"), event("todo1", "a", cat = "todo")),
        )
        assertEquals(
            setOf(TaskMove.Drop("ev2"), TaskMove.Place(action("a", "approved", starts)), TaskMove.Placed("b"), TaskMove.Unplaceable("c")),
            moves.toSet(),
            "a todo is not the event; the proposed and the dismissed make nothing",
        )
    }

    private fun body(vararg payload: Pair<String, String>): JsonObject = placeBody(action("a", "approved", *payload), ny)!!

    @Test
    fun `a timed event is at its hour here, linked and tagged`() {
        // 00:00 UTC is 20:00 the evening before in New York.
        val b = body("starts" to "2026-10-02T00:00:00Z", "location" to "Luigi's")
        assertEquals("add-event", b["action"]!!.jsonPrimitive.content)
        assertEquals("timed", b["cat"]!!.jsonPrimitive.content)
        assertEquals("America/New_York", b["zone"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse("2026-10-01T20:00:00Z").toEpochMilliseconds(), b["start_ms"]!!.jsonPrimitive.long, "wall clock, read in the zone")
        assertEquals(60L, b["dur_min"]!!.jsonPrimitive.long, "an hour without an end")
        val meta = b["meta"]!!.jsonObject
        assertEquals("a", meta["orrery"]!!.jsonPrimitive.content)
        assertEquals(listOf("orrery"), meta["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Luigi's", meta["location"]!!.jsonPrimitive.content)
        assertEquals(90L, body("starts" to "2026-10-02T00:00:00Z", "ends" to "2026-10-02T01:30:00Z")["dur_min"]!!.jsonPrimitive.long)
    }

    @Test
    fun `whole days are all day, here or as a bare date`() {
        val here = body("starts" to "2026-10-03T04:00:00Z", "ends" to "2026-10-05T04:00:00Z")
        assertEquals("allday", here["cat"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse("2026-10-03T00:00:00Z").toEpochMilliseconds(), here["start_ms"]!!.jsonPrimitive.long)
        assertEquals(2L, here["span_days"]!!.jsonPrimitive.long)
        val bare = body("starts" to "2026-10-03")
        assertEquals("allday", bare["cat"]!!.jsonPrimitive.content)
        assertEquals(1L, bare["span_days"]!!.jsonPrimitive.long)
    }

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
