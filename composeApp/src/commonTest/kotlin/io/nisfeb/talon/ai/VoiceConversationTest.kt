package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Brief
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Archie's turns with a scripted model: what it is offered, what it
 * remembers, what is read out in full, and when it stops.
 */
class VoiceConversationTest {
    /** A model that answers from [script] in turn, and keeps what each call was given. */
    private class Model(vararg script: AgentTurn) {
        private val left = ArrayDeque(script.toList())
        val calls = mutableListOf<Triple<String, List<AgentMessage>, List<String>>>()
        val completer = AgentLoop.Completer { system, messages, tools ->
            calls += Triple(system, messages.toList(), tools.map { it.name })
            left.removeFirstOrNull() ?: error("the model was not expected to be asked again")
        }
    }

    private fun tool(name: String, write: Boolean, run: (JsonObject) -> String = { "ok" }) =
        Tool(ToolSpec(name, name, toolSchema(required = emptyList())), write) { run(it) }

    private fun call(name: String, vararg args: Pair<String, Any>) = ToolCall(
        "c-$name", name,
        buildJsonObject { args.forEach { (k, v) -> put(k, if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) } },
    )

    private val brief = Brief("2026-10-17", null, true, mapOf("A1" to "a-1"), "Saturday\n\n[A1] Load the BT26 wifi\n\nAndrea is away; you drop off.")

    private val told = mutableListOf<String>()

    private fun talk(model: Model, brief: Brief? = this.brief, tell: (suspend (String) -> String)? = { told += it; "Done. Orrery will text Andrea." }) =
        VoiceConversation(
            model.completer,
            tools = listOf(tool("list_events", false), tool("send_message", true), tool("orrery_instruct", true), tool("orrery_read", false)),
            tell = tell,
            brief = { brief },
        )

    @Test
    fun `nothing reaches another person unasked, and orrery's instruct asks first`() {
        val t = talk(Model())
        assertEquals(listOf("list_events", "orrery_read", "orrery_instruct", "read_brief"), t.toolNames)
        assertEquals(listOf("list_events", "orrery_read", "read_brief"), talk(Model(), tell = null).toolNames, "with Orrery off, no instruct at all")
    }

    @Test
    fun `the opener is the counts and the model's rest, and a failed model still opens it`() = runBlocking {
        val t = talk(Model(AgentTurn.Final("Your first meeting is in 30 minutes. Shall I read you the brief?")))
        val model = Model(AgentTurn.Final("Your first meeting is in 30 minutes. Shall I read you the brief?"))
        val t2 = talk(model)
        assertEquals("Your first meeting is in 30 minutes. Shall I read you the brief?", t2.opener("You have 2 new emails.", "digest"))
        assertTrue("do not say those numbers again" in model.calls.single().first, "the opener's own prompt")
        assertEquals(AgentMessage.User("digest"), model.calls.single().second.single())
        assertEquals("Your first meeting is in 30 minutes. Shall I read you the brief?", t.opener("You have 2 new emails.", "digest"))
        assertEquals(DictationFlow.Earlier(VoiceConversation.OPENED, "You have 2 new emails. Your first meeting is in 30 minutes. Shall I read you the brief?"), t.said.single())
        val failing = VoiceConversation({ _, _, _ -> error("no network") }, emptyList(), null, { null })
        assertEquals(VoiceConversation.WHAT_NEXT, failing.opener("You have 2 new emails.", "digest"))
        assertEquals("You have 2 new emails. What would you like?", failing.said.single().line)
    }

    @Test
    fun `the brief is read out as orrery wrote it, with no model call to say so, and stays in mind`() = runBlocking {
        val model = Model(
            AgentTurn.Calls(null, listOf(call("read_brief", "aloud" to true))),
            AgentTurn.Final("A1 is the wifi."),
        )
        val t = talk(model)
        val read = t.next("Yes, read me the brief") as VoiceConversation.Next.Say
        assertEquals(VoiceConversation.READING, read.line)
        assertEquals(listOf("Saturday", "A1 Load the BT26 wifi", "Andrea is away; you drop off."), read.inFull)
        assertTrue(brief.text in read.shown, "the card shows it whole, tags and all")
        assertEquals(1, model.calls.size, "said by the tool's result, not a second call")
        // The next turn knows what was read and what was said.
        assertEquals(VoiceConversation.Next.Say("A1 is the wifi."), t.next("What's A1 again?"))
        val (system, messages, _) = model.calls.last()
        assertTrue("What you read out to the owner earlier" in system && "Load the BT26 wifi" in system, system)
        assertTrue(system.startsWith(DATA_NOT_INSTRUCTIONS) && "orrery_instruct" in system && "read_brief with aloud" in system, "Archie's own prompt")
        assertEquals(AgentMessage.User("Yes, read me the brief"), messages[0])
        assertEquals(AgentMessage.Assistant(VoiceConversation.READING, emptyList()), messages[1])
        assertEquals(AgentMessage.User("What's A1 again?"), messages.last())
    }

    @Test
    fun `read out along with something else, the model still words the turn`() = runBlocking {
        val model = Model(
            AgentTurn.Calls(null, listOf(call("read_brief", "aloud" to true), call("list_events"))),
            AgentTurn.Final("Here's the brief, and your next meeting is at nine."),
        )
        val next = talk(model).next("Read me the brief and my next meeting") as VoiceConversation.Next.Say
        assertEquals("Here's the brief, and your next meeting is at nine.", next.line)
        assertEquals(3, next.inFull.size)
        assertEquals(2, model.calls.size)
    }

    @Test
    fun `asked about the brief without wanting it read, the model says it, and no brief is said so`() = runBlocking {
        val t = talk(Model(AgentTurn.Calls(null, listOf(call("read_brief"))), AgentTurn.Final("Andrea is away, so you drop off.")))
        assertEquals(VoiceConversation.Next.Say("Andrea is away, so you drop off."), t.next("Anything in the brief about today?"))
        val none = Model(AgentTurn.Calls(null, listOf(call("read_brief", "aloud" to true))), AgentTurn.Final("No brief yet today."))
        assertEquals(VoiceConversation.Next.Say("No brief yet today."), talk(none, brief = null).next("Read me the brief"))
        val result = (none.calls.last().second.last() as AgentMessage.ToolResults).results.single().content
        assertEquals("There is no brief for today yet.", result)
    }

    @Test
    fun `a message goes to orrery, which asks the owner, and a refusal is said in words`() = runBlocking {
        val t = talk(Model(AgentTurn.Calls(null, listOf(call("orrery_instruct", "text" to "text Andrea I'm running late"))), AgentTurn.Final("Done, Andrea will hear.")))
        assertEquals(VoiceConversation.Next.Say("Done, Andrea will hear."), t.next("Tell Andrea I'm running late"))
        assertEquals(listOf("text Andrea I'm running late"), told)
        val refused = Model(AgentTurn.Calls(null, listOf(call("orrery_instruct", "text" to "x"))), AgentTurn.Final("Orrery did not take it."))
        talk(refused, tell = { error("the day's limit") }).next("x")
        val result = (refused.calls.last().second.last() as AgentMessage.ToolResults).results.single().content
        assertEquals("Orrery did not take it: the day's limit", result)
    }

    @Test
    fun `it ends on the owner's word with no model call, and after twenty turns`() = runBlocking {
        val model = Model()
        assertEquals(VoiceConversation.Next.End(VoiceConversation.GOODBYE), talk(model).next("That's all, thanks"))
        assertTrue(model.calls.isEmpty())
        val long = Model(*Array(VoiceConversation.MAX_TURNS) { AgentTurn.Final("ok $it") })
        val t = talk(long)
        repeat(VoiceConversation.MAX_TURNS) { t.next("more $it") }
        assertEquals(VoiceConversation.Next.End(VoiceConversation.TOO_LONG), t.next("and more"))
        assertTrue(long.calls.last().second.size <= VoiceConversation.MAX_CONTEXT * 2 + 1, "only the recent turns go to the model")
    }
}
