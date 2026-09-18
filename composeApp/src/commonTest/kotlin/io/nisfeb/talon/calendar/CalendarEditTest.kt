package io.nisfeb.talon.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CalendarEditTest {
    private val day = LocalDate(2026, 9, 14)

    @Test fun `a one-off timed event anchors on its wall clock, sent as utc`() {
        val b = eventBody(EventDraft(name = "Dentist", date = day, minuteOfDay = 14 * 60 + 30, durMin = 45, cal = "work"))
        assertEquals("add-event", b["action"]!!.jsonPrimitive.content)
        assertEquals("timed", b["cat"]!!.jsonPrimitive.content)
        assertEquals("once", b["kind"]!!.jsonPrimitive.content)
        assertEquals(1_789_396_200_000L, b["start_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals("45", b["dur_min"]!!.jsonPrimitive.content)
        assertEquals("work", b["cal"]!!.jsonPrimitive.content)
        assertNull(b["count"])
    }

    @Test fun `a weekly event anchors on the day and carries its time and days in args`() {
        val b = eventBody(EventDraft(name = "Standup", date = day, minuteOfDay = 9 * 60, repeat = Repeat.WEEKLY,
            weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), count = 10), id = "e1")
        assertEquals("edit-event", b["action"]!!.jsonPrimitive.content)
        assertEquals(1_789_344_000_000L, b["start_ms"]!!.jsonPrimitive.content.toLong(), "midnight utc of the day")
        val args = b["args"]!!.jsonObject
        assertEquals("540", args["at"]!!.jsonPrimitive.content)
        assertEquals(listOf("mon", "wed"), args["days"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("10", b["count"]!!.jsonPrimitive.content)
    }

    @Test fun `a date event is a month and a day, nothing else`() {
        val b = eventBody(EventDraft(name = "Birthday", date = LocalDate(2026, 3, 9), cat = EventCat.DATE))
        assertEquals("3", b["month"]!!.jsonPrimitive.content)
        assertEquals("9", b["day"]!!.jsonPrimitive.content)
        assertNull(b["kind"])
    }

    @Test fun `an event round-trips through its json`() {
        val d = EventDraft(name = "Standup", note = "daily", date = day, minuteOfDay = 9 * 60 + 15, repeat = Repeat.WEEKLY,
            weekdays = setOf(DayOfWeek.FRIDAY), count = 4, cal = "default")
        val json = eventBody(d, "e1").let { it.jsonObject }
        // event.json carries the same fields the poke does, plus id.
        val back = draftFromEvent(json, day)!!
        assertEquals(d.name, back.name)
        assertEquals(d.minuteOfDay, back.minuteOfDay)
        assertEquals(d.weekdays, back.weekdays)
        assertEquals(d.count, back.count)
        assertEquals(d.date, back.date)
    }

    @Test fun `the month grid starts on the monday on or before the first`() {
        val g = monthGrid(2026, 9)
        assertEquals(42, g.size)
        assertEquals(LocalDate(2026, 8, 31), g.first(), "1 Sep 2026 is a Tuesday")
        assertEquals(LocalDate(2026, 9, 1), g[1])
    }

    @Test fun `an all-day row spans its days and ends before r`() {
        val row = CalendarRow(id = "t", all = true, l = 1_789_344_000_000L, r = 1_789_344_000_000L + 2 * 86_400_000L)
        assertEquals(listOf(LocalDate(2026, 9, 14), LocalDate(2026, 9, 15)), daysOf(row, TimeZone.UTC))
    }

    @Test fun `tags ride in meta as an array and come back`() {
        assertEquals(listOf("work", "lunch"), parseTags(" work, #lunch,, work "))
        val d = EventDraft(name = "Lunch", date = day, tags = listOf("work", "lunch"))
        val b = eventBody(d)
        assertEquals(listOf("work", "lunch"), b["meta"]!!.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("work", "lunch"), draftFromEvent(eventBody(d, "e1"), day)!!.tags)
        assertNull(eventBody(EventDraft(name = "Plain", date = day))["meta"]!!.jsonObject["tags"], "no tags, no key")
    }

    @Test fun `monthly-nth and every carry their own arguments`() {
        val nth = eventBody(EventDraft(name = "Board", date = day, minuteOfDay = 10 * 60, repeat = Repeat.MONTHLY_NTH, ordinal = "second", nthDay = DayOfWeek.TUESDAY))
        val a = nth["args"]!!.jsonObject
        assertEquals("second", a["ord"]!!.jsonPrimitive.content)
        assertEquals("tue", a["day"]!!.jsonPrimitive.content)
        assertEquals("600", a["at"]!!.jsonPrimitive.content)
        val every = eventBody(EventDraft(name = "Pills", date = day, minuteOfDay = 8 * 60, repeat = Repeat.EVERY, periodMin = 720))
        assertEquals("720", every["args"]!!.jsonObject["period"]!!.jsonPrimitive.content)
        assertEquals(1_789_372_800_000L, every["start_ms"]!!.jsonPrimitive.content.toLong(), "every anchors on the moment, like once")
        assertNull(every["args"]!!.jsonObject["at"])
    }

    @Test fun `an imported rule is kept whole through an edit`() {
        val imported = io.nisfeb.talon.mail.AuspexApi.json.parseToJsonElement(
            """{"id":"x","cat":"timed","meta":{"name":"Standup"},"kind":"rrule","start_ms":1789372800000,"args":{"rrule":"FREQ=WEEKLY;BYDAY=MO"},"zone":"none","count":0,"fin":"dur","dur_min":15}""",
        ).jsonObject
        val d = draftFromEvent(imported, day)!!
        assertEquals("rrule", d.rawKind)
        val b = eventBody(d.copy(name = "Standup, renamed"), "x")
        assertEquals("rrule", b["kind"]!!.jsonPrimitive.content)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", b["args"]!!.jsonObject["rrule"]!!.jsonPrimitive.content)
        assertEquals("1789372800000", b["start_ms"]!!.jsonPrimitive.content)
        assertEquals("Standup, renamed", b["meta"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun `this one only becomes a one-off at the occurrence, this and following restarts the series there`() {
        val d = EventDraft(name = "Gym", date = LocalDate(2026, 9, 1), minuteOfDay = 7 * 60, repeat = Repeat.DAILY)
        val occurrence = kotlinx.datetime.LocalDateTime(2026, 9, 20, 7, 0)
        val only = onlyBody(d, occurrence)
        assertEquals("once", only["kind"]!!.jsonPrimitive.content)
        assertEquals("add-event", only["action"]!!.jsonPrimitive.content)
        assertEquals(LocalDate(2026, 9, 20).let { it.atTime(7, 0).toInstant(TimeZone.UTC).toEpochMilliseconds() }, only["start_ms"]!!.jsonPrimitive.content.toLong())
        val following = followingBody(d, occurrence)
        assertEquals("daily", following["kind"]!!.jsonPrimitive.content)
        assertEquals(LocalDate(2026, 9, 20).atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(), following["start_ms"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun `a task carries its due day at utc midnight and keeps its done moment`() {
        val open = eventBody(EventDraft(name = "Bins", cat = EventCat.TODO, date = day, due = day, cal = "home"))
        assertEquals("todo", open["cat"]!!.jsonPrimitive.content)
        assertEquals(1_789_344_000_000L, open["due_ms"]!!.jsonPrimitive.content.toLong())
        assertNull(open["done_ms"]); assertNull(open["kind"]); assertNull(open["start_ms"])
        val undated = eventBody(EventDraft(name = "Call mum", cat = EventCat.TODO, date = day))
        assertNull(undated["due_ms"])
        val done = eventBody(EventDraft(name = "Bins", cat = EventCat.TODO, date = day, done = true, doneMs = 1_789_300_000_000L), "t1")
        assertEquals("1789300000000", done["done_ms"]!!.jsonPrimitive.content, "an edit does not move the done moment")
        assertEquals("edit-event", done["action"]!!.jsonPrimitive.content)
        val tick = doneBody("t1", false)
        assertEquals("done-event", tick["action"]!!.jsonPrimitive.content)
        assertEquals("false", tick["done"]!!.jsonPrimitive.content)
    }

    @Test fun `a task read back keeps due, done and its moment`() {
        val e = io.nisfeb.talon.mail.AuspexApi.json.parseToJsonElement(
            """{"id":"t1","cal":"home","cat":"todo","meta":{"name":"Bins","tags":["chores"]},"due_ms":1789344000000,"done_ms":1789300000000,"done":true}""",
        ).jsonObject
        val d = draftFromEvent(e, LocalDate(2026, 1, 1))!!
        assertEquals(EventCat.TODO, d.cat)
        assertEquals(day, d.due)
        assertEquals(day, d.date)
        assertEquals(true, d.done)
        assertEquals(1_789_300_000_000L, d.doneMs)
        assertEquals(listOf("chores"), d.tags)
        assertEquals(false, d.repeats)
        val none = draftFromEvent(io.nisfeb.talon.mail.AuspexApi.json.parseToJsonElement("""{"id":"t2","cat":"todo","meta":{"name":"x"},"done":false}""").jsonObject, day)!!
        assertNull(none.due)
        assertEquals(day, none.date)
    }

    @Test fun `an all-day row is a utc day whatever the zone, a timed row is not`() {
        val ny = TimeZone.of("America/New_York")
        val utcMidnight = day.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        val allDay = CalendarRow(id = "a", all = true, l = utcMidnight, r = utcMidnight + 86_400_000L)
        assertEquals(listOf(day), daysOf(allDay, ny), "not the evening before")
        val (s, e) = allDay.bounds(ny)
        assertEquals(day.atTime(0, 0).toInstant(ny).toEpochMilliseconds(), s, "starts at the zone's midnight")
        assertEquals(day.plus(1, kotlinx.datetime.DateTimeUnit.DAY).atTime(0, 0).toInstant(ny).toEpochMilliseconds(), e)
        val timed = CalendarRow(id = "t", l = utcMidnight + 3_600_000L, r = utcMidnight + 7_200_000L)
        assertEquals(listOf(day.minus(1, kotlinx.datetime.DateTimeUnit.DAY)), daysOf(timed, ny), "01:00 utc is the evening before in new york")
        assertEquals(timed.l to timed.r, timed.bounds(ny))
    }

    @Test fun `a shared event is a day event over whole utc days and timed otherwise`() {
        val ny = TimeZone.of("America/New_York")
        val d0 = day.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        val allDay = sharedDraft("Fair", d0, d0 + 2 * 86_400_000L, "home", ny, "America/New_York")
        assertEquals(EventCat.ALLDAY, allDay.cat)
        assertEquals(day, allDay.date, "the utc day, not the evening before in new york")
        assertEquals(2, allDay.spanDays)
        val start = day.atTime(16, 0).toInstant(ny).toEpochMilliseconds()
        val timed = sharedDraft("Lunch", start, start + 90 * 60_000L, null, ny, "America/New_York")
        assertEquals(EventCat.TIMED, timed.cat)
        assertEquals(day, timed.date)
        assertEquals(16 * 60, timed.minuteOfDay)
        assertEquals(90, timed.durMin)
        assertEquals("America/New_York", timed.zone)
    }

    @Test fun `an until is written as the midnight after and read back as the last day`() {
        val until = LocalDate(2026, 9, 20)
        val d = EventDraft(name = "Term", date = day, repeat = Repeat.DAILY, until = until)
        val b = eventBody(d)
        assertEquals(
            until.plus(1, kotlinx.datetime.DateTimeUnit.DAY).atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
            b["until_ms"]!!.jsonPrimitive.content.toLong(),
            "until_ms is the day after the last occurrence, at utc midnight",
        )
        assertNull(b["count"])
        val back = draftFromEvent(eventBody(d, "e1"), day)!!
        assertEquals(until, back.until, "the last day itself survives the round trip")
        assertNull(eventBody(EventDraft(name = "x", date = day, repeat = Repeat.DAILY))["until_ms"], "no until, no key")
        val both = eventBody(EventDraft(name = "x", date = day, repeat = Repeat.DAILY, count = 3, until = until))
        assertEquals("3", both["count"]!!.jsonPrimitive.content)
        assertNull(both["until_ms"], "a count wins over an until")
    }

    @Test fun `an out-of-range at from a peer is coerced into the day`() {
        fun at(v: Int) = io.nisfeb.talon.mail.AuspexApi.json.parseToJsonElement(
            """{"id":"x","cat":"timed","meta":{"name":"Standup"},"kind":"daily","start_ms":1789344000000,"args":{"at":$v},"fin":"dur","dur_min":15}""",
        ).jsonObject
        assertEquals(1439, draftFromEvent(at(99999), day)!!.minuteOfDay)
        assertEquals(0, draftFromEvent(at(-5), day)!!.minuteOfDay)
    }

    @Test fun `an all-day occurrence is read as utc, a timed one in the zone`() {
        val ny = TimeZone.of("America/New_York")
        val utcMidnight = day.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        assertEquals(
            kotlinx.datetime.LocalDateTime(2026, 9, 14, 0, 0),
            occurrenceAt(utcMidnight, true, ny),
            "date-space: the same date whatever the zone",
        )
        assertEquals(
            kotlinx.datetime.LocalDateTime(2026, 9, 13, 20, 0),
            occurrenceAt(utcMidnight, false, ny),
            "a moment: midnight utc is the evening before in new york",
        )
    }

    @Test fun `a long all-day row keeps its end while the days shown are capped`() {
        val utcMidnight = day.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        val long = CalendarRow(id = "long", all = true, l = utcMidnight, r = utcMidnight + 90 * 86_400_000L)
        assertEquals(62, daysOf(long, TimeZone.UTC).size, "the grid never lists more than this")
        val (s, e) = long.bounds(TimeZone.UTC)
        assertEquals(utcMidnight, s)
        assertEquals(utcMidnight + 90 * 86_400_000L, e, "the full ninety days, not the capped list's end")
    }

    @Test fun `an edit keeps the colour and everything it does not show`() {
        // A task as the ship holds it, mirrored from orrery and coloured.
        val onShip = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"cat":"todo","cal":"default","done":false,"due_ms":null,
               "meta":{"name":"Book the ferry","note":"the description","color":"#c0392b",
                       "orrery":"act-123","priority":"5"}}""",
        ).jsonObject
        val d = assertNotNull(draftFromEvent(onShip, day))
        assertEquals("the description", d.note)
        assertEquals("#c0392b", d.color)
        val back = eventBody(d.copy(name = "Book the ferry, Friday"), id = "t1")["meta"]!!.jsonObject
        assertEquals("Book the ferry, Friday", back["name"]!!.jsonPrimitive.content, "the edit lands")
        assertEquals("#c0392b", back["color"]!!.jsonPrimitive.content, "the colour survives")
        assertEquals("act-123", back["orrery"]!!.jsonPrimitive.content, "and so does the link orrery finds it by")
        assertEquals("5", back["priority"]!!.jsonPrimitive.content, "and a field another client wrote")
        assertEquals("the description", back["note"]!!.jsonPrimitive.content)
    }
}
