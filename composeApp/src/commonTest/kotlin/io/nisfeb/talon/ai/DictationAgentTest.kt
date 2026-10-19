package io.nisfeb.talon.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationAgentTest {

    private fun spec(name: String) = ToolSpec(name, "", JsonObject(emptyMap()))

    @Test
    fun `a blank dictation runs nothing`() = runBlocking {
        var asked = false
        val agent = DictationAgent(
            completer = { _, _, _ -> asked = true; AgentTurn.Final("x") },
            tools = emptyList(),
        )
        assertEquals("", agent.run("   \n  "))
        assertFalse(asked, "the model was consulted about an empty dictation")
    }

    @Test
    fun `a dictation runs a write with no confirmation and returns the summary`() = runBlocking {
        val wrote = mutableListOf<JsonObject>()
        val orrery = Tool(
            spec = spec("orrery_observe"),
            write = true, // a write tool: it runs only because the dictation authorises writes
            execute = { args -> wrote += args; "ok" },
        )
        // First turn the model calls the write tool; second it answers.
        var turn = 0
        val agent = DictationAgent(
            completer = { _, _, _ ->
                if (turn++ == 0) {
                    AgentTurn.Calls(
                        null,
                        listOf(ToolCall("1", "orrery_observe", buildJsonObject { put("note", "buy milk") })),
                    )
                } else {
                    AgentTurn.Final("Noted: buy milk.")
                }
            },
            tools = listOf(orrery),
        )
        val summary = agent.run("remind me to buy milk")
        assertEquals(1, wrote.size, "the write ran unconfirmed")
        assertTrue(wrote.single().toString().contains("buy milk"))
        assertEquals("Noted: buy milk.", summary)
    }

    @Test
    fun `a dictation is told to act, with no Urbit guide it has no tools for`() {
        val prompt = DictationAgent.prompt
        assertTrue(DictationAgent.role in prompt)
        assertTrue(DATA_NOT_INSTRUCTIONS in prompt, "the content-is-data rule is not negotiable")
        assertFalse("Default to reading" in prompt)
        assertFalse(AgentPrompt.urbitKnowledge in prompt, "the MCP and scry guide went out on every call")
        assertTrue("orrery_file_text" in prompt)
    }

    private fun tool(name: String, write: Boolean, run: (JsonObject) -> String = { "ok" }) = Tool(spec(name), write) { run(it) }

    @Test
    fun `a dictation is offered what a dictation uses, and a locked phone sends nothing to anyone`() {
        val all = listOf(
            tool("search_history", false), tool("read_mail", false), tool("web_search", false), tool("fetch_url", false),
            tool("orrery_file_text", true), tool("orrery_instruct", true), tool("orrery_observe", true),
            tool("create_event", true), tool("update_event", true), tool("create_task", true), tool("complete_task", true),
            tool("send_mail", true), tool("send_message", true), tool("delete_event", true), tool("react", true),
            tool("share_calendar", true), tool("send_event", true), tool("orrery_configure", true), tool("orrery_correct", true),
            // The assistant's alone: dictation and loops are not offered it.
            tool("orrery_mark", true),
        )
        val unlocked = DictationAgent.toolsFor(all, locked = false).map { it.spec.name }.toSet()
        assertEquals(
            setOf(
                "search_history", "read_mail", "web_search", "orrery_file_text", "orrery_instruct", "orrery_observe",
                "create_event", "update_event", "create_task", "complete_task",
                "send_mail", "send_message", "delete_event", "orrery_correct",
            ),
            unlocked,
        )
        val locked = DictationAgent.toolsFor(all, locked = true).map { it.spec.name }.toSet()
        assertEquals(unlocked - setOf("send_mail", "send_message", "delete_event", "orrery_correct"), locked)
    }

    @Test
    fun `a tool offered twice is offered once`() {
        val tools = DictationAgent.toolsFor(listOf(tool("find_person", false), tool("find_person", false)), locked = false)
        assertEquals(listOf("find_person"), tools.map { it.spec.name })
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall("1", name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    @Test
    fun `a note filed is said from the result, with no second model call`() = runBlocking {
        var calls = 0
        val orrery = tool("orrery_file_text", true) { "Handed to orrery to read (item r7). It files what it finds soon." }
        val agent = DictationAgent(
            completer = { _, _, _ ->
                calls++
                AgentTurn.Calls(null, listOf(call("orrery_file_text", "text" to "Linus needs a dentist", "title" to "Dentist for Linus")))
            },
            tools = listOf(orrery),
        )
        assertEquals("Filed in orrery: Dentist for Linus.", agent.run("Linus needs a dentist"))
        assertEquals(1, calls, "the model was asked to word a note that was filed")
    }

    @Test
    fun `writes that worked are said as the tools said them`() {
        fun r(c: ToolCall, text: String) = c to ToolResult(c.id, c.name, text)
        assertEquals(
            "Added \"Dentist\" on 2026-10-20 at 09:00 to the Home calendar.\nSent to ~zod.",
            DictationAgent.plainly(listOf(
                r(call("create_event"), "Added \"Dentist\" on 2026-10-20 at 09:00 to the Home calendar. It is synced there: it goes out on the next sync."),
                r(call("send_message", "whom" to "~zod"), "Sent to ~zod."),
            )),
        )
        assertEquals("Done: Staples return.", DictationAgent.plainly(listOf(r(call("complete_task"), "Done: Staples return."))))
        assertEquals("Filed in orrery: Linus needs a dentist.", DictationAgent.plainly(listOf(r(call("orrery_file_text", "text" to "Linus needs a dentist"), "Handed to orrery to read (item r7)."))))
    }

    @Test
    fun `anything else goes back to the model to word`() {
        fun r(c: ToolCall, text: String) = c to ToolResult(c.id, c.name, text)
        // A refusal, a write that took but is not showing, a read, and a write it has no words for.
        assertNull(DictationAgent.plainly(listOf(r(call("orrery_file_text"), "Orrery did not take it: reading is off."))))
        assertNull(DictationAgent.plainly(listOf(r(call("create_event"), "The calendar took \"X\" for 2026-10-20, but it is not showing there yet."))))
        assertNull(DictationAgent.plainly(listOf(r(call("create_event"), "Added \"X\" on 2026-10-20 to the Home calendar."), r(call("list_events"), "Nothing."))))
        assertNull(DictationAgent.plainly(listOf(r(call("orrery_observe"), "1: written"))))
        assertNull(DictationAgent.plainly(emptyList()))
    }

    @Test
    fun `only the calendar's tools wait for the calendar`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val tools = listOf(tool("list_events", false) { "events" }, tool("orrery_file_text", true) { "filed" })
            .waitingFor(DictationAgent.CALENDAR_TOOLS) { gate.await() }
        assertEquals("filed", tools[1].execute(JsonObject(emptyMap())), "a note waited on the calendar")
        val events = async { tools[0].execute(JsonObject(emptyMap())) }
        kotlinx.coroutines.yield()
        assertFalse(events.isCompleted, "the calendar was read before it was in")
        gate.complete(Unit)
        assertEquals("events", events.await())
        assertTrue(tools[1].write && !tools[0].write, "a wrapped tool keeps whether it writes")
    }

    @Test
    fun `every write the card can say plainly is said as the tool said it`() {
        fun r(name: String, text: String, vararg args: Pair<String, String>) = call(name, *args).let { it to ToolResult(it.id, it.name, text) }
        assertEquals("Added task \"Buy milk\" due 2026-10-17.", DictationAgent.plainly(listOf(r("create_task", "Added task \"Buy milk\" due 2026-10-17."))))
        assertEquals("Updated \"Dentist\".", DictationAgent.plainly(listOf(r("update_event", "Updated \"Dentist\"."))))
        assertEquals("Deleted \"Dentist\".", DictationAgent.plainly(listOf(r("delete_event", "Deleted \"Dentist\"."))))
        assertEquals("Reopened: Staples return.", DictationAgent.plainly(listOf(r("complete_task", "Reopened: Staples return."))))
        assertEquals("Mailed ~zod.", DictationAgent.plainly(listOf(r("send_mail", "Mailed ~zod."))))
        assertEquals("Sent to Andrea.", DictationAgent.plainly(listOf(r("send_message", "Sent to Andrea.", "whom" to "~bus"))), "by name, not the id")
        val queued = ToolCatalog.queued("for Andrea")
        assertEquals("Queued for Andrea: your ship is slow; it goes when it answers.", DictationAgent.plainly(listOf(r("send_message", queued))), "queued is not sent")
        // Each refused in its own words: back to the model.
        listOf("create_task", "update_event", "delete_event", "complete_task", "send_mail", "send_message").forEach {
            assertNull(DictationAgent.plainly(listOf(r(it, "The calendar did not take it."))), it)
        }
    }

    @Test
    fun `a question is offered reads only, and told it can only look things up`() {
        val all = listOf(tool("search_history", false), tool("read_mail", false), tool("send_message", true), tool("create_event", true), tool("web_search", false), tool("fetch_url", false))
        assertEquals(listOf("search_history", "read_mail", "web_search"), DictationAgent.readsFor(all).map { it.spec.name })
        assertTrue(DATA_NOT_INSTRUCTIONS in DictationAgent.askPrompt)
        assertTrue("only look things up" in DictationAgent.askPrompt)
        assertTrue("read" in DictationAgent.askPrompt && "aloud" in DictationAgent.askPrompt)
    }

    @Test
    fun `a question reads orrery too, and writes nothing to it`() {
        // "What do I have left to do before Barcelona?" is orrery's to answer: its open situations and actions.
        val events = listOf(tool("list_events", false))
        assertEquals(listOf("list_events", "orrery_find", "orrery_read", "orrery_flights"), DictationAgent.readsFor(events, OrreryToolsTest.Tap()).map { it.spec.name })
        assertEquals(listOf("list_events"), DictationAgent.readsFor(events).map { it.spec.name }, "no orrery, no orrery tools")
        assertTrue("orrery_read" in DictationAgent.askPrompt && "left to do" in DictationAgent.askPrompt)
        assertTrue("not on the screen" in DictationAgent.askPrompt, "what orrery holds is said, since the card draws none of it")
    }

    // ─── sends wait for the owner (B2-2) ──────────────────────────

    /** A model that calls [first] once, then answers with what the tool said. */
    private fun once(first: ToolCall, seen: MutableList<String> = mutableListOf()) = AgentLoop.Completer { _, messages, _ ->
        val results = messages.filterIsInstance<AgentMessage.ToolResults>()
        if (results.isEmpty()) AgentTurn.Calls(null, listOf(first))
        else AgentTurn.Final(results.last().results.single().content.also { seen += it })
    }

    private val names = mapOf("~bus" to "Andrea", "~zod" to "Zod")
    private fun name(s: String) = names[s] ?: s

    @Test
    fun `a dictated message goes only on the owner's word, and the card says to whom and what`() = runBlocking {
        for (word in listOf(true, false, null)) {
            val sent = mutableListOf<JsonObject>()
            val asked = mutableListOf<CardProposal>()
            val seen = mutableListOf<String>()
            val agent = DictationAgent(
                completer = once(call("send_message", "whom" to "~bus", "text" to " Running late "), seen),
                tools = listOf(tool("send_message", true) { sent += it; "Sent to Andrea." }),
                ownerSends = { asked += it; word },
                name = ::name,
            )
            val said = agent.run("tell Andrea I'm running late")
            assertEquals(listOf(CardProposal("message", "Message to Andrea", to = "Andrea", text = "Running late")), asked, "asked once, for $word")
            if (word == true) {
                assertEquals(1, sent.size)
                assertEquals("Sent to Andrea.", said)
            } else {
                assertTrue(sent.isEmpty(), "sent without the owner's yes ($word)")
                assertEquals(listOf(DictationAgent.NOT_SENT), seen, "the model is told plainly")
            }
        }
    }

    @Test
    fun `a dictated mail is asked as a mail, to everyone it goes to`() = runBlocking {
        val asked = mutableListOf<CardProposal>()
        val mailed = mutableListOf<JsonObject>()
        DictationAgent(
            completer = once(call("send_mail", "to" to "~bus, ~zod,", "subject" to " Dinner ", "body" to "Friday at 7?")),
            tools = listOf(tool("send_mail", true) { mailed += it; "Mailed ~bus, ~zod." }),
            ownerSends = { asked += it; false },
            name = ::name,
        ).run("mail Andrea and Zod about dinner on Friday at 7")
        assertEquals(listOf(CardProposal("message", "Mail to Andrea, Zod: Dinner", to = "Andrea, Zod", text = "Friday at 7?")), asked)
        assertTrue(mailed.isEmpty())
        assertEquals("Mail to Zod", DictationAgent.outgoing("send_mail", buildJsonObject { put("to", "~zod"); put("body", "x") }, ::name)?.title, "no subject, none said")
        assertEquals("Mail to Zod", DictationAgent.outgoing("send_mail", buildJsonObject { put("to", "~zod"); put("subject", " "); put("body", "x") }, ::name)?.title)
    }

    @Test
    fun `nobody to ask sends nothing, and the other writes go ahead unasked`() = runBlocking {
        val ran = mutableListOf<String>()
        var turn = 0
        val agent = DictationAgent(
            completer = { _, _, _ ->
                if (turn++ == 0) AgentTurn.Calls(null, listOf(call("send_message", "whom" to "~bus", "text" to "hi"), call("create_event", "name" to "Dentist")))
                else AgentTurn.Final("ok")
            },
            tools = listOf(tool("send_message", true) { ran += "send"; "Sent." }, tool("create_event", true) { ran += "event"; "Added." }),
        )
        agent.run("dentist on friday and tell Andrea")
        assertEquals(listOf("event"), ran, "a send went with no one to ask, or a calendar write waited")
    }

    @Test
    fun `a send that lacks whom or what is refused unasked, and nothing runs`() = runBlocking {
        var asked = 0
        val sent = mutableListOf<JsonObject>()
        val tools = listOf(tool("send_message", true) { sent += it; "Sent." }, tool("send_mail", true) { sent += it; "Mailed." })
            .askingToSend(::name) { asked++; true }
        val bad = listOf(
            "send_message" to buildJsonObject { put("text", "hi") },
            "send_message" to buildJsonObject { put("whom", "~bus"); put("text", "  ") },
            "send_mail" to buildJsonObject { put("to", " , "); put("body", "hi") },
            "send_mail" to buildJsonObject { put("to", "~bus") },
        )
        bad.forEach { (n, a) -> assertTrue(tools.first { it.spec.name == n }.execute(a).startsWith("Error:"), "$n $a") }
        assertEquals(0, asked)
        assertTrue(sent.isEmpty())
        // An empty whom is still a send the tool would try: asked, never passed by.
        tools.first().execute(buildJsonObject { put("whom", ""); put("text", "hi") })
        assertEquals(1, asked)
        assertTrue(tools.all { it.write }, "a wrapped send is still a write")
    }

    // ─── a locked phone reads no one's words (B2-8) ───────────────

    @Test
    fun `the private reads are every dictation tool that gives messages or mail, and no other`() {
        assertEquals(
            setOf("search_history", "read_conversation", "list_unread", "list_mail", "search_mail", "read_mail"),
            DictationAgent.PRIVATE_READS,
        )
        assertTrue(DictationAgent.TOOLS.containsAll(DictationAgent.PRIVATE_READS))
        assertTrue(DictationAgent.CALENDAR_TOOLS.none { it in DictationAgent.PRIVATE_READS }, "the calendar's reads stay free")
    }

    @Test
    fun `while locked a message or mail read waits for the fingerprint, and without it nothing is read`() = runBlocking {
        val read = mutableListOf<String>()
        var unlocks = 0
        val tools = listOf("list_unread", "read_mail", "list_events", "find_person").map { n -> tool(n, false) { read += n; "$n said" } }
            .unlockingReads(locked = { true }) { unlocks++; false }
        assertEquals(DictationAgent.UNLOCK_TO_READ, tools[0].execute(JsonObject(emptyMap())))
        assertEquals(DictationAgent.UNLOCK_TO_READ, tools[1].execute(JsonObject(emptyMap())))
        assertEquals(1, unlocks, "a no is asked once a run, not at every read")
        assertEquals("list_events said", tools[2].execute(JsonObject(emptyMap())))
        assertEquals("find_person said", tools[3].execute(JsonObject(emptyMap())))
        assertEquals(listOf("list_events", "find_person"), read, "a message or mail was read on a locked phone")
        assertEquals(1, unlocks, "the calendar or a contact asked for the fingerprint")
    }

    @Test
    fun `an unlock reads it, an unlocked phone is never asked, and a later unlock lifts a no`() = runBlocking {
        var locked = true
        var unlocks = 0
        var answer = true
        val tools = listOf(tool("search_history", false) { "found" }).unlockingReads({ locked }) { unlocks++; answer }
        assertEquals("found", tools[0].execute(JsonObject(emptyMap())))
        assertEquals(1, unlocks)
        val refused = listOf(tool("read_conversation", false) { "said" }).unlockingReads({ locked }) { unlocks++; answer }
        answer = false
        assertEquals(DictationAgent.UNLOCK_TO_READ, refused[0].execute(JsonObject(emptyMap())))
        locked = false
        assertEquals("said", refused[0].execute(JsonObject(emptyMap())), "unlocked by hand after a no")
        assertEquals("found", tools[0].execute(JsonObject(emptyMap())))
        assertEquals(2, unlocks, "an unlocked phone was asked to unlock")
    }

    @Test
    fun `a dictation on a locked phone draws and says nothing it was not unlocked for`() = runBlocking {
        val drawn = mutableListOf<String>()
        val seen = mutableListOf<String>()
        val said = DictationAgent(
            completer = once(call("list_unread"), seen),
            // As the catalog's list_unread hands what it read to the card.
            tools = listOf(tool("list_unread", false) { drawn += "Andrea: the code is 4471"; "Andrea: the code is 4471" }),
            locked = { true },
            unlock = { false },
        ).run("read me my messages")
        assertTrue(drawn.isEmpty(), "the card was handed messages")
        assertEquals(listOf(DictationAgent.UNLOCK_TO_READ), seen)
        assertEquals(DictationAgent.UNLOCK_TO_READ, said)
        // With nothing wired, a locked phone is refused, not read, and an unlocked one reads.
        assertEquals(DictationAgent.UNLOCK_TO_READ, DictationAgent(once(call("list_unread")), listOf(tool("list_unread", false) { "x" }), locked = { true }).run("read them"))
        assertEquals("x", DictationAgent(once(call("list_unread")), listOf(tool("list_unread", false) { "x" })).run("read them"))
    }

    @Test
    fun `with no names given a send is asked by its id`() = runBlocking {
        val asked = mutableListOf<CardProposal>()
        DictationAgent(once(call("send_message", "whom" to "~bus", "text" to "hi")), listOf(tool("send_message", true)), ownerSends = { asked += it; false }).run("tell bus hi")
        assertEquals("~bus", asked.single().to)
    }
}
