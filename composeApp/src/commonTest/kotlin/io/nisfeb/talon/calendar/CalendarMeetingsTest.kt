package io.nisfeb.talon.calendar

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Meetings as the calendar's page writes them (calendar.js, app.hoon on
 * version 34): "only this one" as edit-at, the owner's answer, and the
 * guests and video call an edit writes into meta.
 */
class CalendarMeetingsTest {
    private val day = LocalDate(2026, 9, 14)
    private fun meta(json: String) = Json.parseToJsonElement(json).jsonObject

    // The bodies, exactly: app.hoon's edit-at reads id, home and at_ms,
    // then parse-event (cat, meta with a name, kind, start_ms, zone, fin,
    // dur_min or span_days) and parse-alarms. The page sends no cal and
    // no args.
    @Test fun `edit-at is the page's body for a timed occurrence`() {
        val d = EventDraft(
            name = "Standup", date = day, minuteOfDay = 17 * 60, durMin = 15, zone = "America/New_York", cal = "work",
            repeat = Repeat.WEEKLY, count = 4, alarms = listOf(CalAlarm.before(900)),
            otherMeta = meta("""{"conference":"https://meet.google.com/abc"}"""),
        )
        assertEquals(
            """{"action":"edit-at","id":"s1","home":"work","at_ms":1789340400000,"cat":"timed",""" +
                """"meta":{"conference":"https://meet.google.com/abc","name":"Standup"},"kind":"once",""" +
                """"alarms":[{"kind":"before","s":900,"desc":""}],"start_ms":1789405200000,"zone":"America/New_York","fin":"dur","dur_min":15}""",
            editAtBody(d, "s1", "work", 1_789_340_400_000L).toString(),
        )
    }

    @Test fun `edit-at moves an all-day occurrence to another day, with no reminders where the calendar has none`() {
        val d = EventDraft(name = "Away", cat = EventCat.ALLDAY, date = LocalDate(2026, 9, 15), spanDays = 2)
        assertEquals(
            """{"action":"edit-at","id":"a1","at_ms":1789344000000,"cat":"allday","meta":{"name":"Away"},"kind":"once","start_ms":1789430400000,"span_days":2}""",
            editAtBody(d, "a1", null, 1_789_344_000_000L).toString(),
        )
    }

    // app.hoon's split-event reads id, home and idx (the series ends
    // before it, as cap-event's dom), then add-event's own fields and
    // calendar for the new one.
    @Test fun `this and following is one split-event, add-event's fields and the series named`() {
        val d = EventDraft(name = "Lesson", date = day, minuteOfDay = 17 * 60, durMin = 45, zone = "America/New_York", cal = "family", repeat = Repeat.WEEKLY, weekdays = setOf(kotlinx.datetime.DayOfWeek.MONDAY))
        assertEquals(
            """{"action":"split-event","id":"f1","home":"family","idx":5,"cat":"timed","meta":{"name":"Lesson"},"cal":"family",""" +
                """"kind":"weekly","start_ms":1789344000000,"args":{"at":1020,"days":["mon"]},"zone":"America/New_York","fin":"dur","dur_min":45}""",
            splitBody(d, "f1", "family", 5).toString(),
        )
    }

    @Test fun `rsvp is the page's body`() {
        assertEquals("""{"action":"rsvp","id":"g1","home":"work","status":"tentative"}""", rsvpBody("g1", "work", "tentative").toString())
    }

    private val invited = meta(
        """{"name":"Planning","guests":[{"email":"Ana@Example.com","name":"Ana","status":"accepted"},{"email":"me@example.com","status":"needs-action","self":true}],""" +
            """"ships":[{"ship":"~bus","status":"tentative"},{"ship":"~zod","status":"accepted","self":true}]}""",
    )

    @Test fun `the guests are listed by name, email or ship, with their answers, and the owner apart`() {
        val g = guestsOf(invited)
        assertEquals(listOf("Ana", "me@example.com", "~bus", "~zod"), g.map { it.label })
        assertEquals(listOf("going", "no answer", "maybe", "going"), g.map { answerWords(it.status) })
        assertEquals(listOf(false, true, false, true), g.map { it.self })
        assertEquals("not going", answerWords("declined"))
        assertEquals("Ana@Example.com, ~bus", guestText(invited))
        // A peer can send anything: what is not a guest is left out, not a crash.
        assertEquals(listOf(""), guestsOf(meta("""{"guests":"everyone","ships":[3,{"ship":{"x":1}}]}""")).map { it.label })
    }

    @Test fun `an unchanged guests field writes nothing, spaces and all`() {
        assertSame(invited, withGuests(invited, " Ana@Example.com ,~bus, "))
        assertNull(guestsProblem(invited, "Ana@Example.com, ~bus", google = false), "an unchanged field is never refused")
        val plain = meta("""{"name":"Lunch"}""")
        assertSame(plain, withGuests(plain, ""), "no empty guests where there were none")
        assertEquals(plain, draftMeta(EventDraft(name = "Lunch", date = day, guests = "")), "nor through an edit")
    }

    @Test fun `a guest kept keeps their answer, the owner stays, and a new one has none yet`() {
        // Matched without case, and kept as it was written.
        val m = withGuests(invited, "ANA@example.com, ~bus, ~nec, bo@example.com")
        assertEquals(
            """[{"email":"me@example.com","status":"needs-action","self":true},{"email":"Ana@Example.com","name":"Ana","status":"accepted"},{"email":"bo@example.com","status":"needs-action"}]""",
            m["guests"].toString(),
        )
        assertEquals(
            """[{"ship":"~zod","status":"accepted","self":true},{"ship":"~bus","status":"tentative"},{"ship":"~nec","status":"needs-action"}]""",
            m["ships"].toString(),
        )
        assertEquals("Planning", m.str("name"))
    }

    @Test fun `the owner typed in as a guest is still the one entry it was`() {
        val m = withGuests(invited, "~bus, ~zod, me@example.com")
        assertEquals("""[{"ship":"~zod","status":"accepted","self":true},{"ship":"~bus","status":"tentative"}]""", m["ships"].toString())
        assertEquals("""[{"email":"me@example.com","status":"needs-action","self":true}]""", m["guests"].toString())
    }

    @Test fun `taking every guest off leaves the owner, and a ship alone makes no email list`() {
        val m = withGuests(invited, "")
        assertEquals("""[{"email":"me@example.com","status":"needs-action","self":true}]""", m["guests"].toString())
        assertEquals("""[{"ship":"~zod","status":"accepted","self":true}]""", m["ships"].toString())
        val s = withGuests(meta("""{"name":"Tea"}"""), "~nec")
        assertEquals("""{"name":"Tea","ships":[{"ship":"~nec","status":"needs-action"}]}""", s.toString())
    }

    @Test fun `a ship name, an email, and emails only on Google, as the page checks them`() {
        val plain = meta("""{"name":"Tea"}""")
        assertEquals("Not a ship name: ~Nec", guestsProblem(plain, "~Nec", google = true))
        assertEquals("Not a ship name: ~ne", guestsProblem(plain, "~ne", google = true))
        assertNull(guestsProblem(plain, "~sampel-palnet, ~nec, ~dabnev-nisseb--nidsut-tomdun", google = false), "ships work on any calendar")
        assertEquals("Not an email or a ship: bob", guestsProblem(plain, "bob", google = true))
        assertEquals("Not an email or a ship: bo@example", guestsProblem(plain, "bo@example", google = true))
        assertEquals("Email guests need a calendar linked to Google", guestsProblem(plain, "bo@example.com", google = false))
        assertNull(guestsProblem(plain, "bo@example.com", google = true))
    }

    @Test fun `Meet asks for a call, None takes the ask away, Keep and an untouched choice leave meta alone`() {
        val base = EventDraft(name = "Sync", date = day, otherMeta = meta("""{"call":"meet","conference":"https://meet.google.com/x"}"""))
        assertEquals("meet", draftMeta(base.copy(otherMeta = JsonObject(emptyMap()), call = VideoCall.MEET)).str("call"))
        assertEquals("""{"conference":"https://meet.google.com/x","name":"Sync"}""", draftMeta(base.copy(call = VideoCall.NONE)).toString())
        assertEquals("""{"call":"meet","conference":"https://meet.google.com/x","name":"Sync"}""", draftMeta(base.copy(call = VideoCall.KEEP)).toString())
        assertEquals(draftMeta(base.copy(call = VideoCall.KEEP)), draftMeta(base))
        // A call is a timed event's: an all-day one is left as it came.
        assertEquals("meet", draftMeta(base.copy(cat = EventCat.ALLDAY, call = VideoCall.NONE)).str("call"))
        assertEquals(VideoCall.KEEP, callOf(base.otherMeta))
        assertEquals(VideoCall.MEET, callOf(meta("""{"call":"meet"}""")))
        assertEquals(VideoCall.NONE, callOf(meta("""{}""")))
    }

    @Test fun `a task or a date writes no guests, whatever the field says`() {
        for (cat in listOf(EventCat.TODO, EventCat.DATE)) {
            assertNull(draftMeta(EventDraft(name = "x", cat = cat, date = day, guests = "~nec", call = VideoCall.MEET))["ships"], cat.name)
        }
    }

    // Everything the editor does not change goes back as it came: the
    // organizer, the host of a copy, the join link, and keys no one here knows.
    @Test fun `an edit passes every meeting key through untouched`() {
        val m = meta(
            """{"name":"Planning","organizer":{"email":"o@example.com"},"host":"~nec","conference":"https://zoom.us/j/1","call":"meet",""" +
                """"guests":[{"email":"a@example.com","status":"accepted"}],"ships":[{"ship":"~bus","status":"declined"}],"x-other":{"k":[1]}}""",
        )
        val event = Json.parseToJsonElement("""{"cat":"timed","kind":"once","start_ms":1789396200000,"dur_min":30,"meta":$m}""").jsonObject
        val d = draftFromEvent(event, day)!!
        assertEquals(m, eventBody(d, "e1")["meta"])
        assertEquals(m, eventBody(d.copy(guests = guestText(m), call = callOf(m)), "e1")["meta"], "the editor's own starting values change nothing")
    }

    // Google mails email guests on every change; ships hear from their
    // own calendars. A copy's guests are its host's.
    @Test fun `Google mails only email guests, and a copy is one with a host`() {
        assertEquals(true, mailsGuests("~bus, ana@example.com"))
        assertEquals(false, mailsGuests(" ~bus , ~nec "))
        assertEquals(false, mailsGuests(""))
        assertEquals(true, isCopy(meta("""{"name":"Seed swap","host":"~nec"}""")))
        assertEquals(false, isCopy(meta("""{"name":"Seed swap","host":""}""")))
        assertEquals(false, isCopy(meta("""{"name":"Planning"}""")))
    }

    @Test fun `a join link is http or https, nothing else`() {
        assertEquals("https://meet.google.com/abc", joinLink(meta("""{"conference":"https://meet.google.com/abc"}""")))
        assertEquals("HTTP://zoom.us/j/1", joinLink(meta("""{"conference":"HTTP://zoom.us/j/1"}""")))
        for (bad in listOf("javascript:alert(1)", "ftp://x", "https://a b", "")) assertNull(joinLink(meta("""{"conference":${kotlinx.serialization.json.JsonPrimitive(bad)}}""")), bad)
        assertNull(joinLink(meta("""{"conference":{"uri":"https://x"}}""")))
    }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
