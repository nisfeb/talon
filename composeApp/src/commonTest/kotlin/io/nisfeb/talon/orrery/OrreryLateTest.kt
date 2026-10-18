package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Orrery 110's late nags, Talon's half: which actions are late, as the
 * ship's +late-high has it, and what a late push's buttons send, pinned
 * against orrery's serve-mark and serve-set-action. sneagan forgot to
 * check in for a flight until at the airport.
 */
class OrreryLateTest {
    private val now = 1_792_350_000_000L // 2026-10-18T19:00:00Z

    private fun iso(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()

    private fun action(
        id: String = "a1",
        status: String = "approved",
        kind: String = "task",
        due: String? = iso(now - 2 * 3_600_000 - 10 * 60_000),
        payload: String = """{"priority":"high"}""",
    ) = OrreryAction(id, kind, "Check in for DL 2627", Json.parseToJsonElement(payload).jsonObject, emptyList(), due, status, "owner")

    @Test
    fun late_is_open_and_high_and_past_its_due_and_not_snoozed() {
        assertTrue(action().lateAt(now))
        assertTrue(action(status = "proposed").lateAt(now), "a proposal nags too")
        assertFalse(action(status = "claimed").lateAt(now), "the ship marks only proposed or approved")
        assertFalse(action(status = "done").lateAt(now))
        assertFalse(action(payload = "{}").lateAt(now), "an item does not start urgent")
        assertFalse(action(payload = """{"priority":""}""").lateAt(now))
        assertFalse(action(due = null).lateAt(now), "only a due makes one late")
        assertFalse(action(due = iso(now)).lateAt(now), "due now is not late yet")
        assertTrue(action(due = iso(now - 1)).lateAt(now))
        assertFalse(action(due = iso(now + 60_000)).lateAt(now))
        assertFalse(action(payload = """{"priority":"high","snooze_until":"${iso(now + 1)}"}""").lateAt(now), "snoozed")
        assertTrue(action(payload = """{"priority":"high","snooze_until":"${iso(now)}"}""").lateAt(now), "a snooze that ends now is over")
        assertTrue(action(payload = """{"priority":"high","snooze_until":"${iso(now - 60_000)}"}""").lateAt(now))
        assertTrue(action(due = "2026-10-17").lateAt(now), "a bare date is that day at midnight UTC")
    }

    @Test
    fun the_late_section_lists_the_late_ones_newest_due_first() {
        val older = action("old", due = iso(now - 86_400_000))
        val newer = action("new", due = iso(now - 60_000))
        val quiet = action("quiet", payload = "{}")
        assertEquals(listOf("new", "old"), lateActions(listOf(older, quiet, newer), now).map { it.id })
        assertEquals(emptyList<OrreryAction>(), lateActions(listOf(quiet), now))
    }

    @Test
    fun how_late_is_said_as_orrery_page_says_it() {
        assertEquals("0 min", lateSpan(29_999))
        assertEquals("1 min", lateSpan(30_000))
        assertEquals("59 min", lateSpan(59 * 60_000L))
        assertEquals("1 h", lateSpan(60 * 60_000L))
        assertEquals("2 h 10 min", lateSpan(130 * 60_000L))
        assertEquals("due Sun 18 Oct 16:50, 2 h 10 min late", lateLine(action(), now, TimeZone.UTC, twentyFourHour = true))
        assertEquals("", lateLine(action(due = null), now, TimeZone.UTC, twentyFourHour = true))
    }

    @Test
    fun a_mark_is_held_until_the_list_shows_it() {
        val a = action(payload = "{}")
        assertTrue(a.marked("priority", "high").high)
        assertEquals("{}", a.marked("priority", "high").marked("priority", "").payload.toString(), "an empty value takes it off")
        assertFalse(markShown(a, "priority", "high"))
        assertTrue(markShown(a.marked("priority", "high"), "priority", "high"))
        assertTrue(markShown(a, "priority", ""), "none is shown as not high")
        assertTrue(markShown(null, "snooze_until", "2026-10-18T20:00:00Z"), "gone from the list holds nothing")
    }

    @Test
    fun what_the_ship_carries_out_is_never_done_from_here() {
        assertFalse(action().shipCarriesOut)
        assertTrue(action(kind = "message", payload = """{"via":"chat","to":"~bus","text":"late"}""").shipCarriesOut)
        assertTrue(action(kind = "calendar", payload = """{"title":"Dentist","starts":"2026-10-19T09:00:00Z"}""").shipCarriesOut)
        assertTrue(action(kind = "fact", payload = """{"subject":"person/me","attr":"x","value":"y"}""").shipCarriesOut)
    }

    @Test
    fun a_failure_is_said_in_a_line_without_codes() {
        assertEquals("Done did not go through: your ship did not answer. Try it again.", markProblemLine("Done", OrreryError.Unreachable(RuntimeException("timeout"))))
        assertEquals("Snooze did not go through: only an open action is marked.", markProblemLine("Snooze", OrreryError.Refused(409, "only an open action is marked")))
        assertEquals("Not high did not go through: Orrery gave no reason.", markProblemLine("Not high", OrreryError.Refused(500, "<html>")))
    }

    // ─── a late push's buttons, as the owner ─────────────────────────

    private class Ship {
        val sent = mutableListOf<String>()
        /** The open list, by the status asked for. */
        var open: (String) -> String = { "[]" }
        var answer: (String, String) -> Pair<HttpStatusCode, String> = { _, _ -> HttpStatusCode.OK to """{"ok":true}""" }
        var down = false
        val api = OrreryApi(
            HttpClient(MockEngine { req ->
                if (down) error("connection refused")
                val path = req.url.encodedPath
                val json = headersOf("Content-Type", "application/json")
                if (req.method.value == "GET") return@MockEngine respond(open(req.url.parameters["status"] ?: ""), HttpStatusCode.OK, json)
                val body = req.body.toByteArray().decodeToString()
                sent += "${req.method.value} $path $body"
                val (status, said) = answer(path, body)
                respond(said, status, json)
            }),
            HttpClient(MockEngine { error("the key's client is not the owner's") }),
            "https://ship.test",
        )
    }

    private fun row(status: String, kind: String = "task", payload: String = """{"priority":"high"}""") =
        """[{"id":"a1","kind":"$kind","title":"Check in","payload":$payload,"due":"2026-10-18T16:50:00Z","status":"$status"}]"""

    @Test
    fun snooze_holds_the_nags_an_hour() = runTest {
        val ship = Ship().apply { answer = { _, _ -> HttpStatusCode.OK to """{"ok":true,"id":"a1","snooze_until":"2026-10-18T20:00:00Z"}""" } }
        assertNull(ship.api.lateMove("a1", LateMove.SNOOZE))
        // serve-mark reads minutes as a number (gn); a string is a 400.
        assertEquals(listOf("""POST /apps/orrery/api/actions/a1/snooze {"minutes":60}"""), ship.sent)
    }

    @Test
    fun done_on_an_approved_one_is_one_move() = runTest {
        val ship = Ship().apply { open = { if (it == "open") row("approved") else "[]" } }
        assertNull(ship.api.lateMove("a1", LateMove.DONE))
        assertEquals(listOf("""POST /apps/orrery/api/actions/a1 {"status":"done"}"""), ship.sent)
    }

    // transition-ok: a proposal goes to approved or dismissed and nowhere
    // else, and the ship answers before its writer applies a move.
    @Test
    fun done_on_a_proposal_approves_and_waits_for_it_to_land() = runTest {
        var landed = false
        val ship = Ship()
        ship.open = { s -> when (s) { "open" -> row("proposed"); "approved" -> if (landed) row("approved") else "[]"; else -> "[]" } }
        ship.answer = { _, body -> if ("approved" in body) landed = true; HttpStatusCode.OK to """{"ok":true}""" }
        assertNull(ship.api.lateMove("a1", LateMove.DONE))
        assertEquals(
            listOf("""POST /apps/orrery/api/actions/a1 {"status":"approved"}""", """POST /apps/orrery/api/actions/a1 {"status":"done"}"""),
            ship.sent,
        )
    }

    @Test
    fun done_on_a_proposal_that_never_shows_approved_says_so() = runTest {
        val ship = Ship().apply { open = { if (it == "open") row("proposed") else "[]" } }
        assertEquals("Done did not go through: approved, but not yet marked done. Try Done again.", ship.api.lateMove("a1", LateMove.DONE))
        assertEquals(listOf("""POST /apps/orrery/api/actions/a1 {"status":"approved"}"""), ship.sent, "done is not sent before approved shows")
    }

    @Test
    fun done_on_one_no_longer_open_sends_nothing() = runTest {
        val ship = Ship()
        assertNull(ship.api.lateMove("a1", LateMove.DONE), "done elsewhere: the nag is taken back")
        assertEquals(emptyList<String>(), ship.sent)
    }

    @Test
    fun done_on_one_the_ship_carries_out_is_left_to_the_app() = runTest {
        val ship = Ship().apply { open = { row("proposed", kind = "message", payload = """{"priority":"high","via":"chat","to":"~bus","text":"late"}""") } }
        assertEquals("Open Talon to answer this one: the ship carries it out.", ship.api.lateMove("a1", LateMove.DONE))
        assertEquals(emptyList<String>(), ship.sent, "approving it would send it")
    }

    @Test
    fun a_refusal_or_no_answer_is_the_line_the_push_shows() = runTest {
        val ship = Ship().apply { open = { if (it == "open") row("approved") else "[]" } }
        ship.answer = { _, _ -> HttpStatusCode.Conflict to """{"error":"cannot go from claimed to done"}""" }
        assertEquals("Done did not go through: cannot go from claimed to done.", ship.api.lateMove("a1", LateMove.DONE))
        ship.answer = { _, _ -> HttpStatusCode.Forbidden to """{"error":"read only key"}""" }
        assertEquals("Snooze did not go through: read only key.", ship.api.lateMove("a1", LateMove.SNOOZE))
        ship.answer = { _, _ -> HttpStatusCode.NotFound to """{"error":"no such action"}""" }
        assertEquals("Snooze did not go through: no such action.", ship.api.lateMove("a1", LateMove.SNOOZE))
        ship.down = true
        assertEquals("Done did not go through: your ship did not answer. Try it again.", ship.api.lateMove("a1", LateMove.DONE))
        assertEquals("Snooze did not go through: your ship did not answer. Try it again.", ship.api.lateMove("a1", LateMove.SNOOZE))
    }

    // ─── the routes themselves ───────────────────────────────────────

    @Test
    fun priority_and_snooze_send_what_serve_mark_reads_and_answer_what_it_stores() = runTest {
        val ship = Ship()
        ship.answer = { path, _ ->
            HttpStatusCode.OK to if (path.endsWith("/priority")) """{"ok":true,"id":"a1","priority":"high"}""" else """{"ok":true,"id":"a1","snooze_until":""}"""
        }
        assertEquals("high", ship.api.priority(null, "a1", high = true))
        ship.api.priority(null, "a1", high = false)
        assertEquals("", ship.api.snooze(null, "a1", 0))
        assertEquals(
            listOf(
                """POST /apps/orrery/api/actions/a1/priority {"priority":"high"}""",
                """POST /apps/orrery/api/actions/a1/priority {"priority":""}""",
                """POST /apps/orrery/api/actions/a1/snooze {"minutes":0}""",
            ),
            ship.sent,
        )
    }

    @Test
    fun the_version_is_read_and_an_orrery_without_the_route_is_old() = runTest {
        var said: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"version":110}"""
        val api = OrreryApi(
            HttpClient(MockEngine { req ->
                assertEquals("/apps/orrery/api/version", req.url.encodedPath)
                respond(said.second, said.first, headersOf("Content-Type", "application/json"))
            }),
            HttpClient(MockEngine { error("not the key's") }),
            "https://ship.test",
        )
        assertEquals(110, api.version())
        said = HttpStatusCode.NotFound to """{"error":"no such route"}"""
        assertEquals(0, api.version())
    }
}
