package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.usageLine
import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.mail.MailMessage
import io.nisfeb.talon.mail.MailThread
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daily brief against fixtures: a saved state view, one calendar
 * day, the actions waiting, and a reply. Nothing here reaches a ship
 * or a model.
 */
class OrreryBriefTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    private val zone = TimeZone.of("America/New_York")
    private val day = LocalDate(2026, 9, 19) // a Saturday
    private val now = ms("2026-09-19T11:00:00Z") // 07:00 in New York

    /** A state view as a key with sensitive: write reads it: health is listed, never returned. */
    private val state: JsonObject = Json.parseToJsonElement(
        """
        {"me": "person/me", "rev": 7,
         "schema": {"kinds": {
            "person": {"attrs": ["status", "location", "health", "income", "timezone"]},
            "activity": {"attrs": ["participants", "next", "last", "schedule"]},
            "situation": {"attrs": ["status", "starts", "ends", "participants"]},
            "thing": {"attrs": ["status", "location"]}},
          "actions": ["task", "message"]},
         "bodies": [
          {"id": "person/me", "kind": "person", "name": "Me", "aliases": [],
           "attrs": {"timezone": {"value": "America/New_York", "at": "2026-09-01T00:00:00Z"}}},
          {"id": "person/rose", "kind": "person", "name": "Rose", "aliases": ["daughter"], "attrs": {}},
          {"id": "thing/subaru", "kind": "thing", "name": "Subaru", "aliases": [],
           "attrs": {"status": {"value": "in the shop", "at": "2026-09-15T12:00:00Z"}}},
          {"id": "activity/swim-lessons", "kind": "activity", "name": "Swim lessons", "aliases": [],
           "attrs": {"next": {"value": "2026-09-19T21:00:00Z"},
                     "participants": [{"value": {"ref": "person/rose"}}]}},
          {"id": "situation/dentist", "kind": "situation", "name": "Dentist", "aliases": [],
           "attrs": {"starts": {"value": "2026-09-19T14:00:00Z"}}},
          {"id": "situation/grandmas", "kind": "situation", "name": "Grandma's", "aliases": [],
           "attrs": {"starts": {"value": "2026-09-19T18:00:00Z"}, "status": {"value": "cancelled"}}}
         ]}
        """.trimIndent(),
    ).jsonObject

    private fun event(name: String, l: String, r: String, all: Boolean = false, location: String = "") = CalendarRow(
        id = name.lowercase().replace(' ', '-'),
        meta = buildJsonObject {
            put("name", name)
            if (location.isNotEmpty()) put("location", location)
        },
        cat = if (all) "allday" else "timed",
        all = all,
        l = ms(l),
        r = ms(r),
    )

    private val events = listOf(
        event("Dentist", "2026-09-19T14:00:00Z", "2026-09-19T15:00:00Z", location = "Main St"),
        event("Farmers market", "2026-09-19T04:00:00Z", "2026-09-20T04:00:00Z", all = true),
        event("Church", "2026-09-20T14:00:00Z", "2026-09-20T15:00:00Z"),
    )

    private fun todo(name: String, due: String? = null, done: Boolean = false) = CalendarTask(
        id = name,
        cat = "todo",
        meta = buildJsonObject { put("name", name) },
        dueMs = due?.let(::ms),
        done = done,
    )

    private val todos = listOf(
        todo("Book the ferry", "2026-09-19T00:00:00Z"),
        todo("Renew passport", "2026-09-15T00:00:00Z"),
        todo("Call the plumber"),
        todo("Paid the gas bill", "2026-09-19T00:00:00Z", done = true),
        todo("Winterize the boat", "2026-10-15T00:00:00Z"),
    )

    private fun action(id: String, kind: String, title: String, status: String, due: String? = null, why: String? = null) =
        OrreryAction(
            id = id,
            kind = kind,
            title = title,
            payload = buildJsonObject { if (why != null) put("why", why) },
            about = listOf("person/rose"),
            due = due,
            status = status,
            by = "generator",
        )

    private val actions = listOf(
        action("1789-aaa", "task", "Buy swim goggles", "proposed", "2026-09-20T13:00:00Z", "Lessons are today and she has none."),
        action("1789-bbb", "message", "Tell Rose the dentist moved", "proposed"),
        action("1789-ccc", "task", "Pick up the Subaru", "approved"),
    )

    @Test
    fun `the day is the calendar, what orrery adds, then the todos`() {
        assertEquals(
            listOf(
                "All day  Farmers market",
                "10:00  Dentist, Main St",
                // Only in orrery; the dentist is on the calendar already,
                // and grandma's is cancelled.
                "17:00  Swim lessons",
                "To do  Renew passport (overdue)",
                "To do  Book the ferry",
                "To do  Call the plumber",
            ),
            Brief.today(day, zone, events, todos, state),
        )
    }

    @Test
    fun `only proposals wait, each under a tag`() {
        val (lines, tags) = Brief.waiting(actions, zone, Brief.names(state))
        assertEquals(mapOf("A1" to "1789-aaa", "A2" to "1789-bbb"), tags)
        assertEquals(
            listOf(
                "[A1] Buy swim goggles",
                "     task, about Rose, due Sun 20 Sep 09:00",
                "     Why: Lessons are today and she has none.",
                "[A2] Tell Rose the dentist moved",
                "     message, about Rose",
            ),
            lines,
        )
    }

    @Test
    fun `the rendered brief has its three parts and no dashes of the long kind`() {
        val today = Brief.today(day, zone, events, todos, state)
        val (waiting, _) = Brief.waiting(actions, zone, Brief.names(state))
        val mail = Brief.render(day, today, waiting, "The Subaru has been in the shop since Tuesday.")
        assertTrue(mail.startsWith("Today, Saturday 19 September\n\nAll day  Farmers market\n"), mail)
        assertTrue("\nWaiting on you\n[A1] Buy swim goggles\n" in mail, mail)
        assertTrue("\nSuggestions\nThe Subaru has been in the shop since Tuesday.\n" in mail, mail)
        assertFalse('—' in mail || '–' in mail, "no em or en dashes")
        assertEquals("Daily brief 2026-09-19", Brief.subject(day))
        assertEquals(day, Brief.dayOf("Re: Daily brief 2026-09-19"))
    }

    @Test
    fun `the model sees the state the generator's way, closed and over situations left out`() {
        val prompt = Brief.statePrompt(state, emptyList(), "2026-09-19T11:00:00Z", zone, listOf("10:00  Dentist"), emptyList())
        assertTrue("  thing/subaru | Subaru | status=in the shop" in prompt, prompt)
        assertTrue("  activity/swim-lessons | Swim lessons | next=2026-09-19T21:00:00Z; participants=person/rose" in prompt, prompt)
        assertTrue("situation/dentist | Dentist | upcoming" in prompt, prompt)
        assertFalse("grandmas" in prompt, "a cancelled situation is not shown")
        assertTrue(prompt.endsWith("Now: 2026-09-19T11:00:00Z, timezone America/New_York. Write the brief."))
    }

    @Test
    fun `seven in the owner's zone, or within the hour for a device that slept`() {
        assertEquals("America/New_York", Brief.zone(state).id)
        assertNull(Brief.dueDay(ms("2026-09-19T10:59:00Z"), zone))
        assertEquals(day, Brief.dueDay(ms("2026-09-19T11:00:00Z"), zone))
        assertEquals(day, Brief.dueDay(ms("2026-09-19T11:59:00Z"), zone))
        assertNull(Brief.dueDay(ms("2026-09-19T12:00:00Z"), zone))
        // The loop wakes for it: at 06:50 that is ten minutes off, and
        // just after seven it is tomorrow's.
        assertEquals(10 * 60_000L, Brief.untilNext(ms("2026-09-19T10:50:00Z"), zone))
        assertEquals(ms("2026-09-20T11:00:00Z") - ms("2026-09-19T11:05:00Z"), Brief.untilNext(ms("2026-09-19T11:05:00Z"), zone))
    }

    @Test
    fun `a phone gives a computer the first quarter hour`() {
        val grace = Brief.PHONE_GRACE_MS
        assertNull(Brief.dueDay(ms("2026-09-19T11:00:00Z"), zone, grace))
        assertNull(Brief.dueDay(ms("2026-09-19T11:14:00Z"), zone, grace))
        assertEquals(day, Brief.dueDay(ms("2026-09-19T11:15:00Z"), zone, grace))
        assertNull(Brief.dueDay(ms("2026-09-19T12:00:00Z"), zone, grace))
        assertEquals(15 * 60_000L, Brief.untilNext(ms("2026-09-19T11:00:00Z"), zone, grace))
    }

    private val briefText = Brief.render(day, listOf("10:00  Dentist"), listOf("[A1] Buy swim goggles"), "Nothing to add.")

    private val replyText = """
        approve A1. A2 due friday 3pm
        Rose has a cold.
        Waiting on you
        Add Magnus to swim lessons.

        On Sat, Sep 19, 2026 at 7:00 AM ~zod wrote:
        > Today, Saturday 19 September
        > [A1] Buy swim goggles
        Rose had a cold last year too.
    """.trimIndent()

    @Test
    fun `the quoted brief is stripped, and a line the brief said is not the owner's`() {
        assertEquals(
            "approve A1. A2 due friday 3pm\nRose has a cold.\nAdd Magnus to swim lessons.",
            Brief.ownWords(replyText, briefText),
        )
    }

    private val tags = mapOf("A1" to "1789-aaa", "A2" to "1789-bbb")
    private val known = setOf("person/me", "person/rose", "thing/subaru", "activity/swim-lessons", "situation/dentist", "situation/grandmas")

    @Test
    fun `the model's moves are held to the brief's tags, the statuses and the bodies that exist`() {
        val answer = Json.parseToJsonElement(
            """
            {"moves": [
               {"tag": "A1", "status": "approved"},
               {"tag": "[A2]", "due": "2026-09-25T19:00:00Z"},
               {"tag": "A9", "status": "approved"},
               {"tag": "A2", "status": "reopened"},
               {"tag": "A1", "about": ["person/ghost"]},
               {"tag": "A2", "about": ["person/rose", "thing/subaru"]}]}
            """.trimIndent(),
        ).jsonObject
        assertEquals(
            listOf(
                Brief.Direction("1789-aaa", status = "approved"),
                Brief.Direction("1789-bbb", dueMs = ms("2026-09-25T19:00:00Z"), about = listOf("person/rose", "thing/subaru")),
            ),
            Brief.movesOf(answer, tags, known),
        )
    }

    @Test
    fun `what the owner asks for is filed only as act takes it`() {
        val schema = Json.parseToJsonElement(
            """{"actions": ["task", "message"], "payloads": {"message": {"to": "required: the body id", "text": "required: the message"}}}""",
        ).jsonObject
        val answer = Json.parseToJsonElement(
            """
            {"actions": [
               {"kind": "task", "title": "Buy tissues", "about": ["person/rose"], "due": "2026-09-20T13:00:00Z"},
               {"kind": "home", "title": "Turn on the porch light"},
               {"kind": "task", "title": "Visit", "about": ["person/ghost"]},
               {"kind": "message", "title": "Tell Rose", "payload": {"to": "person/rose"}},
               {"kind": "task", "title": "  "}]}
            """.trimIndent(),
        ).jsonObject
        val filed = Brief.replyActions(answer, schema, known)
        assertEquals(1, filed.size)
        assertEquals(setOf("kind", "title", "about", "due", "payload"), filed[0].keys)
        assertEquals(JsonPrimitive("Buy tissues"), filed[0]["title"])
    }

    private val shapes = Json.parseToJsonElement(
        """{"actions": ["task", "note", "message", "home", "calendar"], "payloads": {
            "task": {"notes": "optional: what to do, in a sentence"},
            "calendar": {"title": "required", "starts": "required: ISO 8601 UTC", "ends": "optional: ISO 8601 UTC", "location": "optional"},
            "home": {"entity_id": "required: the entity"},
            "message": {"via": "required: one of telegram, mail, chat; the channel the conversation is on", "to": "required: the body id of the person, e.g. person/andrea", "text": "required: the message, short, in the owner's own voice; no em dashes, no semicolons or colons joining independent clauses, simple direct sentences of varied length"}}}""",
    ).jsonObject

    @Test
    fun `a reply's proposals are held to the schema's shapes, rule 14`() {
        val answer = Json.parseToJsonElement(
            """
            {"actions": [
               {"kind": "message", "title": "Tell Rose", "about": ["person/rose"], "payload": {"via": "Chat", "to": "person/rose", "text": "running late"}},
               {"kind": "message", "title": "Text Rose", "payload": {"via": "sms", "to": "person/rose", "text": "hi"}},
               {"kind": "message", "title": "Tell a ghost", "payload": {"via": "mail", "to": "person/ghost", "text": "boo"}},
               {"kind": "calendar", "title": "Swim meet", "payload": {"title": "Swim meet", "starts": "2026-09-20T19:00:00-04:00"}},
               {"kind": "calendar", "title": "Someday", "payload": {"title": "Someday", "starts": "Friday"}},
               {"kind": "home", "title": "Porch light", "payload": {"entity_id": "light.porch"}}]}
            """.trimIndent(),
        ).jsonObject
        val dropped = mutableListOf<String>()
        val filed = Brief.replyActions(answer, shapes, known) { dropped += it }
        assertEquals(listOf("Tell Rose", "Swim meet"), filed.map { it["title"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("chat"), filed[0]["payload"]!!.jsonObject["via"], "a listed value, as the list writes it")
        assertEquals(JsonPrimitive("2026-09-20T23:00:00Z"), filed[1]["payload"]!!.jsonObject["starts"], "sent as UTC")
        assertEquals(4, dropped.size, dropped.toString())
        assertTrue(dropped.any { "not one of telegram, mail, chat" in it }, dropped.toString())
        assertTrue(dropped.any { "person/ghost" in it }, dropped.toString())
        assertTrue(dropped.any { "home is not a reader's" in it }, dropped.toString())
    }

    @Test
    fun `the reply's context lists the kinds it may propose and their shapes`() {
        val prompt = Brief.analystPrompt(JsonObject(state + ("schema" to shapes)), emptyMap(), emptyMap(), "m-reply", now, "hi")
        assertTrue("Action kinds you may propose: task, calendar, message\n" in prompt, prompt)
        assertTrue(
            """  calendar payload: {"title": "required", "starts": "required: ISO 8601 UTC", "ends": "optional: ISO 8601 UTC", "location": "optional"}""" in prompt,
            prompt,
        )
        assertTrue("home payload" !in prompt && "Channel: mail" in prompt, prompt)
    }

    @Test
    fun `the reply is read with the analyst prompt, the tags and the clock`() {
        assertTrue(Brief.REPLY_SYSTEM.startsWith(Brief.ANALYST + "\n\n"))
        val prompt = Brief.analystPrompt(
            state, mapOf("person" to listOf("status", "health")), emptyMap(), "m-reply", now, "Rose has a cold.",
            tagged = mapOf("A1" to actions[0]), nowMs = now, zone = zone,
        )
        assertTrue("  A1 | task | Buy swim goggles | person/rose | 2026-09-20T13:00:00Z | proposed" in prompt, prompt)
        assertTrue("Now: 2026-09-19T11:00:00Z, timezone America/New_York." in prompt, prompt)
        assertTrue(prompt.endsWith("--- message m-reply | 2026-09-19T11:00:00Z | from person/me\nRose has a cold.\n---\nAnswer with the JSON object."), prompt)
    }

    @Test
    fun `a proposal is approved on its way to done, and a decision stands`() {
        assertEquals(listOf("approved", "done"), Brief.steps("proposed", "done"))
        assertEquals(listOf("done"), Brief.steps("approved", "done"))
        assertEquals(emptyList(), Brief.steps("dismissed", "approved"))
        assertEquals(emptyList(), Brief.steps("approved", "approved"))
    }

    @Test
    fun `a changed action is proposed again with only what act takes`() {
        val r = Brief.replacement(actions[0], ms("2026-09-25T19:00:00Z"), null)
        assertEquals(setOf("kind", "title", "about", "due", "payload"), r.keys)
        assertEquals(JsonPrimitive("2026-09-25T19:00:00Z"), r["due"])
    }

    @Test
    fun `the answer is held to the ship, conf is the owner's, and the reply is the source`() {
        val answer = Json.parseToJsonElement(
            """
            {"bodies": [{"id": "person/magnus", "name": "Magnus"}, {"id": "person/rosie", "name": "Rose", "aliases": ["Rosie"]}],
             "observations": [
               {"subject": "person/rosie", "attr": "health", "value": "a cold", "conf": 90},
               {"subject": "person/rose", "attr": "status", "value": "sick", "conf": 80},
               {"subject": "person/rose", "attr": "mood", "value": "grumpy"},
               {"subject": "person/rose", "attr": "favourite", "value": "blue"},
               {"subject": "person/ghost", "attr": "status", "value": "here"},
               {"subject": "activity/swim-lessons", "attr": "participants", "value": {"ref": "person/magnus"}},
               {"subject": "situation/grandmas", "attr": "status", "value": "cancelled"}],
             "actions": [{"kind": "task", "title": "Buy tissues"}]}
            """.trimIndent(),
        ).jsonObject
        val view = stateOf(state)
        val facts = Brief.replyFacts(
            answer,
            known = view.first,
            attrs = view.second,
            resolved = mapOf("person/rosie" to "person/rose"),
            replyId = "m-reply",
            atMs = now,
        )
        // Magnus is made; the Rose the model made twice is the one the
        // ship has, taught the name it was called.
        assertEquals(listOf(OBody("person/magnus", "Magnus"), OBody("person/rose", aliases = listOf("Rosie", "Rose"))), facts.bodies)
        assertEquals(
            listOf(
                "person/rose.health" to JsonPrimitive("a cold"),
                "person/rose.status" to JsonPrimitive("sick"),
                "activity/swim-lessons.participants" to buildJsonObject { put("ref", "person/magnus") },
                "situation/grandmas.status" to JsonPrimitive("cancelled"),
            ),
            facts.observations.map { "${it.subject}.${it.attr}" to it.value },
        )
        assertTrue(facts.observations.all { it.conf == 100 && it.sourceKind == "mail" && it.sourceId == "m-reply" && it.atMs == now })
    }

    private fun stateOf(o: JsonObject): Pair<Set<String>, Map<String, List<String>>> {
        val known = Brief.bodies(o).mapNotNull { (it["id"] as? JsonPrimitive)?.content }.toSet()
        val kinds = o["schema"]!!.jsonObject["kinds"]!!.jsonObject
        return known to kinds.mapValues { (_, k) -> (k.jsonObject["attrs"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content } }
    }

    private fun msg(id: String, from: String, prev: String?, subject: String = "Re: Daily brief 2026-09-19") =
        MailMessage(id = id, from = from, subject = subject, body = "", prev = prev, sent = now)

    @Test
    fun `a reply is read once, and only the owner's`() {
        val thread = MailThread(
            id = "t1",
            messages = listOf(
                msg("b1", "~zod", null, "Daily brief 2026-09-19"),
                msg("r1", "~zod", "b1"),
                msg("r2", "~bus", "b1"),
            ),
        )
        assertEquals("b1", Brief.briefOf(thread, "~zod")?.id)
        assertEquals(listOf("r1"), Brief.pendingReplies(thread, "~zod", handled = emptySet()).map { it.id })
        // The second run of the same reply: nothing to do, so nothing written.
        assertEquals(emptyList(), Brief.pendingReplies(thread, "~zod", handled = setOf("r1")))
    }

    @Test
    fun `a key covers the schema only with every kind, attribute and action kind`() {
        val full = Json.parseToJsonElement(
            """{"kinds": {"person": {"attrs": ["status", "health"]}, "note": {"attrs": []}}, "actions": ["task", "home"]}""",
        ).jsonObject
        val noHealth = Json.parseToJsonElement(
            """{"kinds": {"person": {"attrs": ["status"]}, "note": {"attrs": []}}, "actions": ["task", "home"]}""",
        ).jsonObject
        val noHome = Json.parseToJsonElement(
            """{"kinds": {"person": {"attrs": ["status", "health"]}, "note": {"attrs": []}}, "actions": ["task"]}""",
        ).jsonObject
        assertTrue(scopeCovers(full, full))
        assertFalse(scopeCovers(noHealth, full), "a key that may not write health")
        assertFalse(scopeCovers(noHome, full))
        assertFalse(scopeCovers(null, full))
    }

    @Test
    fun `each model call logs its tokens and cost`() {
        val body = Json.parseToJsonElement("""{"usage": {"input_tokens": 8556, "output_tokens": 2125}}""").jsonObject
        assertEquals("model claude-opus-5: 8556 in, 2125 out, $0.0959", usageLine(AiSettings.Provider.Anthropic, "claude-opus-5", body))
        val routed = Json.parseToJsonElement("""{"usage": {"prompt_tokens": 100, "completion_tokens": 20, "cost": 0.0012}}""").jsonObject
        assertEquals("model anthropic/claude-opus-5: 100 in, 20 out, $0.0012", usageLine(AiSettings.Provider.OpenRouter, "anthropic/claude-opus-5", routed))
    }

    @Test
    fun `a dismissal carries the owner's reason, and only when they gave one`() {
        val answer = Json.parseToJsonElement(
            """{"moves": [{"tag": "A1", "status": "dismissed", "reason": " just the event "}, {"tag": "A2", "status": "dismissed"}]}""",
        ).jsonObject
        assertEquals(
            listOf(Brief.Direction("1789-aaa", "dismissed", reason = "just the event"), Brief.Direction("1789-bbb", "dismissed")),
            Brief.movesOf(answer, tags, known),
        )
        assertTrue("never make one up" in Brief.REPLY_SYSTEM)
    }

    @Test
    fun `a reason is cut at five hundred bytes, never inside a character`() {
        assertEquals("short", clipBytes("short", 500))
        val cut = clipBytes("é".repeat(400), 500) // two bytes each
        assertEquals(250, cut.length)
        val emoji = clipBytes("🦐".repeat(200), 500) // four bytes, two chars each
        assertTrue(emoji.encodeToByteArray().size <= 500)
        assertTrue(!emoji.last().isHighSurrogate(), "no half of a pair left at the end")
        assertEquals(125, emoji.length / 2)
    }
}
