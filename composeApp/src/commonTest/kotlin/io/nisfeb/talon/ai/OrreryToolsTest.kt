package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.OrreryApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
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
        var stateText = """{"bodies":[]}"""
        override suspend fun state() = Result.success(stateText)
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
        var told: Pair<String, Boolean>? = null
        var instructed = io.nisfeb.talon.orrery.Instructed(reply = "Noted.")
        override suspend fun instruct(text: String, apply: Boolean): Result<io.nisfeb.talon.orrery.Instructed> {
            told = text to apply
            return Result.success(instructed)
        }
        var struck: String? = null
        override suspend fun correct(subject: String, attr: String, value: kotlinx.serialization.json.JsonElement, why: String): Result<JsonObject> {
            struck = "$subject $attr $value $why".trim()
            return Result.success(buildJsonObject { put("id", "c1") })
        }
    }

    // ─── telling orrery, and striking what it holds ─────────────────

    @Test
    fun `the owner's words go to the ship, and what it filed is read back`() = runTest {
        val t = Tap()
        t.instructed = io.nisfeb.talon.orrery.Instructed(
            reply = "Sam and Samuel look like one person.",
            actions = listOf(io.nisfeb.talon.orrery.OrreryAction("m1", "merge", "Fold Samuel into Sam", JsonObject(emptyMap()), emptyList(), null, "proposed", "owner")),
            note = "",
        )
        val said = run(t, "orrery_instruct", buildJsonObject { put("text", "Sam and Samuel are one person") })
        assertEquals("Sam and Samuel are one person" to false, t.told, "filed for the owner to approve, not applied")
        assertEquals("Orrery says: Sam and Samuel look like one person.\nFiled:\n- merge: Fold Samuel into Sam (proposed)", said)
        run(t, "orrery_instruct", buildJsonObject { put("text", "never propose calls"); put("apply", true) })
        assertEquals("never propose calls" to true, t.told)
        t.instructed = io.nisfeb.talon.orrery.Instructed(note = "Orrery has made all of today's model calls. Try again tomorrow.")
        assertTrue("today's model calls" in run(t, "orrery_instruct", buildJsonObject { put("text", "x") }), "a refusal is said")
    }

    @Test
    fun `a wrong value is struck as a string or a body, never both`() = runTest {
        val t = Tap()
        val said = run(t, "orrery_correct", buildJsonObject { put("subject", "person/andrea"); put("attr", "location"); put("ref", "place/barcelona"); put("why", "she stayed home") })
        assertEquals("""person/andrea location {"ref":"place/barcelona"} she stayed home""", t.struck)
        assertTrue("Struck" in said, said)
        run(t, "orrery_correct", buildJsonObject { put("subject", "person/sam"); put("attr", "status"); put("value", "on jury duty") })
        assertEquals("""person/sam status "on jury duty"""", t.struck)
        t.struck = null
        assertTrue("not both" in run(t, "orrery_correct", buildJsonObject { put("subject", "a/b"); put("attr", "c"); put("value", "x"); put("ref", "d/e") }))
        assertTrue("Error" in run(t, "orrery_correct", buildJsonObject { put("subject", "a/b"); put("attr", "c") }))
        assertEquals(null, t.struck, "nothing half-said reached the ship")
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

    // orrery-0c, 2026-10-07: the assistant could not create Mark Beale,
    // since the tool sent only observations, and it wrote him into a
    // situation's participants as text as well as a ref.
    @Test
    fun `someone new goes with the facts about them, and is named by ref`() = runTest {
        val t = Tap()
        run(t, "orrery_observe", buildJsonObject {
            put("bodies", """[{"id":"person/mark-beale","name":"Mark Beale"}]""")
            put("observations", """[{"subject":"situation/pantry-quote","attr":"participants","value":["person/mark-beale",{"ref":"person/owner"}]},{"subject":"person/mark-beale","attr":"job","value":"carpenter"}]""")
        })
        assertEquals(
            """{"bodies":[{"id":"person/mark-beale","name":"Mark Beale"}],"observations":[{"subject":"situation/pantry-quote","attr":"participants","value":[{"ref":"person/mark-beale"},{"ref":"person/owner"}]},{"subject":"person/mark-beale","attr":"job","value":"carpenter"}]}""",
            t.observed.toString(),
        )
        assertTrue("bodies must be a JSON array" in run(t, "orrery_observe", buildJsonObject {
            put("bodies", """{"id":"person/x"}""")
            put("observations", "[]")
        }))
    }

    @Test
    fun `only a whole body id becomes a ref`() {
        fun sent(v: String) = refBodyIds(Json.parseToJsonElement("""{"subject":"person/a","attr":"x","value":$v}""")).toString()
        assertTrue(""""value":{"ref":"place/home"}""" in sent("\"place/home\""))
        // Text that only contains one, or a kind orrery has not, or a slug it would refuse, stays text.
        assertTrue(""""value":"meet at place/home"""" in sent("\"meet at place/home\""))
        assertTrue(""""value":"event/x"""" in sent("\"event/x\""))
        assertTrue(""""value":"person/Mark"""" in sent("\"person/Mark\""))
        assertTrue(""""value":3""" in sent("3"))
    }

    // orrery-0c, 2026-10-07: the assistant put an appointment on the
    // calendar, then told orrery the same with apply, and orrery placed
    // a second copy. Every tool a model reads names the one path.
    @Test
    fun `an event has one path, and the tools that could double it say so`() = runTest {
        val specs = orreryTools(Tap()).associate { it.spec.name to it.spec.description }
        assertTrue("Not for adding an event" in specs.getValue("orrery_instruct"))
        assertTrue("calendar placements included" in orreryTools(Tap()).first { it.spec.name == "orrery_instruct" }.spec.parameters.toString())
        assertTrue("create_event, and only there" in run(Tap(), "orrery_guide"))
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
        assertEquals(setOf("orrery_observe", "orrery_configure", "orrery_set_preferences", "orrery_file_text", "orrery_register", "orrery_instruct", "orrery_correct"), byWrite[true]?.toSet())
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

    // ─── the state view reaches the model whole, or cut at a boundary ───

    private fun body(i: Int) = """{"id":"person/p$i","kind":"person","name":"Person $i","aliases":[],"values":{"status":"${"x".repeat(200)}"}}"""

    @Test
    fun `a brief view that fits is handed over as it came`() = runTest {
        val tap = Tap(); tap.stateText = """{"bodies":[${body(1)}],"situations":[],"kinds":{"person":["status"]}}"""
        assertEquals(tap.stateText, tools(tap).getValue("orrery_read").execute(JsonObject(emptyMap())))
    }

    @Test
    fun `a view too long loses list elements from the end, stays JSON, and says what was cut`() = runTest {
        val bodies = (1..400).joinToString(",") { body(it) }   // ~100 KB
        val tap = Tap(); tap.stateText = """{"bodies":[$bodies],"situations":[{"id":"situation/s1","needs":"a date"}],"kinds":{"person":["status"]}}"""
        val out = tools(tap).getValue("orrery_read").execute(JsonObject(emptyMap()))
        assertTrue(out.length <= STATE_CHARS, "within the cap: ${out.length}")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(out).jsonObject
        val kept = parsed.getValue("bodies").jsonArray
        assertTrue(kept.size in 1 until 400, "some bodies kept, not all: ${kept.size}")
        assertEquals("person/p1", kept.first().jsonObject.getValue("id").jsonPrimitive.content, "cut from the end, the first stay")
        assertEquals(1, parsed.getValue("situations").jsonArray.size, "the small list is whole")
        assertEquals("""{"person":["status"]}""", parsed.getValue("kinds").toString(), "an object key is whole")
        val note = parsed.getValue("_cut").jsonPrimitive.content
        assertTrue("bodies: ${kept.size} of 400 kept" in note && "situations" !in note, note)
    }

    @Test
    fun `text that is not an object is cut at a line, with the note`() {
        val lines = (1..3000).joinToString("\n") { "line $it is here" }
        val out = clipJson(lines, 6000)
        assertTrue(out.length <= 6000 + 120 && out.lines().dropLast(1).all { it.startsWith("line ") }, "whole lines only")
        assertTrue("cut here" in out && "ask for one body" in out)
        assertTrue("cut here, ${lines.length - 6000} more characters" in out, "says how much was left out")
    }

    // Many short elements: the per-element count is what keeps the cut
    // under the cap, so it has to be counted right.
    @Test
    fun `a view of many short elements is cut to within the room`() {
        val view = "{\"bodies\":[" + (1..3000).joinToString(",") { "\"b$it\"" } + "]}"
        val out = clipJson(view, 6000)
        assertTrue(out.length <= 6000, "${out.length}")
        assertTrue("bodies:" in kotlinx.serialization.json.Json.parseToJsonElement(out).jsonObject.getValue("_cut").jsonPrimitive.content)
    }

    @Test
    fun `a view exactly the room there is is handed over whole`() {
        val view = "{\"bodies\":[" + (1..200).joinToString(",") { "\"b$it\"" } + "]}"
        assertEquals(view, clipJson(view, view.length))
    }

    // A long view with no list to shorten: cut at a line, and said so.
    @Test
    fun `a long view with nothing to drop is cut at a line, with the note`() {
        val view = buildJsonObject { (1..40).forEach { put("k$it", "v".repeat(200)) } }.toString()
        val out = clipJson(view, 2000)
        assertTrue(out.length <= 2000 + 60, "${out.length}")
        assertTrue(out.endsWith("… cut here; ask for one body instead."), out.takeLast(80))
    }
}
