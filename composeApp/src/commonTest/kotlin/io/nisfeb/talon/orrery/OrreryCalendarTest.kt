package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a calendar event becomes on the ship, by the rules in
 * orrery-utils' docs/writing-a-client.md: ask before creating, a hit is
 * the thing itself, an occurrence lands on its activity, no status for
 * an event, and every `at` is the event's own time.
 */
class OrreryCalendarTest {
    private val me = "~zod"
    private val noon = 1_789_646_400_000L // 2026-09-17T12:00:00Z
    private val week = 7L * 24 * 3_600_000

    private fun row(startMs: Long, kind: String = "once", title: String = "Standup", uid: String = "E9", location: String = "") =
        CalendarRow(
            id = uid, cal = "default", kind = kind,
            meta = buildJsonObject {
                put("name", JsonPrimitive(title))
                if (location.isNotEmpty()) put("location", JsonPrimitive(location))
            },
            l = startMs, r = startMs + 1_800_000,
        )

    private fun subject(vararg rows: CalendarRow) = calendarSubjects(rows.toList()).single()

    @Test
    fun `a hit of kind activity is an occurrence of it, and makes no body`() {
        val s = subject(row(noon - week, "weekly"), row(noon, "weekly"), row(noon + week, "weekly"))
        val hits = listOf(ResolvedBody("activity/standup", "activity", "Standup", "exact"))
        val w = calendarWrite(s, decided = null, hits = hits, written = emptySet(), ourShip = me, nowMs = noon + 3_600_000)
        assertEquals("activity/standup", w.bodyId)
        assertTrue(w.facts.bodies.isEmpty(), "the ship already has it")
        assertEquals(false, w.creates)
        val last = w.facts.observations.filter { it.attr == "last" }
        assertEquals(listOf(noon - week, noon), last.map { it.atMs }, "each occurrence at its own time")
        assertEquals(JsonPrimitive(isoUtc(noon + week)), w.facts.observations.single { it.attr == "next" }.value)
        assertTrue(w.facts.observations.none { it.attr == "status" }, "an event never says it is open")
    }

    @Test
    fun `a hit of kind situation gets the facts, not a twin`() {
        val s = subject(row(noon))
        val hits = listOf(ResolvedBody("situation/bed-delivery", "situation", "Bed delivery", "exact"))
        val w = calendarWrite(s, null, hits, emptySet(), me, noon + 3_600_000)
        assertEquals("situation/bed-delivery", w.bodyId)
        assertTrue(w.facts.bodies.isEmpty())
        assertEquals(listOf("ended", "participants", "started"), w.facts.observations.map { it.attr }.sorted())
        assertEquals(noon, w.facts.observations.single { it.attr == "started" }.atMs)
    }

    @Test
    fun `no hit and it repeats makes one activity that can be resolved again`() {
        val s = subject(row(noon - week, "weekly", location = "the office"), row(noon, "weekly", location = "the office"))
        val w = calendarWrite(s, null, emptyList(), emptySet(), me, noon + 3_600_000)
        assertEquals(true, w.creates)
        val body = w.facts.bodies.single()
        assertEquals("activity/standup", body.id)
        assertTrue("E9" in body.aliases, "the calendar's own id, which is what reconcile keeps as an alias")
        assertTrue("Standup" in body.aliases)
        val attrs = w.facts.observations.map { it.attr }.toSet()
        assertTrue(attrs.containsAll(setOf("cadence", "schedule", "participants", "location", "last")))
        assertTrue("status" !in attrs)
        assertEquals(listOf(noon - week, noon), w.facts.observations.filter { it.attr == "last" }.map { it.atMs })
    }

    @Test
    fun `no hit and it happens once makes a situation with started and ended`() {
        val s = subject(row(noon, title = "Bed delivery", uid = "UID-1"))
        val w = calendarWrite(s, null, emptyList(), emptySet(), me, noon)
        assertEquals("situation/bed-delivery", w.facts.bodies.single().id)
        assertTrue("UID-1" in w.facts.bodies.single().aliases)
        assertEquals(noon, w.facts.observations.single { it.attr == "started" }.atMs)
        assertEquals(noon + 1_800_000, w.facts.observations.single { it.attr == "ended" }.atMs)
        assertTrue(w.facts.observations.none { it.attr == "status" })
    }

    @Test
    fun `an occurrence already written is not written again`() {
        val s = subject(row(noon - week, "weekly"), row(noon, "weekly"))
        val first = calendarWrite(s, "activity/standup", emptyList(), emptySet(), me, noon + 3_600_000)
        assertEquals(2, first.occurrenceKeys.size)
        val again = calendarWrite(s, "activity/standup", emptyList(), first.occurrenceKeys.toSet(), me, noon + 3_600_000)
        assertTrue(again.facts.observations.none { it.attr == "last" }, "a replay says nothing new")
        assertTrue(again.occurrenceKeys.isEmpty())
    }

    @Test
    fun `the decision this install already made skips the asking`() {
        val s = subject(row(noon, "weekly"))
        val w = calendarWrite(s, decided = "activity/standup", hits = emptyList(), written = emptySet(), ourShip = me, nowMs = noon + 60_000)
        assertEquals("activity/standup", w.bodyId)
        assertTrue(w.facts.bodies.isEmpty(), "a body is made once or never")
    }

    @Test
    fun `an event seen twice in one window counts as repeating`() {
        val rows = listOf(row(noon - week), row(noon))
        val s = calendarSubjects(rows).single()
        assertTrue(s.repeats, "the same event twice is a series the calendar did not label")
        assertEquals("activity/standup", calendarWrite(s, null, emptyList(), emptySet(), me, noon).bodyId)
    }

    @Test
    fun `tasks and nameless rows are not events`() {
        assertTrue(calendarSubjects(listOf(row(noon).copy(cat = "todo"))).isEmpty())
        assertTrue(calendarSubjects(listOf(row(noon, title = ""))).isEmpty())
    }
}
