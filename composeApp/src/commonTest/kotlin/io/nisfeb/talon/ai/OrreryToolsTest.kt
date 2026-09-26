package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.OrreryApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tools are thin, so what is worth holding is what they refuse
 * and what they let through: the ship is on the other side of them
 * and a model is on this one.
 */
class OrreryToolsTest {

    /** What was asked of orrery, and what it was told to answer. */
    private class Tap(
        val hits: List<Pair<String, String>> = emptyList(),
        val answer: List<String> = listOf("1: written"),
    ) : OrreryTap {
        var wroteTo: String? = null
        var wrote: JsonObject? = null
        var observed: JsonObject? = null
        var read: String? = null

        override suspend fun find(q: String) = Result.success(hits)
        override suspend fun state() = Result.success("""{"bodies":[]}""")
        override suspend fun body(id: String) = Result.success(listOf("obs=1 attr=status"))
        override suspend fun observe(batch: JsonObject): Result<List<String>> {
            observed = batch
            return Result.success(answer)
        }
        override suspend fun settings(name: String): Result<String> {
            read = name
            return Result.success("""{"enabled":true,"token_set":true}""")
        }
        override suspend fun configure(name: String, body: JsonObject): Result<String> {
            wroteTo = name; wrote = body
            return Result.success("""{"ok":true}""")
        }
        var registered: String? = null
        override suspend fun register(name: String): Result<String> {
            registered = name
            return Result.success("""{"ok":true,"description":"Webhook was set"}""")
        }
        override suspend fun registration(name: String): Result<String> =
            Result.success("""{"url":"https://my.ship/apps/orrery/telegram","pending_update_count":0,"last_error_message":null}""")

        var prefs = io.nisfeb.talon.orrery.OrreryPreferences("Short.", listOf("No calls before 9am", "Plain words"))
        var changed: Triple<String?, String?, String?>? = null
        override suspend fun preferences() = Result.success(prefs)
        override suspend fun changePreferences(add: String?, remove: String?, style: String?): Result<io.nisfeb.talon.orrery.OrreryPreferences> {
            changed = Triple(add, remove, style)
            prefs = io.nisfeb.talon.orrery.OrreryPreferences(style ?: prefs.style, (prefs.list - listOfNotNull(remove)) + listOfNotNull(add))
            return Result.success(prefs)
        }
        var handed: Pair<String, String?>? = null
        var handAnswer = """{"ok":true,"id":"1790441000000-0xab12"}"""
        override suspend fun hand(text: String, title: String?): Result<JsonObject> {
            handed = text to title
            return Result.success(kotlinx.serialization.json.Json.parseToJsonElement(handAnswer) as JsonObject)
        }
    }

    // ─── preferences and handing orrery text ────────────────────────

    @Test
    fun `the owner's preferences are read out, numbered`() = runTest {
        assertEquals("style: Short.\n1. No calls before 9am\n2. Plain words", run(Tap(), "orrery_preferences"))
    }

    @Test
    fun `a preference is added, one taken off by its number, and the style cleared`() = runTest {
        val t = Tap()
        run(t, "orrery_set_preferences", buildJsonObject { put("add", "Never propose Mondays") })
        assertEquals(Triple("Never propose Mondays", null, null), t.changed)
        run(t, "orrery_set_preferences", buildJsonObject { put("remove", "1") })
        assertEquals(Triple(null, "No calls before 9am", null), t.changed, "a number is the preference it names")
        val said = run(t, "orrery_set_preferences", buildJsonObject { put("style", "") })
        assertEquals(Triple(null, null, ""), t.changed, "an empty style is a style of none, not nothing to change")
        assertTrue("style: (none)" in said, said)
        assertTrue("Error" in run(t, "orrery_set_preferences"), "nothing to change is said")
        assertTrue("no preference 9" in run(t, "orrery_set_preferences", buildJsonObject { put("remove", "9") }))
    }

    @Test
    fun `text is handed to orrery whole, and a closed read channel is said`() = runTest {
        val t = Tap()
        val todos = "- buy candles\n- book the hall for Oct 3, 6pm\n- invite the Egans"
        val said = run(t, "orrery_file_text", buildJsonObject { put("text", todos); put("title", "Todos for the party") })
        assertEquals(todos to "Todos for the party", t.handed)
        assertTrue("1790441000000-0xab12" in said && "Actions" in said, said)
        t.handAnswer = """{"ok":true,"dropped":"the read channel is off"}"""
        assertTrue("the read channel is off" in run(t, "orrery_file_text", buildJsonObject { put("text", "x") }))
        assertTrue("Error" in run(t, "orrery_file_text"), "no text, nothing sent")
    }

    private fun tools(t: OrreryTap) = orreryTools(t).associateBy { it.spec.name }

    private suspend fun run(t: OrreryTap, name: String, args: JsonObject = buildJsonObject {}) =
        tools(t)[name]!!.execute(args)

    // A name from a model lands in a URL path. Anything that is not
    // one of orrery's own documents is answered, not sent.
    @Test
    fun `a document orrery does not have is never asked for`() = runTest {
        val t = Tap()
        listOf("clients", "../clients", "state", "telegram/../schema").forEach { bad ->
            val said = run(t, "orrery_settings", buildJsonObject { put("document", bad) })
            assertTrue("no orrery settings document" in said, said)
        }
        assertEquals(null, t.read, "nothing reached the ship")

        val refused = run(t, "orrery_configure", buildJsonObject {
            put("document", "../clients"); put("settings", """{"x":1}""")
        })
        assertTrue("no orrery settings document" in refused, refused)
        assertEquals(null, t.wroteTo, "nothing was written")
    }

    @Test
    fun `a document orrery does have goes through as it was given`() = runTest {
        val t = Tap()
        val said = run(t, "orrery_configure", buildJsonObject {
            put("document", "telegram")
            put("settings", """{"enabled":true,"chats":["-100123"]}""")
        })
        assertEquals("telegram", t.wroteTo)
        // Passed on whole: what the fields mean is orrery's business,
        // and a wrapper that knew would be a wrapper to keep in step.
        assertEquals("""{"enabled":true,"chats":["-100123"]}""", t.wrote.toString())
        assertTrue("Sent to telegram" in said, said)
    }

    @Test
    fun `what is not JSON is not sent as JSON`() = runTest {
        val t = Tap()
        val said = run(t, "orrery_configure", buildJsonObject {
            put("document", "generator"); put("settings", "enabled, please")
        })
        assertTrue("must be a JSON object" in said, said)
        assertEquals(null, t.wroteTo)

        val empty = run(t, "orrery_configure", buildJsonObject {
            put("document", "generator"); put("settings", "{}")
        })
        assertTrue("nothing to change" in empty, empty)
        assertEquals(null, t.wroteTo)
    }

    @Test
    fun `observations are a batch, and a refusal is read back`() = runTest {
        val t = Tap(answer = listOf("1: written", "2: refused, unknown attr"))
        val said = run(t, "orrery_observe", buildJsonObject {
            put("observations", """[{"subject":"person/alice","attr":"status","value":"away"}]""")
        })
        assertEquals(
            """{"observations":[{"subject":"person/alice","attr":"status","value":"away"}]}""",
            t.observed.toString(),
        )
        assertTrue("2: refused, unknown attr" in said, "the ship's own answer, per item")

        assertTrue("must be a JSON array" in run(t, "orrery_observe", buildJsonObject {
            put("observations", """{"subject":"person/alice"}""")
        }))
    }

    @Test
    fun `the rules are a tool a model can ask for`() = runTest {
        val said = run(Tap(), "orrery_guide")
        assertTrue("resolves a name to a body id" in said, said)
        // The guide carries what the ship will refuse, so the model
        // does not learn it by being refused in front of the owner.
        assertTrue("16 bytes" in said, said)
    }

    // A confirmation on every read teaches people to tap yes without
    // reading it, so only the two that change the ship ask.
    @Test
    fun `changing the ship asks first, looking does not`() {
        val byWrite = orreryTools(Tap()).groupBy({ it.write }, { it.spec.name })
        assertEquals(setOf("orrery_observe", "orrery_configure", "orrery_set_preferences", "orrery_file_text", "orrery_register"), byWrite[true]?.toSet())
        assertEquals(
            setOf("orrery_guide", "orrery_find", "orrery_read", "orrery_settings", "orrery_preferences"),
            byWrite[false]?.toSet(),
        )
    }

    // Registering is the one step of setting up a reader that is not a
    // document write, and a 200 from it is not delivery: the tool reads
    // back what the service holds in the same breath.
    @Test
    fun `a registration goes through and is read back, or is refused by name`() = runTest {
        val t = Tap()
        val said = run(t, "orrery_register", buildJsonObject { put("document", "telegram") })
        assertEquals("telegram", t.registered)
        assertTrue("Webhook was set" in said, said)
        assertTrue("pending_update_count" in said, said)

        val bad = run(t, "orrery_register", buildJsonObject { put("document", "telegram/../wake") })
        assertTrue("no orrery registration" in bad, bad)
        assertEquals("telegram", t.registered, "the bad name never reached the ship")
    }

    @Test
    fun `every document the api allows is offered by name`() {
        val said = orreryTools(Tap()).first { it.spec.name == "orrery_configure" }.spec.description
        OrreryApi.SETTINGS.forEach { assertTrue(it in said, "$it is not named in the description") }
    }

    // Orrery 39's chat reader: its DMs and channels are there to pick
    // from, and a list the ship holds is not a document anyone writes.
    @Test
    fun `the chat reader's lists are read, never written`() = runTest {
        val t = Tap()
        run(t, "orrery_settings", buildJsonObject { put("document", "chat/dms") })
        assertEquals("chat/dms", t.read)
        val said = run(t, "orrery_configure", buildJsonObject { put("document", "chat/dms"); put("settings", """{"x":1}""") })
        assertTrue("no orrery settings document" in said, said)
        assertEquals(null, t.wroteTo, "never sent")
        run(t, "orrery_configure", buildJsonObject { put("document", "chat"); put("settings", """{"enabled":true}""") })
        assertEquals("chat", t.wroteTo, "the reader's own document is written as any other")
    }

    // Telegram pushes to the ship, so only an address the internet
    // reaches can be its public_url, and the ship adds the path itself.
    @Test
    fun `the ship's address is offered for telegram only where the internet reaches it`() {
        val public = shipUrlHint("https://urbit.example.com/apps/talon")
        assertTrue("at https://urbit.example.com." in public && "is the `public_url`" in public, public)
        for (home in listOf("http://localhost:8080", "https://192.168.1.4", "https://172.20.0.2:8443", "http://my.ship.example")) {
            assertTrue("not a `public_url`" in shipUrlHint(home), home)
        }
    }
}
