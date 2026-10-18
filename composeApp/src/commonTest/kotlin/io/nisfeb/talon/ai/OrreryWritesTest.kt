package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Instructed
import io.nisfeb.talon.orrery.ItemAnswer
import io.nisfeb.talon.orrery.ObserveAnswer
import io.nisfeb.talon.orrery.OrreryAction
import io.nisfeb.talon.orrery.OrreryError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Orrery's writes as the assistant makes them, each read back. sneagan,
 * 2026-10-18: the assistant could not change or remove a value or answer
 * a proposal, and said changes were made that the view did not show.
 */
class OrreryWritesTest {
    private val jax = "situation/2026-10-18-dl-2627"

    /** A body as orrery's +body-json gives it: each winner's row under attrs, the timeline with sources. */
    private fun view(attrs: String = "{}", rows: String = "[]", id: String = jax) =
        """{"id":"$id","kind":"situation","name":"DL 2627","attrs":$attrs,"involved":[],"actions":[],"observations":$rows}"""

    private fun winner(value: String, kind: String = "user") =
        """{"value":$value,"at":"2026-10-18T12:00:00Z","until":null,"conf":100,"source":{"kind":"$kind","id":"x"},"by":"owner","obs":"o1"}"""

    private fun row(id: String, attr: String, value: String, kind: String = "user", src: String = "talon", status: String = "live") =
        """{"id":"$id","attr":"$attr","value":$value,"at":"2026-10-18T12:00:00Z","status":"$status","source":{"kind":"$kind","id":"$src"}}"""

    private suspend fun run(t: OrreryTap, name: String, args: JsonObject) =
        orreryTools(t).first { it.spec.name == name }.execute(args)

    private fun set(vararg kv: Pair<String, Any>) = buildJsonObject {
        put("subject", jax)
        kv.forEach { (k, v) -> if (v is Boolean) put(k, v) else put(k, v.toString()) }
    }

    // ─── changing a value ───────────────────────────────────────────

    // orrery's serve-observe: an owner's row keeps the by it names, and
    // one with no at is dated by the ship's own clock (+fill-obs).
    @Test
    fun `a value is filed as the owner's word dated by the ship and read back confirmed`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views[jax] = mutableListOf(
            view("""{"needs":${winner("\"park at JAX\"")}}""", "[${row("o1", "needs", "\"park at JAX\"")}]"),
            view("""{"needs":${winner("\"terminal drop-off\"")}}"""),
        )
        val said = run(t, "orrery_set", set("attr" to "needs", "value" to "terminal drop-off"))
        assertEquals("""{"observations":[{"subject":"$jax","attr":"needs","value":"terminal drop-off","by":"owner"}]}""", t.observed.toString())
        assertEquals(true, t.asOwner)
        assertEquals("Confirmed: $jax needs shows \"terminal drop-off\".", said)
        assertNull(t.struck, "a plan that changed is not a wrong value")
        assertEquals(2, t.viewReads, "read first, then once more: the read back stops once it shows")
    }

    // The report: "it reported some changes as done when the view still
    // showed old values".
    @Test
    fun `a value the record does not show is not confirmed and says what it still shows`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views[jax] = mutableListOf(view("""{"needs":${winner("\"park at JAX\"")}}"""))
        val said = run(t, "orrery_set", set("attr" to "needs", "value" to "terminal drop-off"))
        assertTrue(said.startsWith("Not confirmed (the record still shows \"park at JAX\"): $jax needs was to be \"terminal drop-off\"."), said)
        assertTrue(NOT_CONFIRMED in said && "Confirmed:" !in said, said)
    }

    @Test
    fun `the old value is struck first when it was wrong and never the new one`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views[jax] = mutableListOf(view("""{"needs":${winner("\"park at JAX\"")}}"""), view("""{"needs":${winner("\"terminal drop-off\"")}}"""))
        run(t, "orrery_set", set("attr" to "needs", "value" to "terminal drop-off", "wrong" to true, "why" to "never parked"))
        assertEquals("""$jax needs "park at JAX" never parked""", t.struck)
        assertTrue("terminal drop-off" in t.observed.toString())

        val again = OrreryToolsTest.Tap()
        again.views[jax] = mutableListOf(view("""{"needs":${winner("\"terminal drop-off\"")}}"""))
        assertTrue(run(again, "orrery_set", set("attr" to "needs", "value" to "terminal drop-off", "wrong" to true)).startsWith("Confirmed"))
        assertNull(again.struck, "striking it would have the ship refuse what is filed")
    }

    // orrery's +plan-events: a one-off's starts is said once per start, and
    // again when the event's time moves; its place and people are not.
    @Test
    fun `a calendar's time is sent to update_event and nothing is written`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views[jax] = mutableListOf(view("""{"starts":${winner("\"2026-10-18T20:40:00Z\"", "calendar")}}""", "[${row("o9", "starts", "\"2026-10-18T20:40:00Z\"", "calendar", "default/e42")}]"))
        val said = run(t, "orrery_set", set("attr" to "starts", "value" to "2026-10-18T20:50:00Z", "calendar" to true))
        assertTrue("update_event" in said && "event e42" in said && "Nothing was changed" in said, said)
        assertNull(t.observed, "a calendar's time is never filed over")
    }

    @Test
    fun `a calendar's place waits for the event to change and then goes through`() = runTest {
        val t = OrreryToolsTest.Tap()
        val before = view("""{"location":${winner("\"Park at JAX\"", "calendar")}}""", "[${row("o9", "location", "\"Park at JAX\"", "calendar", "e42")}]")
        t.views[jax] = mutableListOf(before, before, view("""{"location":${winner("\"JAX terminal\"")}}"""))
        val waits = run(t, "orrery_set", set("attr" to "location", "value" to "JAX terminal"))
        assertTrue("update_event first" in waits && "calendar true" in waits && "event e42" in waits, waits)
        assertNull(t.observed)
        assertTrue(run(t, "orrery_set", set("attr" to "location", "value" to "JAX terminal", "calendar" to true)).startsWith("Confirmed"))
        assertTrue("JAX terminal" in t.observed.toString())
    }

    // The calendar also writes a vanished event's status and the owner's
    // zone (+plan-events, +cal-zone-seed): no event carries those.
    @Test
    fun `what the calendar wrote that no event carries goes through`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views[jax] = mutableListOf(
            view("""{"status":${winner("\"cancelled\"", "calendar")}}""", "[${row("o9", "status", "\"cancelled\"", "calendar", "gone")}]"),
            view("""{"status":${winner("\"open\"")}}"""),
        )
        assertEquals("Confirmed: $jax status shows \"open\".", run(t, "orrery_set", set("attr" to "status", "value" to "open")))
    }

    @Test
    fun `a strike that fails stops the change`() = runTest {
        val andrea = """{"ref":"person/andrea"}"""
        val list = OrreryToolsTest.Tap().apply { correctFails = IllegalStateException("HTTP 500: crashed") }
        list.views[jax] = mutableListOf(view("""{"participants":[${winner(andrea)}]}""", "[${row("o1", "participants", andrea)}]"))
        assertEquals("Could not strike $andrea: HTTP 500: crashed. Nothing was changed.", run(list, "orrery_set", set("attr" to "participants", "ref" to "person/andrea", "remove" to true, "wrong" to true)))
        assertTrue(list.retracted.isEmpty())

        val single = OrreryToolsTest.Tap().apply { correctFails = IllegalStateException("HTTP 500: crashed") }
        single.views[jax] = mutableListOf(view("""{"needs":${winner("\"a\"")}}"""))
        assertEquals("Could not strike \"a\": HTTP 500: crashed. Nothing was changed.", run(single, "orrery_set", set("attr" to "needs", "remove" to true, "wrong" to true)))
        assertEquals("Could not strike \"a\": HTTP 500: crashed. Nothing was changed.", run(single, "orrery_set", set("attr" to "needs", "value" to "b", "wrong" to true)))
        assertNull(single.observed, "nothing filed after a strike that did not land")
    }

    @Test
    fun `leave-by is never set`() = runTest {
        val t = OrreryToolsTest.Tap()
        assertEquals(LEAVE_BY_SHIPS, run(t, "orrery_set", set("attr" to "leave-by", "value" to "2026-10-18T20:00:00Z")))
        assertNull(t.observed)
    }

    // ─── removing one ───────────────────────────────────────────────

    @Test
    fun `a single value is cleared by remove`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views[jax] = mutableListOf(view("""{"needs":${winner("\"park at JAX\"")}}"""), view())
        val said = run(t, "orrery_set", set("attr" to "needs", "remove" to true))
        assertEquals("""{"observations":[{"subject":"$jax","attr":"needs","value":null,"by":"owner"}]}""", t.observed.toString())
        assertEquals("Confirmed: $jax needs is cleared.", said)

        t.views[jax] = mutableListOf(view("""{"needs":${winner("\"park at JAX\"")}}"""))
        t.observed = null
        assertTrue("is \"park at JAX\", not \"x\"" in run(t, "orrery_set", set("attr" to "needs", "remove" to true, "value" to "x")))
        assertNull(t.observed, "only the value it holds is taken off")
    }

    // orrery's +fold keeps a list's newest row per value: a row left
    // behind would bring the value back.
    @Test
    fun `one value comes off a list by retracting every row that says it`() = runTest {
        val t = OrreryToolsTest.Tap()
        val me = """{"ref":"person/me"}"""
        val andrea = """{"ref":"person/andrea"}"""
        t.views[jax] = mutableListOf(
            view("""{"participants":[${winner(me)},${winner(andrea)}]}""", "[${row("o1", "participants", andrea)},${row("o2", "participants", andrea, status = "superseded")},${row("o3", "participants", me, "calendar", "e42")},${row("o4", "participants", andrea, status = "retracted")}]"),
            view("""{"participants":[${winner(me)}]}"""),
        )
        val said = run(t, "orrery_set", set("attr" to "participants", "ref" to "person/andrea", "remove" to true, "why" to "she flies alone"))
        assertEquals(listOf("o1" to "she flies alone", "o2" to "she flies alone"), t.retracted, "the calendar's own row for someone else is left, and a retracted one too")
        assertNull(t.observed)
        assertEquals("Confirmed: $jax participants no longer holds $andrea; it shows [$me].", said)

        val wrong = OrreryToolsTest.Tap()
        wrong.views[jax] = mutableListOf(view("""{"participants":[${winner(andrea)}]}""", "[${row("o1", "participants", andrea)}]"), view("""{"participants":[]}"""))
        run(wrong, "orrery_set", set("attr" to "participants", "ref" to "person/andrea", "remove" to true, "wrong" to true))
        assertEquals("""$jax participants $andrea""", wrong.struck)
        assertTrue(wrong.retracted.isEmpty(), "a strike takes back every row itself")
    }

    @Test
    fun `a value it does not hold is not removed`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views[jax] = mutableListOf(view())
        assertTrue("holds no needs" in run(t, "orrery_set", set("attr" to "needs", "remove" to true)))
        t.views[jax] = mutableListOf(view("""{"participants":[${winner("""{"ref":"person/me"}""")}]}""", "[${row("o3", "participants", """{"ref":"person/me"}""")}]"))
        assertTrue("does not hold" in run(t, "orrery_set", set("attr" to "participants", "ref" to "person/rose", "remove" to true)))
        assertTrue("say which value" in run(t, "orrery_set", set("attr" to "participants", "remove" to true)))
        assertTrue(t.observed == null && t.retracted.isEmpty())
    }

    @Test
    fun `a refusal or no answer from the ship is said and not claimed`() = runTest {
        val refused = OrreryToolsTest.Tap(answer = ObserveAnswer(emptyList(), listOf(ItemAnswer(null, false, false, "attr: unknown"))))
        refused.views[jax] = mutableListOf(view())
        assertEquals("Orrery refused it: attr: unknown.", run(refused, "orrery_set", set("attr" to "needs", "value" to "x")))

        val gone = OrreryToolsTest.Tap().apply { views[jax] = mutableListOf(view()); observeFails = OrreryError.Unreachable(RuntimeException("timeout")) }
        assertTrue("may or may not have taken it" in run(gone, "orrery_set", set("attr" to "needs", "value" to "x")))

        val unread = OrreryToolsTest.Tap().apply { viewFails = OrreryError.Unreachable(RuntimeException("timeout")) }
        assertEquals("Could not read $jax: no answer from the ship. Nothing was changed.", run(unread, "orrery_set", set("attr" to "needs", "value" to "x")))
        assertNull(unread.observed)

        // Struck, then the new value refused: the strike is said to stand.
        val half = OrreryToolsTest.Tap(answer = ObserveAnswer(emptyList(), listOf(ItemAnswer(null, false, false, "struck"))))
        half.views[jax] = mutableListOf(view("""{"needs":${winner("\"a\"")}}"""))
        assertEquals("Orrery refused it: struck. The old value was struck first, and stays struck.", run(half, "orrery_set", set("attr" to "needs", "value" to "b", "wrong" to true)))
        assertTrue("Error" in run(half, "orrery_set", set("attr" to "needs")), "nothing to set")
        assertTrue("not both" in run(half, "orrery_set", set("attr" to "needs", "value" to "a", "ref" to "person/x")))
    }

    // ─── what orrery has waiting ────────────────────────────────────

    private fun action(id: String, title: String, status: String = "proposed", kind: String = "message", payload: JsonObject = JsonObject(emptyMap())) =
        OrreryAction(id, kind, title, payload, listOf(jax), "2026-10-18T20:45:00Z", status, "generator")

    private val late = action("a1", "Say you are running late to Park at JAX for DL 2627")

    @Test
    fun `what orrery has waiting is listed by id`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.open[0] = listOf(late, action("a2", "Book parking", "approved", "task").copy(about = emptyList(), due = null))
        assertEquals(
            "id=a1 kind=message status=proposed due=2026-10-18T20:45:00Z about=$jax title=Say you are running late to Park at JAX for DL 2627\n" +
                "id=a2 kind=task status=approved title=Book parking",
            run(t, "orrery_actions", buildJsonObject { }),
        )
        t.open[0] = emptyList()
        assertEquals("Nothing is waiting in orrery.", run(t, "orrery_actions", buildJsonObject { }))
    }

    @Test
    fun `an answer is read back from the open list`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.open.clear(); t.open += listOf(listOf(late), listOf(late), emptyList())
        val said = run(t, "orrery_answer", buildJsonObject { put("id", "a1"); put("status", "dismissed"); put("note", "dropped off, not parking") })
        assertEquals(listOf("a1 dismissed dropped off, not parking"), t.answered)
        assertEquals("Confirmed: orrery no longer lists Say you are running late to Park at JAX for DL 2627 as waiting.", said)
        assertEquals(3, t.actionReads, "read first, then until it shows")

        val stuck = OrreryToolsTest.Tap().apply { open[0] = listOf(late) }
        assertEquals(
            "Not confirmed (orrery still shows Say you are running late to Park at JAX for DL 2627 as proposed). $NOT_CONFIRMED",
            run(stuck, "orrery_answer", buildJsonObject { put("id", "a1"); put("status", "dismissed") }),
        )
        val approved = OrreryToolsTest.Tap().apply { open.clear(); open += listOf(listOf(late), listOf(late.copy(status = "claimed"))) }
        assertEquals("Confirmed: orrery shows Say you are running late to Park at JAX for DL 2627 as claimed.", run(approved, "orrery_answer", buildJsonObject { put("id", "a1"); put("status", "approved") }))
    }

    @Test
    fun `an answer the ship refuses or never hears is said`() = runTest {
        val t = OrreryToolsTest.Tap().apply { open[0] = listOf(late) }
        t.transitionFails = OrreryError.Refused(409, "cannot go from proposed to done")
        assertEquals("Orrery did not take it: cannot go from proposed to done. Nothing was changed.", run(t, "orrery_answer", buildJsonObject { put("id", "a1"); put("status", "approved") }))
        t.transitionFails = OrreryError.Unreachable(RuntimeException("timeout"))
        assertTrue("may or may not have taken it" in run(t, "orrery_answer", buildJsonObject { put("id", "a1"); put("status", "dismissed") }))
        t.transitionFails = null
        assertTrue("no open action a9" in run(t, "orrery_answer", buildJsonObject { put("id", "a9"); put("status", "dismissed") }))
        assertTrue("Error" in run(t, "orrery_answer", buildJsonObject { put("id", "a1"); put("status", "done") }), "only approve or dismiss")
        assertTrue(t.answered.isEmpty())
    }

    @Test
    fun `an approved fact is read back from the record`() = runTest {
        val fact = action("f1", "Plan is a drop-off", kind = "fact", payload = buildJsonObject { put("subject", jax); put("attr", "needs"); put("value", "terminal drop-off") })
        val t = OrreryToolsTest.Tap().apply { open.clear(); open += listOf(listOf(fact), emptyList()) }
        t.views[jax] = mutableListOf(view("""{"needs":${winner("\"terminal drop-off\"")}}"""))
        assertEquals(
            "Confirmed: orrery no longer lists Plan is a drop-off as waiting.\nConfirmed: $jax needs shows \"terminal drop-off\".",
            run(t, "orrery_answer", buildJsonObject { put("id", "f1"); put("status", "approved") }),
        )
    }

    // ─── every write reads back ─────────────────────────────────────

    @Test
    fun `observed facts are read back one body alone and many by the state view`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views["person/alice"] = mutableListOf(view("""{"status":${winner("\"away\"")}}""", id = "person/alice"))
        val one = run(t, "orrery_observe", buildJsonObject { put("observations", """[{"subject":"person/alice","attr":"status","value":"away"}]""") })
        assertEquals("1: written\nConfirmed: person/alice status shows \"away\".", one)

        val many = OrreryToolsTest.Tap(answer = ObserveAnswer(emptyList(), List(3) { ItemAnswer("$it", true, false, null) }))
        // The brief view: bare values, a list for a multi-valued one.
        many.stateText = """{"bodies":[{"id":"person/a","attrs":{"x":"1"}},{"id":"person/b","attrs":{"kids":[{"ref":"person/c"}]}}]}"""
        val said = run(many, "orrery_observe", buildJsonObject {
            put("observations", """[{"subject":"person/a","attr":"x","value":"1"},{"subject":"person/b","attr":"kids","value":"person/c"},{"subject":"person/a","attr":"y","value":"2"}]""")
        })
        assertTrue("Confirmed: person/a x shows \"1\"." in said && "Confirmed: person/b kids shows [{\"ref\":\"person/c\"}]." in said, said)
        assertTrue("Not confirmed (the record still shows nothing): person/a y was to be \"2\"." in said && NOT_CONFIRMED in said, said)

        val all = OrreryToolsTest.Tap(answer = ObserveAnswer(emptyList(), List(2) { ItemAnswer("$it", true, false, null) }))
        all.stateText = """{"bodies":[{"id":"person/a","attrs":{"x":"1"}},{"id":"person/b","attrs":{"y":"2"}}]}"""
        assertEquals(
            "1: written\n2: written\nConfirmed: person/a x shows \"1\".\nConfirmed: person/b y shows \"2\".",
            run(all, "orrery_observe", buildJsonObject { put("observations", """[{"subject":"person/a","attr":"x","value":"1"},{"subject":"person/b","attr":"y","value":"2"}]""") }),
        )
    }

    @Test
    fun `the ship's answer is a line an item`() {
        val answer = ObserveAnswer(
            listOf(ItemAnswer("person/a", true, true, null), ItemAnswer("person/b", true, false, null), ItemAnswer("person/c", false, false, "id: bad"), ItemAnswer("person/d", false, false, null)),
            listOf(ItemAnswer("1-a", true, true, null), ItemAnswer("2-b", true, false, null), ItemAnswer(null, false, false, "attr: unknown")),
        )
        assertEquals(
            listOf("body person/a: already there", "body person/b: created", "body person/c: refused, id: bad", "body person/d: refused, no reason given", "1: already known", "2: written", "3: refused, attr: unknown"),
            answer.lines(),
        )
    }

    @Test
    fun `a row dated ahead of now is said to count from then`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views["person/alice"] = mutableListOf(view("""{"location":${winner("\"Home\"")}}""", id = "person/alice"))
        val said = run(t, "orrery_observe", buildJsonObject { put("observations", """[{"subject":"person/alice","attr":"location","value":"Lisbon","at":"2099-01-01T00:00:00Z"}]""") })
        assertTrue("dated 2099-01-01T00:00:00Z, ahead of now, and orrery counts it only from then" in said, said)
        val past = Change("person/alice", "location", JsonPrimitive("Lisbon"), at = "2020-01-01T00:00:00Z").line(JsonPrimitive("Home"), 1_800_000_000_000)
        assertTrue("ahead of now" !in past, past)
    }

    @Test
    fun `a strike is read back`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.views["person/sam"] = mutableListOf(view("""{"status":${winner("\"on jury duty\"")}}""", id = "person/sam"), view("{}", id = "person/sam"))
        val said = run(t, "orrery_correct", buildJsonObject { put("subject", "person/sam"); put("attr", "status"); put("value", "on jury duty") })
        assertTrue(said.endsWith("Confirmed: person/sam status no longer holds \"on jury duty\"; it shows nothing."), said)
    }

    // orrery's +serve-instruct lists what it filed before it approves it,
    // so an applied answer still says proposed.
    @Test
    fun `an applied instruction reads back its facts and says a merge is not`() = runTest {
        val t = OrreryToolsTest.Tap()
        t.instructed = Instructed(reply = "Done.", actions = listOf(
            action("f1", "Plan is a drop-off", kind = "fact", payload = buildJsonObject { put("subject", jax); put("attr", "needs"); put("value", "terminal drop-off") }),
            action("m1", "Fold Samuel into Sam", kind = "merge", payload = buildJsonObject { put("from", "person/samuel"); put("into", "person/sam") }),
        ))
        t.views[jax] = mutableListOf(view("""{"needs":${winner("\"park at JAX\"")}}"""))
        val said = run(t, "orrery_instruct", buildJsonObject { put("text", "it is a drop-off now"); put("apply", true) })
        assertTrue("Not confirmed (the record still shows \"park at JAX\"): $jax needs was to be \"terminal drop-off\"." in said, said)
        assertTrue("Not read back: the merge of person/samuel into person/sam." in said, said)
        // A resolve is read back by its two rows.
        assertEquals(
            listOf(Change("situation/s", "status", JsonPrimitive("closed")), Change("situation/s", "outcome", JsonPrimitive("flew"))),
            changesOf(action("r1", "Closed", kind = "resolve", payload = buildJsonObject { put("situation", "situation/s"); put("outcome", "flew") })),
        )
        assertTrue(changesOf(action("p1", "x", kind = "fact")).isEmpty(), "a fact with no payload asks nothing")
        assertEquals(
            listOf(Change("person/sam", "status", JsonPrimitive("on jury duty"), gone = true)),
            changesOf(action("c1", "Not on jury duty", kind = "correct", payload = buildJsonObject { put("subject", "person/sam"); put("attr", "status"); put("value", "on jury duty") })),
        )
    }

    // What the owner approved in the transcript is carried out and read
    // back; what still waits under Actions is not.
    @Test
    fun `an instruction's approved facts are read back and its waiting ones are not`() = runTest {
        val fact = action("f1", "Plan is a drop-off", kind = "fact", payload = buildJsonObject { put("subject", jax); put("attr", "needs"); put("value", "terminal drop-off") })
        val t = OrreryToolsTest.Tap().apply { instructed = Instructed(reply = "On it.", actions = listOf(fact)) }
        t.views[jax] = mutableListOf(view("""{"needs":${winner("\"terminal drop-off\"")}}"""))
        val approved = t.puttingToOwner(decide = { _, _ -> true }, answer = { _, _ -> })
        assertEquals(
            "Orrery says: On it.\nFiled:\n- fact: Plan is a drop-off (approved)\nConfirmed: $jax needs shows \"terminal drop-off\".",
            run(approved, "orrery_instruct", buildJsonObject { put("text", "it is a drop-off") }),
        )
        val waits = t.puttingToOwner(decide = { _, _ -> null }, answer = { _, _ -> })
        t.viewReads = 0
        assertEquals("Orrery says: On it.\nFiled:\n- fact: Plan is a drop-off (proposed)", run(waits, "orrery_instruct", buildJsonObject { put("text", "it is a drop-off") }))
        assertEquals(0, t.viewReads, "a proposal changes nothing yet, so nothing is read")
    }

    // ─── what the record carries ────────────────────────────────────

    @Test
    fun `both shapes of a view's values are read`() {
        val full = Json.parseToJsonElement("""{"a":${winner("\"x\"")},"b":[${winner("""{"ref":"person/me"}""")}]}""") as JsonObject
        val brief = Json.parseToJsonElement("""{"a":"x","b":[{"ref":"person/me"}],"c":{"ref":"place/home"}}""") as JsonObject
        for (attrs in listOf(full, brief)) {
            assertEquals(JsonPrimitive("x"), shownOf(attrs, "a"))
            assertEquals("""[{"ref":"person/me"}]""", shownOf(attrs, "b").toString())
        }
        assertEquals("""{"ref":"place/home"}""", shownOf(brief, "c").toString(), "a ref is a value, not a row")
        assertNull(shownOf(brief, "d"))
        assertTrue(Change("s", "a", JsonNull).shownIn(null) && Change("s", "a", JsonNull).shownIn(Json.parseToJsonElement("[]")))
        assertTrue(!Change("s", "a", JsonNull).shownIn(JsonPrimitive("x")))
    }

    // orrery's +leave-pass writes leave-by alone on the appointment (the
    // time, when, until its start, source ship); the drive is in its plan.
    @Test
    fun `leave-by's basis is said from the ship's plan for this body and plainly not otherwise`() {
        val body = Json.parseToJsonElement(view("""{"leave-by":{"value":"2026-10-18T19:58:00Z","at":"2026-10-18T17:00:00Z","until":"2026-10-18T20:40:00Z","conf":90,"source":{"kind":"ship","id":"leave/x"},"by":"ship","obs":"o5"}}""")) as JsonObject
        val plan = Json.parseToJsonElement("""{"next":{"key":"k","id":"$jax","name":"DL 2627","starts":"2026-10-18T20:40:00Z","leave_by":"2026-10-18T19:58:00Z","minutes":37,"from":"home","via":"I-295","typical_minutes":31,"incident":"","learned_min":0,"weather_min":0,"weather":""}}""") as JsonObject
        assertEquals(
            "Worked out by the ship for a start of 2026-10-18T20:40:00Z: leave by 2026-10-18T19:58:00Z, for a 37 min drive with traffic from home via I-295, usually 31 min, and minutes to park. The ship works it out again as the traffic and the start move.",
            leaveBasis(body, plan),
        )
        val other = Json.parseToJsonElement("""{"next":{"id":"activity/swim","name":"Swim"}}""") as JsonObject
        val said = leaveBasis(body, other)!!
        assertTrue("worked out by the ship at 2026-10-18T17:00:00Z" in said && "neither the drive's length nor its route" in said && "is for Swim, not this" in said, said)
        assertTrue("could not be read" in leaveBasis(body, null)!!)
        assertTrue("has no leave plan now" in leaveBasis(body, JsonObject(emptyMap()))!!)
        fun from(where: String, more: String = "") = leaveBasis(body, Json.parseToJsonElement("""{"next":{"id":"$jax","starts":"s","leave_by":"l","minutes":20,"from":"$where"$more}}""") as JsonObject)!!
        assertTrue("from where the phone last was, and" in from("position"))
        assertTrue("from the appointment before it, and" in from("previous"))
        assertTrue("from work, and" in from("work"))
        assertEquals(
            "Worked out by the ship for a start of s: leave by l, for a 20 min drive with traffic from home; a crash on I-95, and minutes to park, 6 more for rain. The ship works it out again as the traffic and the start move.",
            from("home", ""","incident":"a crash on I-95","weather_min":6,"weather":"rain""""),
        )
        assertNull(leaveBasis(Json.parseToJsonElement(view()) as JsonObject, plan), "no leave-by, nothing to say")
        assertTrue(""""leave_by_basis":"Worked out""" in bodyForModel(body, leaveBasis(body, plan)))
    }
}
