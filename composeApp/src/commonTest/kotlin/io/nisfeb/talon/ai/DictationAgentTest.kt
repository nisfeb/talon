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
            tool("search_history", false), tool("read_mail", false), tool("web_search", false),
            tool("orrery_file_text", true), tool("orrery_instruct", true), tool("orrery_observe", true),
            tool("create_event", true), tool("update_event", true), tool("create_task", true), tool("complete_task", true),
            tool("send_mail", true), tool("send_message", true), tool("delete_event", true), tool("react", true),
            tool("share_calendar", true), tool("send_event", true), tool("orrery_configure", true), tool("orrery_correct", true),
        )
        val unlocked = DictationAgent.toolsFor(all, locked = false).map { it.spec.name }.toSet()
        assertEquals(
            setOf(
                "search_history", "read_mail", "orrery_file_text", "orrery_instruct", "orrery_observe",
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
                r(call("send_message", "whom" to "~zod"), "Sent."),
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
}
