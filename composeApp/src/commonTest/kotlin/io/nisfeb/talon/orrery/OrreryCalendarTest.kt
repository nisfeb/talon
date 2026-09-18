package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

    private fun row(
        startMs: Long,
        kind: String = "once",
        title: String = "Standup",
        uid: String = "E9",
        location: String = "",
        note: String = "",
    ) =
        CalendarRow(
            id = uid, cal = "default", kind = kind,
            meta = buildJsonObject {
                put("name", JsonPrimitive(title))
                if (location.isNotEmpty()) put("location", JsonPrimitive(location))
                if (note.isNotEmpty()) put("note", JsonPrimitive(note))
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
    fun `a one-off ahead of us is a schedule, not something that happened`() {
        val s = subject(row(noon, title = "Bed delivery", uid = "UID-1"))
        val soon = noon - 3_600_000 // an hour before it is due
        val w = calendarWrite(s, null, emptyList(), emptySet(), me, soon)
        assertEquals("situation/bed-delivery", w.facts.bodies.single().id)
        assertTrue("UID-1" in w.facts.bodies.single().aliases)
        assertEquals(listOf("ends", "participants", "starts"), w.facts.observations.map { it.attr }.sorted())
        // Dated when we learned it: a row dated ahead is hidden until then.
        assertEquals(soon, w.facts.observations.single { it.attr == "starts" }.atMs)
        assertEquals(JsonPrimitive(isoUtc(noon)), w.facts.observations.single { it.attr == "starts" }.value)
        assertEquals(soon, w.facts.observations.single { it.attr == "participants" }.atMs)
        assertTrue(w.facts.observations.none { it.attr == "status" })
        assertEquals(false, w.occurrences.single().settled, "nothing has happened yet")
    }

    @Test
    fun `a one-off under way has started, and still only ends`() {
        val s = subject(row(noon, title = "Bed delivery", uid = "UID-1"))
        val w = calendarWrite(s, null, emptyList(), emptySet(), me, noon + 60_000)
        assertEquals(listOf("ends", "participants", "started"), w.facts.observations.map { it.attr }.sorted())
        assertEquals(noon, w.facts.observations.single { it.attr == "started" }.atMs, "at its own moment")
        assertEquals(noon + 60_000, w.facts.observations.single { it.attr == "ends" }.atMs)
        assertEquals(false, w.occurrences.single().settled)
    }

    @Test
    fun `a one-off that is over is said in the past tense, and settles`() {
        val s = subject(row(noon, title = "Bed delivery", uid = "UID-1"))
        val w = calendarWrite(s, null, emptyList(), emptySet(), me, noon + 2 * 3_600_000)
        assertEquals(listOf("ended", "participants", "started"), w.facts.observations.map { it.attr }.sorted())
        assertEquals(noon, w.facts.observations.single { it.attr == "started" }.atMs)
        assertEquals(noon + 1_800_000, w.facts.observations.single { it.attr == "ended" }.atMs)
        assertTrue(w.facts.observations.none { it.attr == "status" })
        assertEquals(true, w.occurrences.single().settled)
        assertEquals("${noon + 1_800_000}:f", w.occurrences.single().record)
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
    fun `an edited time, place or description is a different event to the one we sent`() {
        val was = subject(row(noon, location = "the office", note = "bring the laptop")).digest
        assertEquals(was, subject(row(noon, location = "the office", note = "bring the laptop")).digest)
        assertTrue(was != subject(row(noon, location = "the cafe", note = "bring the laptop")).digest, "place")
        assertTrue(was != subject(row(noon, location = "the office", note = "bring nothing")).digest, "description")
        assertTrue(was != subject(row(noon, title = "Retro", location = "the office", note = "bring the laptop")).digest, "title")
        assertTrue(was != subject(row(noon, "weekly", location = "the office", note = "bring the laptop")).digest, "cadence")
    }

    @Test
    fun `an activity that changed says what it is again, without repeating its occurrences`() {
        val s = subject(row(noon - week, "weekly", location = "the cafe"), row(noon, "weekly", location = "the cafe"))
        val written = calendarWrite(s, "activity/standup", emptyList(), emptySet(), me, noon + 3_600_000)
            .occurrenceKeys.toSet()
        val again = calendarWrite(s, "activity/standup", emptyList(), written, me, noon + 3_600_000, changed = true)
        val attrs = again.facts.observations.map { it.attr }.toSet()
        assertTrue(attrs.containsAll(setOf("cadence", "schedule", "participants", "location")), "$attrs")
        assertEquals("the cafe", again.facts.observations.single { it.attr == "location" }.value.jsonPrimitive.content)
        assertEquals(noon, again.facts.observations.first { it.attr == "location" }.atMs, "true from the last time it came round")
        assertTrue(again.facts.observations.none { it.attr == "last" }, "an occurrence already sent is still not sent twice")
        assertTrue(again.facts.bodies.isEmpty(), "a body is made once or never")
    }

    @Test
    fun `a one-off that changed is said again although its occurrence was written`() {
        val s = subject(row(noon, title = "Bed delivery", uid = "UID-1", location = "the flat"))
        val written = calendarWrite(s, "situation/bed-delivery", emptyList(), emptySet(), me, noon).occurrenceKeys.toSet()
        assertTrue(calendarWrite(s, "situation/bed-delivery", emptyList(), written, me, noon).facts.observations.isEmpty())
        val again = calendarWrite(s, "situation/bed-delivery", emptyList(), written, me, noon, changed = true)
        assertEquals(listOf("ends", "location", "participants", "started"), again.facts.observations.map { it.attr }.sorted())
        assertEquals(noon, again.facts.observations.single { it.attr == "started" }.atMs)
    }

    @Test
    fun `an occurrence the calendar has moved away from is stale, one it never reached is not`() {
        val s = subject(row(noon, "weekly"), row(noon + week, "weekly"))
        val seen = mapOf(
            "occ:default/E9/${noon - week}" to "${noon - week + 1_800_000}", // moved: in the window, no longer there
            "occ:default/E9/$noon" to "${noon + 1_800_000}", // still there
            "occ:default/E9/${noon - 40 * week}" to "", // before the window: nothing to judge it by
            "occ:default/OTHER/${noon - week}" to "", // another event entirely
        )
        val stale = staleOccurrences(s, seen, noon - 30 * week, noon + 4 * week)
        assertEquals(listOf("occ:default/E9/${noon - week}"), stale.map { it.first })
        assertEquals(
            listOf(noon - week, noon - week + 1_800_000),
            stale.single().second,
            "both times its rows were anchored at, so the end goes with the start",
        )
    }

    @Test
    fun `an activity on our own calendar names who holds it`() {
        val s = subject(row(noon - week, "weekly"), row(noon, "weekly"))
        val ours = calendarWrite(s, null, emptyList(), emptySet(), me, noon + 3_600_000, ours = true)
        assertEquals(
            "person/me",
            ours.facts.observations.single { it.attr == "organizer" }.value.jsonObject["ref"]!!.jsonPrimitive.content,
        )
        val theirs = calendarWrite(s, null, emptyList(), emptySet(), me, noon + 3_600_000)
        assertTrue(
            theirs.facts.observations.none { it.attr == "organizer" },
            "on a calendar another ship shares, whose it is is not ours to say",
        )
    }

    private val cast = EventPeople.of(listOf(
        KnownBody("person/linus", "Linus Quill", listOf("Linus"), null),
        KnownBody("person/magnus", "Magnus", emptyList(), "~sampel-palnet"),
    ))

    private fun refs(w: CalendarWrite) =
        w.facts.observations.filter { it.attr == "participants" }.map { it.value.jsonObject["ref"]!!.jsonPrimitive.content }

    @Test
    fun `everyone the event names is in it, and the ship's own people are used`() {
        val s = subject(row(noon, title = "Rose and Linus- Opti Sail", uid = "S1"))
        val w = calendarWrite(s, null, emptyList(), emptySet(), me, noon, people = cast)
        assertEquals(listOf("person/me", "person/rose", "person/linus"), refs(w))
        assertEquals(
            listOf("person/rose"),
            w.facts.bodies.filter { it.id.startsWith("person/") }.map { it.id },
            "Linus the ship already has; Rose it does not, so Rose is made once",
        )
        assertEquals("Rose", w.facts.bodies.single { it.id == "person/rose" }.name)
    }

    @Test
    fun `a production, a team or a place is never a person`() {
        val none = subject(row(noon, "weekly", title = "Nutcracker rehearsal", uid = "S2"))
        val w = calendarWrite(none, null, emptyList(), emptySet(), me, noon + 60_000, people = cast)
        assertEquals(listOf("person/me"), refs(w))
        assertTrue(w.facts.bodies.none { it.id.startsWith("person/") }, "no body for a ballet")
        // A leading word the ship does keep as a person is that person.
        val known = subject(row(noon, "weekly", title = "Magnus Fencing Lesson", uid = "S3"))
        val w2 = calendarWrite(known, null, emptyList(), emptySet(), me, noon + 60_000, people = cast)
        assertEquals(listOf("person/me", "person/magnus"), refs(w2))
    }

    @Test
    fun `a description names whoever the ship already knows`() {
        val s = subject(row(noon, title = "Sailing", uid = "S4", note = "bring Linus's helmet"))
        val w = calendarWrite(s, null, emptyList(), emptySet(), me, noon, people = cast)
        assertEquals(listOf("person/me", "person/linus"), refs(w))
    }

    @Test
    fun `an event said again names nobody new`() {
        // A rename must not recreate somebody the owner's reconcile merged
        // away: only the pass that makes the event may make a person.
        val s = subject(row(noon, title = "Rose and Linus- Opti Sail", uid = "S5"))
        val again = calendarWrite(s, "situation/opti-sail", emptyList(), emptySet(), me, noon, changed = true, people = cast)
        assertEquals(listOf("person/me", "person/linus"), refs(again))
        assertTrue(again.facts.bodies.isEmpty())
    }

    @Test
    fun `a remembered occurrence says whether it still owes a past tense`() {
        val ahead = Occurrence("occ:default/E9/1", 5L, settled = false)
        assertEquals("5:s", ahead.record)
        assertTrue(Occurrence.unsettled(ahead.record))
        assertEquals(5L, Occurrence.endOf(ahead.record))
        val over = Occurrence("occ:default/E9/1", 5L, settled = true)
        assertEquals("5:f", over.record)
        assertTrue(!Occurrence.unsettled(over.record))
        // A record written before this rule carries the end alone, and
        // what that pass wrote was already the past tense.
        assertEquals(5L, Occurrence.endOf("5"))
        assertTrue(!Occurrence.unsettled("5"))
    }

    @Test
    fun `tasks and nameless rows are not events`() {
        assertTrue(calendarSubjects(listOf(row(noon).copy(cat = "todo"))).isEmpty())
        assertTrue(calendarSubjects(listOf(row(noon, title = ""))).isEmpty())
    }
}
