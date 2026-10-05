package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wire half: what goes up, on which client, and how the answers read. */
class OrreryApiTest {
    private var seen: HttpRequestData? = null

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): OrreryApi {
        val engine = MockEngine { req -> seen = req; respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return OrreryApi(owner = HttpClient(engine), bare = HttpClient(engine), baseUrl = "https://ship/")
    }

    @Test
    fun `the probe reads presence off the status`() = runTest {
        assertEquals(OrreryAvailability.PRESENT, api().probe())
        assertEquals(OrreryAvailability.MISSING, api(HttpStatusCode.NotFound).probe())
        assertEquals(OrreryAvailability.SIGNED_OUT, api(HttpStatusCode.Forbidden).probe())
        assertEquals("https://ship/apps/orrery/api/state?kind=none", seen!!.url.toString())
    }

    @Test
    fun `minting asks for the pipe's scope and keeps the token`() = runTest {
        val key = api(body = """{"id":"k1","name":"Talon on x","by":"talon/x","scope":{},"token":"k1.secret","made":"2026-09-17T00:00:00Z"}""")
            .mint("Talon on x", "talon/x")
        assertEquals(MintedKey("k1", "k1.secret"), key)
        val sent = Json.parseToJsonElement((seen!!.body as TextContent).text).jsonObject
        assertEquals("talon/x", sent["by"]!!.jsonPrimitive.content)
        val scope = sent["scope"]!!.jsonObject
        assertEquals(OrreryApi.KINDS, scope["kinds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(OrreryApi.ACTIONS, scope["actions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", scope["write"]!!.jsonPrimitive.content)
    }

    // Checked against orrery's serve-read (version 59): text required,
    // title and source optional, answered at once with the item's id.
    @Test
    fun `text to read goes up as orrery's read route takes it, on the owner's session`() = runTest {
        val said = api(body = """{"ok":true,"id":"1790441000000-0xab12"}""").read("- buy candles\n- book the hall", "Todos for the party")
        assertEquals("https://ship/apps/orrery/api/read", seen!!.url.toString())
        assertEquals("POST", seen!!.method.value)
        assertEquals(
            """{"text":"- buy candles\n- book the hall","title":"Todos for the party","source":{"kind":"talon"}}""",
            (seen!!.body as TextContent).text,
        )
        assertEquals("1790441000000-0xab12", said["id"]!!.jsonPrimitive.content)
    }

    // Checked against orrery's de-correct (version 60): subject a body id,
    // attr, value a string or {"ref"}, why optional and at most 500 bytes.
    @Test
    fun `a correction goes up as orrery's correct route takes it`() = runTest {
        api(body = """{"id":"c1","subject":"person/andrea","attr":"location","value":"place/barcelona","why":"","at":"2026-09-28T00:00:00Z","by":"owner"}""")
            .correct("person/andrea", "location", buildJsonObject { put("ref", "place/barcelona") }, "  she stayed home ")
        assertEquals("https://ship/apps/orrery/api/correct", seen!!.url.toString())
        assertEquals(
            """{"subject":"person/andrea","attr":"location","value":{"ref":"place/barcelona"},"why":"she stayed home"}""",
            (seen!!.body as TextContent).text,
        )
        val record = api(body = """{"id":"c7"}""").correct("person/sam", "status", kotlinx.serialization.json.JsonPrimitive("on jury duty"), "")
        assertEquals("c7", record["id"]!!.jsonPrimitive.content, "the ship's record comes back")
        assertEquals("""{"subject":"person/sam","attr":"status","value":"on jury duty"}""", (seen!!.body as TextContent).text, "no reason, no why")
    }

    // Checked against orrery's serve-instruct: 503 no model key, 429 the
    // day's calls spent, 502 the model failed. Each is said, not thrown.
    @Test
    fun `an instruction's refusals are answers the owner can read`() = runTest {
        assertEquals("Orrery has made all of today's model calls. Try again tomorrow.", api(HttpStatusCode.TooManyRequests, """{"error":"the day's model calls are spent"}""").instruct("x").note)
        assertEquals("Orrery's model did not answer: the model answered 500", api(HttpStatusCode.BadGateway, """{"error":"the model answered 500"}""").instruct("x").note)
        assertTrue(api(HttpStatusCode.ServiceUnavailable, """{"error":"the generator has no key"}""").instruct("x").note.startsWith("Orrery has no model key"))
        assertEquals("text: over 2000 bytes", api(HttpStatusCode.BadRequest, """{"error":"text: over 2000 bytes"}""").instruct("x").note, "any other refusal in the ship's words")
        assertEquals("Could not find Samuel.", api(body = """{"ok":true,"reply":"","actions":[],"note":"Could not find Samuel."}""").instruct("x").note)
        val ok = api(body = """{"ok":true,"reply":"Done.","actions":[{"id":"p1","kind":"preference","title":"Never propose calls","payload":{"text":"never propose calls"},"status":"approved","by":"owner"}],"note":""}""")
            .instruct("never propose calls", apply = true)
        assertEquals("Done." to listOf("p1"), ok.reply to ok.actions.map { it.id })
        assertEquals("""{"text":"never propose calls","apply":true}""", (seen!!.body as TextContent).text)
    }

    // An approved action the executor could not carry out: status
    // failed, the reason in its note, and when in its last history step.
    @Test
    fun `a failed action reads out with its reason and when it failed`() = runTest {
        val row = { history: String -> """{"id":"m1","kind":"message","title":"Tell Bus","payload":{},"about":[],"status":"failed","by":"generator","note":"no DM with ~bus","history":$history}""" }
        val steps = """[{"at":"2026-09-27T09:00:00Z","status":"approved","by":"owner"},{"at":"2026-09-28T09:00:00Z","status":"failed","by":"executor"}]"""
        val failed = api(body = "[" + row(steps) + "]").actions(null, "failed").single()
        assertEquals("no DM with ~bus", failed.note)
        assertEquals(kotlinx.datetime.Instant.parse("2026-09-28T09:00:00Z").toEpochMilliseconds(), failed.movedMs, "the last step, not the first")
        assertEquals(null, api(body = "[" + row("[]") + "]").actions(null, "failed").single().movedMs)
        val garbled = """[{"at":"soon","status":"failed","by":"executor"}]"""
        assertEquals(null, api(body = "[" + row(garbled) + "]").actions(null, "failed").single().movedMs)
        assertEquals("https://ship/apps/orrery/api/actions?status=failed", seen!!.url.toString())
    }

    @Test
    fun `observing carries the key and reads per-item answers`() = runTest {
        val answer = api(body = """{"bodies":[{"id":"person/bus","ok":true,"existing":false}],"observations":[{"id":"1-a","ok":true,"existing":true},{"ok":false,"error":"unknown subject thing/x"}]}""")
            .observe(buildJsonObject { }, token = "k1.secret")
        assertEquals("Bearer k1.secret", seen!!.headers[HttpHeaders.Authorization])
        assertNull(seen!!.headers[HttpHeaders.Cookie], "the key request carries no cookie")
        assertEquals(1, answer.refused.size)
        assertEquals("unknown subject thing/x", answer.refused.single().error)
        assertEquals(true, answer.observations[0].existing)
    }

    @Test
    fun `the brief view is asked for with brief=1, the whole one without`() = runTest {
        val api = api(body = """{"bodies":[]}""")
        api.stateJson("k1.secret", brief = true)
        assertEquals("https://ship/apps/orrery/api/state?brief=1", seen!!.url.toString())
        assertEquals("Bearer k1.secret", seen!!.headers[HttpHeaders.Authorization])
        api.stateJson("k1.secret")
        assertEquals("https://ship/apps/orrery/api/state", seen!!.url.toString())
    }

    @Test
    fun `travel is read with the key, its last pass with the owner's session`() = runTest {
        val keyed = mutableListOf<HttpRequestData>()
        val owned = mutableListOf<HttpRequestData>()
        val api = OrreryApi(
            owner = HttpClient(MockEngine { req -> owned += req; respond("""{"next":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            bare = HttpClient(MockEngine { req -> keyed += req; respond("""{"enabled":false,"lead_min":10}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            baseUrl = "https://ship/",
        )
        assertEquals(null, io.nisfeb.talon.orrery.readLeavePlan(api, "k1.secret"))
        assertEquals("https://ship/apps/orrery/api/travel", keyed.single().url.toString())
        assertEquals("Bearer k1.secret", keyed.single().headers[HttpHeaders.Authorization])
        assertEquals(emptyList(), owned, "off: the pass's record is not asked for")
        api.travelLast()
        assertEquals("https://ship/apps/orrery/api/travel/last", owned.single().url.toString())
        assertEquals(null, owned.single().headers[HttpHeaders.Authorization], "the owner's, by cookie, not the key")
    }

    @Test
    fun `every call made with a key carries it, and no cookie`() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val bare = HttpClient(MockEngine { req -> sent += req; respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        val api = OrreryApi(owner = HttpClient(MockEngine { error("a key call went on the owner's client") }), bare = bare, baseUrl = "https://ship/")
        val t = "k1.secret"
        val calls: List<Pair<String, suspend () -> Unit>> = listOf(
            "state" to { api.stateJson(t) }, "keyLanded" to { api.keyLanded(t) }, "act" to { api.act(buildJsonObject { }, t) },
            "resolve" to { api.resolve("sarah", t) }, "observationsOf" to { api.observationsOf("person/x", t) },
            "actions" to { api.actions(t) }, "refine" to { api.refine(t, "a", "sooner") },
            "transition" to { api.transition(t, "a", "done") }, "generate" to { api.generate(t, emptyList()) },
            "observe" to { api.observe(buildJsonObject { }, t) },
        )
        for ((name, call) in calls) {
            val before = sent.size
            // An answer of the wrong shape is not the point here.
            runCatching { call() }
            val made = sent.drop(before)
            kotlin.test.assertTrue(made.isNotEmpty(), "$name asked nothing")
            for (r in made) {
                assertEquals("Bearer $t", r.headers[HttpHeaders.Authorization], "$name: ${r.url}")
                assertNull(r.headers[HttpHeaders.Cookie], name)
            }
        }
    }

    @Test
    fun `a body's timeline reads out with the ids only the ship can know`() = runTest {
        val rows = api(
            body = """{"id":"activity/standup","observations":[
                {"id":"o-1","attr":"last","value":"2026-09-17T12:00:00Z","at":"2026-09-17T12:00:00Z",
                 "source":{"kind":"calendar","id":"default/E9"},"status":"live"},
                {"id":"o-2","attr":"started","value":"x","at":"2026-09-10T12:00:00Z",
                 "source":{"kind":"calendar","id":"default/E9"},"status":"retracted"},
                {"attr":"next","at":"2026-09-24T12:00:00Z","status":"live"}]}""",
        ).observationsOf("activity/standup", "k1.secret")
        assertEquals("https://ship/apps/orrery/api/body/activity/standup", seen!!.url.toString())
        assertEquals("Bearer k1.secret", seen!!.headers[HttpHeaders.Authorization])
        assertEquals(listOf("o-1", "o-2"), rows.map { it.id }, "a row with no id is not one we can take back")
        assertEquals(1_789_646_400_000L, rows.first().atMs)
        assertEquals("default/E9", rows.first().sourceId)
        assertEquals(listOf(true, false), rows.map { it.stands })
    }

    @Test
    fun `open actions read out, and a transition goes up with its note`() = runTest {
        val list = api(body = """[{"id":"1758-a","kind":"message","title":"Tell Sarah","payload":{"via":"chat","to":"person/sarah","text":"on my way"},"about":["person/sarah"],"due":null,"by":"claude-code","proposed":"2026-09-17T00:00:00Z","status":"approved","note":"","history":[]}]""")
            .actions("k1.secret")
        val a = list.single()
        assertEquals("message", a.kind)
        assertEquals(MessageToSend("chat", "person/sarah", "on my way"), a.messageToSend())
        assertEquals(listOf("person/sarah"), a.about)
        assertEquals("Bearer k1.secret", seen!!.headers[HttpHeaders.Authorization])
        api().transition("k1.secret", "1758-a", "failed", "the ship was down")
        assertEquals("https://ship/apps/orrery/api/actions/1758-a", seen!!.url.toString())
        val sent = Json.parseToJsonElement((seen!!.body as TextContent).text).jsonObject
        assertEquals("failed", sent["status"]!!.jsonPrimitive.content)
        assertEquals("the ship was down", sent["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a calendar action becomes an event, with no end unless it says one`() {
        val a = OrreryAction("x", "calendar", "Dentist", kotlinx.serialization.json.buildJsonObject { put("start", kotlinx.serialization.json.JsonPrimitive("2026-09-18T09:00:00Z")) }, emptyList(), null, "approved", "claude-code")
        val e = a.eventToAdd()!!
        assertEquals("Dentist", e.title)
        assertEquals(null, e.endMs, "placing it gives it the hour")
        assertEquals(null, a.copy(kind = "task").eventToAdd())
    }

    @Test
    fun `the owner's key list says when each was used`() = runTest {
        val keys = api(body = """[{"id":"k1","name":"Talon on Desktop (Linux)","by":"talon/desktop-linux","scope":{},"made":"2026-09-17T00:00:00Z","used":"2026-09-17T12:00:00Z"},{"id":"k2","name":"Talon on Android 17","by":"talon/android-17","scope":{},"made":"2026-09-17T00:00:00Z","used":null}]""")
            .clients()
        assertEquals(listOf("talon/desktop-linux", "talon/android-17"), keys.map { it.by })
        assertEquals(1_789_646_400_000L, keys[0].usedMs)
        assertNull(keys[1].usedMs)
        assertEquals("https://ship/apps/orrery/api/clients", seen!!.url.toString())
    }

    @Test
    fun `a refusal names the ship's reason`() = runTest {
        val e = assertFailsWith<OrreryError.Refused> { api(HttpStatusCode.Forbidden, """{"error":"read only key"}""").observe(buildJsonObject { }, "t") }
        assertEquals(403, e.status)
        assertEquals("read only key", e.reason)
    }

    @Test
    fun `a refused key or schema is a refusal, not an answer that could not be read`() = runTest {
        val mint = assertFailsWith<OrreryError.Refused> { api(HttpStatusCode.InternalServerError, """{"error":"no keys today"}""").mint("Talon on x", "talon/x") }
        assertEquals(500 to "no keys today", mint.status to mint.reason)
        val schema = assertFailsWith<OrreryError.Refused> { api(HttpStatusCode.ServiceUnavailable, "down").schema() }
        assertEquals(503, schema.status, "a proxy with no ship behind it cost the ship nothing")
        assertFailsWith<OrreryError.Garbled> { api(body = "not json").mint("Talon on x", "talon/x") }
    }
}
