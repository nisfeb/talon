package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Brief
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    private fun talk(model: Model, brief: Brief? = this.brief, tell: (suspend (String) -> String)? = { told += it; "Done. Orrery will text Andrea." }, extra: List<Tool> = emptyList()) =
        VoiceConversation(
            model.completer,
            tools = listOf(tool("list_events", false), tool("send_message", true), tool("orrery_instruct", true), tool("orrery_read", false)) + extra,
            tell = tell,
            brief = { brief },
        )

    // sneagan, 2026-10-19: memory parity with Claude Code. The index and
    // a recall reach a turn only while the phone is unlocked.
    private val mind = LatticeMind(
        listOf(tool("memory_recall", false), tool("memory_save", false)),
        "MEMORY rules",
        "LATTICE INDEX core",
    ) { q -> "RECALLED for $q" }

    @Test
    fun `an unlocked turn has the index and the recall, a locked one neither, nor memory's writes`() = runBlocking {
        var locked = false
        val model = Model(AgentTurn.Final("It leaves at seven."), AgentTurn.Final("Unlock to hear it."))
        val t = VoiceConversation(model.completer, tools = mind.tools, tell = null, brief = { null }, locked = { locked }, memory = mind)
        t.next("when does my flight leave")
        val (system, messages, tools) = model.calls[0]
        assertTrue("MEMORY rules" in system && "LATTICE INDEX core" in system, system)
        assertEquals(AgentMessage.User("RECALLED for when does my flight leave\n\nwhen does my flight leave"), messages.last())
        assertEquals(listOf("memory_recall", "memory_save", "read_brief"), tools)
        assertEquals("when does my flight leave", t.said.last().words, "kept as said, not as asked")
        locked = true
        t.next("what do you remember about me")
        val (system2, messages2, tools2) = model.calls[1]
        assertTrue("MEMORY rules" in system2 && "LATTICE INDEX" !in system2, system2)
        assertEquals(AgentMessage.User("what do you remember about me"), messages2.last())
        assertEquals(listOf("memory_recall", "read_brief"), tools2)
    }

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
        // In mind as the reading turn's reply, marked as others' words; not
        // in the system prompt, which went again with every later turn.
        assertEquals(VoiceConversation.prompt, system, "Archie's own prompt, and nothing read out")
        assertTrue(system.startsWith(DATA_NOT_INSTRUCTIONS) && "orrery_instruct" in system && "read_brief with aloud" in system, "Archie's own prompt")
        assertEquals(AgentMessage.User("Yes, read me the brief"), messages[0])
        assertEquals(AgentMessage.Assistant(VoiceConversation.READING + "\n\n" + VoiceConversation.READ_OUT + brief.text, emptyList()), messages[1])
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

    // The evening review by voice (orrery 130): "approve A1", "A2 due
    // Friday", and the week's review read out as the brief is.
    private val acted = mutableListOf<Triple<String, String?, String?>>()
    private fun actTool(fault: String? = null) = VoiceConversation.briefActionTool({ brief }) { id, status, due -> acted += Triple(id, status, due); fault }
    private fun JsonObject.with(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    @Test
    fun `brief_action answers an action by its tag, its status or its due or both`() = runBlocking {
        assertEquals("A1 marked approved.", actTool().execute(JsonObject(emptyMap()).with("tag" to "a1", "status" to "Approved")))
        assertEquals("A1 due 2026-10-30.", actTool().execute(JsonObject(emptyMap()).with("tag" to "A1", "due" to "2026-10-30")))
        assertEquals("A1 marked done, no due now.", actTool().execute(JsonObject(emptyMap()).with("tag" to "A1", "status" to "done", "due" to "none")))
        actTool().execute(JsonObject(emptyMap()).with("tag" to "A1", "due" to "2026-10-30T22:00:00Z"))
        assertEquals(
            listOf(Triple("a-1", "approved", null), Triple("a-1", null, "2026-10-30"), Triple("a-1", "done", "none"), Triple("a-1", null, "2026-10-30T22:00:00Z")),
            acted.toList(),
            "the brief's id for the tag, never the tag",
        )
    }

    @Test
    fun `brief_action refuses what orrery would, and says what failed`() = runBlocking {
        val bad = listOf(
            JsonObject(emptyMap()).with("status" to "done") to "Error: tag is required.",
            JsonObject(emptyMap()).with("tag" to "A1") to "Error: give a status or a due.",
            JsonObject(emptyMap()).with("tag" to "A1", "status" to "maybe") to "Error: status is approved, dismissed or done.",
            JsonObject(emptyMap()).with("tag" to "A1", "due" to "friday") to "Error: due is YYYY-MM-DD, an ISO 8601 UTC time, or none.",
            JsonObject(emptyMap()).with("tag" to "A9", "status" to "done") to "Today's brief has no A9.",
        )
        for ((args, said) in bad) assertEquals(said, actTool().execute(args), args.toString())
        assertTrue(acted.isEmpty(), "nothing sent: $acted")
        assertEquals("Orrery did not take that for A1: HTTP 409: only an open action is moved", actTool("HTTP 409: only an open action is moved").execute(JsonObject(emptyMap()).with("tag" to "A1", "due" to "2026-10-30")))
        val noBrief = VoiceConversation.briefActionTool({ null }) { _, _, _ -> error("not asked") }
        assertEquals("Today's brief has no A1.", noBrief.execute(JsonObject(emptyMap()).with("tag" to "A1", "status" to "done")))
        val unread = VoiceConversation.briefActionTool({ error("ship down") }) { _, _, _ -> error("not asked") }
        assertEquals("Could not read the brief from orrery: ship down", unread.execute(JsonObject(emptyMap()).with("tag" to "A1", "status" to "done")))
        val throws = VoiceConversation.briefActionTool({ brief }) { _, _, _ -> error("HTTP 500: boom") }
        assertEquals("Orrery did not take that for A1: HTTP 500: boom", throws.execute(JsonObject(emptyMap()).with("tag" to "A1", "status" to "done")))
    }

    @Test
    fun `an empty field is no field to brief_action`() = runBlocking {
        assertEquals("Error: tag is required.", actTool().execute(JsonObject(emptyMap()).with("tag" to " ", "status" to "done")))
        assertEquals("A1 due 2026-10-30.", actTool().execute(JsonObject(emptyMap()).with("tag" to "A1", "status" to "", "due" to "2026-10-30")))
        assertEquals("A1 marked done.", actTool().execute(JsonObject(emptyMap()).with("tag" to "A1", "status" to "done", "due" to "")))
        assertEquals(listOf(Triple("a-1", null, "2026-10-30"), Triple("a-1", "done", null)), acted.toList())
    }

    @Test
    fun `brief_action is offered with Orrery on, and not to a locked phone`() {
        var locked = false
        val t = VoiceConversation(Model().completer, listOf(tool("list_events", false)), tell = null, brief = { brief }, locked = { locked }, act = { _, _, _ -> null })
        assertEquals(listOf("list_events", "read_brief", "brief_action"), t.toolNames)
        locked = true
        assertEquals(listOf("list_events", "read_brief"), t.toolNames, "whoever holds a locked phone answers nothing of orrery's")
        assertEquals(listOf("list_events", "orrery_read", "read_brief"), talk(Model(), tell = null).toolNames, "no act, no brief_action")
        assertTrue("brief_action" in VoiceConversation.prompt && "list_tasks" in VoiceConversation.prompt)
    }

    @Test
    fun `the week's review is read out as the brief is, and none is said so`() = runBlocking {
        val week = Brief("2026-10-25", null, false, emptyMap(), "The week\n\nWork ran long.")
        val model = Model(AgentTurn.Calls(null, listOf(call("read_brief", "aloud" to true, "which" to "week"))))
        val t = VoiceConversation(model.completer, emptyList(), tell = null, brief = { brief }, week = { week })
        val read = t.next("Read me the week's review") as VoiceConversation.Next.Say
        assertEquals(VoiceConversation.WEEK_READING, read.line)
        assertEquals(listOf("The week", "Work ran long."), read.inFull)
        val none = Model(AgentTurn.Calls(null, listOf(call("read_brief", "which" to "week"))), AgentTurn.Final("None yet."))
        VoiceConversation(none.completer, emptyList(), tell = null, brief = { brief }, week = { null }).next("and the week?")
        assertEquals("There is no week's review yet.", (none.calls.last().second.last() as AgentMessage.ToolResults).results.single().content)
        val failing = VoiceConversation.briefTool({ brief }, week = { error("ship down") })
        assertEquals("Could not read the week's review from orrery: ship down", failing.execute(JsonObject(emptyMap()).with("which" to "week")))
        // Offered with the week's reader, and said so; aloud only where it can be read out.
        val both = VoiceConversation.briefTool({ brief }, week = { week }) { _, _ -> }
        assertTrue("which" in both.spec.parameters.toString() && "aloud" in both.spec.parameters.toString(), both.spec.parameters.toString())
        assertTrue("week's review" in both.spec.description && "aloud true" in both.spec.description, both.spec.description)
        // Without the week's reader, which is not offered, and today's brief is what is read.
        val today = VoiceConversation.briefTool({ brief })
        assertTrue("week" !in today.spec.description && "aloud" !in today.spec.parameters.toString(), today.spec.toString())
        assertTrue("which" !in today.spec.parameters.toString(), today.spec.parameters.toString())
        assertEquals(brief.text, today.execute(JsonObject(emptyMap()).with("which" to "week")))
    }

    // "Read me my unread messages" (sneagan, 2026-10-16): read out as
    // list_unread gives them, but not the ids it gives the model.
    private val unreadText = "Andrea:\ncan you grab milk?\nand eggs.\n\nIn Family · General:\nNec: lunch anyone?\n\n" +
        "${Briefing.UNREAD_IDS} Andrea = ~bus; Family · General = chat/~nec/general"
    private fun unread(text: String = unreadText) = tool(VoiceConversation.UNREAD_TOOL, false) { text }

    @Test
    fun `unread messages are read out word for word, without their ids, and stay in mind`() = runBlocking {
        val model = Model(AgentTurn.Calls(null, listOf(call("list_unread", "aloud" to true))), AgentTurn.Final("Andrea asked for milk."))
        val t = talk(model, extra = listOf(unread()))
        val read = t.next("Read me my unread messages") as VoiceConversation.Next.Say
        assertEquals(VoiceConversation.UNREAD_READING, read.line)
        assertEquals(listOf("Andrea: can you grab milk? and eggs.", "In Family · General: Nec: lunch anyone?"), read.inFull)
        assertTrue("Nec: lunch anyone?" in read.shown && Briefing.UNREAD_IDS !in read.shown && read.inFull.none { Briefing.UNREAD_IDS in it }, "the ids are the model's, neither said nor shown")
        assertEquals(1, model.calls.size, "said by the tool's result, not a second call")
        t.next("What did Andrea want?")
        val reply = (model.calls.last().second[1] as AgentMessage.Assistant).text.orEmpty()
        assertTrue(VoiceConversation.READ_OUT in reply && "can you grab milk?" in reply, "the next turn knows what was read, as others' words: $reply")
        assertTrue("can you grab milk?" !in model.calls.last().first, "and not in the system prompt")
        assertTrue("list_unread's, read to them the same way with aloud true" in model.calls.first().first, "Archie is told how")
    }

    // A turn that failed after a write asked to be said again, and the
    // write was done twice.
    @Test
    fun `a turn that stops after a write says it may have done part of it`() = runBlocking {
        val task = tool("create_task", true) { "Added task \"Milk\"." }
        // With a read beside it, the model is asked to word the turn, and fails.
        val failing = Model(AgentTurn.Calls(null, listOf(call("create_task", "name" to "Milk"), call("list_events"))))
        val t = talk(failing, extra = listOf(task))
        assertFailsWith<IllegalStateException> { t.next("Add milk and text Andrea") }
        val said = t.failed("Add milk and text Andrea", "No network", "No network. Say it again, or close me.")
        assertEquals("No network. It may have done part of it before it stopped, so check before saying it again.", said.line)
        assertTrue("- Added task \"Milk\"." in said.shown, said.shown)
        assertEquals(DictationFlow.Earlier("Add milk and text Andrea", said.shown), t.said.last(), "kept, so the next turn knows")
    }

    // Out of time in the middle, as the card's budget cuts it: on real time.
    @Test
    fun `a turn out of time after a write or during one says so too`() = runBlocking {
        var asked = 0
        val after = VoiceConversation(
            { _, _, _ -> if (asked++ == 0) AgentTurn.Calls(null, listOf(call("create_task", "name" to "Milk"), call("list_events"))) else kotlinx.coroutines.awaitCancellation() },
            tools = listOf(tool("create_task", true) { "Added task \"Milk\"." }, tool("list_events", false)), tell = null, brief = { null },
        )
        assertNull(kotlinx.coroutines.withTimeoutOrNull(300) { after.next("Add milk") })
        val late = after.failed("Add milk", "That took too long", "That took too long. Say it again?")
        assertTrue(late.line.startsWith("That took too long. It may have done part of it") && "- Added task \"Milk\"." in late.shown, late.shown)
        val during = VoiceConversation(
            { _, _, _ -> AgentTurn.Calls(null, listOf(call("create_task", "name" to "Milk"))) },
            tools = listOf(Tool(ToolSpec("create_task", "", toolSchema(required = emptyList())), true) { kotlinx.coroutines.awaitCancellation() }), tell = null, brief = { null },
        )
        assertNull(kotlinx.coroutines.withTimeoutOrNull(300) { during.next("Add milk") })
        assertTrue("- create_task, still going when it stopped" in during.failed("Add milk", "That took too long", "again").shown)
    }

    @Test
    fun `a turn that stops before any write is asked again`() = runBlocking {
        val read = Model(AgentTurn.Calls(null, listOf(call("list_events"))))
        val t = talk(read)
        assertFailsWith<IllegalStateException> { t.next("What's on?") }
        assertEquals(VoiceConversation.Next.Say("No network. Say it again, or close me."), t.failed("What's on?", "No network", "No network. Say it again, or close me."))
        assertTrue(t.said.none { it.words == "What's on?" }, "nothing to keep")
    }

    // A long read-out went whole with every later turn.
    @Test
    fun `a long read-out is kept in part`() = runBlocking {
        val long = "Saturday\n\n" + "x".repeat(VoiceConversation.READ_KEPT * 2)
        val model = Model(AgentTurn.Calls(null, listOf(call("read_brief", "aloud" to true))), AgentTurn.Final("ok"))
        val t = talk(model, brief = brief.copy(text = long))
        t.next("Read me the brief")
        t.next("And then?")
        val reply = (model.calls.last().second[1] as AgentMessage.Assistant).text.orEmpty()
        assertEquals(VoiceConversation.READING + "\n\n" + VoiceConversation.READ_OUT + long.take(VoiceConversation.READ_KEPT), reply)
    }

    @Test
    fun `unread asked about but not to be read, or with nothing unread, is the model's to word`() = runBlocking {
        val asked = Model(AgentTurn.Calls(null, listOf(call("list_unread", "from" to "~bus"))), AgentTurn.Final("Andrea asked for milk and eggs."))
        assertEquals(VoiceConversation.Next.Say("Andrea asked for milk and eggs."), talk(asked, extra = listOf(unread())).next("What did Andrea say?"))
        val none = Model(AgentTurn.Calls(null, listOf(call("list_unread", "aloud" to true))), AgentTurn.Final("Nothing new."))
        assertEquals(VoiceConversation.Next.Say("Nothing new."), talk(none, extra = listOf(unread("Nothing unread."))).next("Read me my messages"))
        assertEquals(2, none.calls.size)
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

    // The lock was read once, when the conversation began, and an unlocked
    // start kept delete_event for half an hour after the phone locked.
    @Test
    fun `a phone that locks mid-conversation loses the writes a locked phone may not make`() = runBlocking {
        var locked = false
        val model = Model(AgentTurn.Final("Sure."), AgentTurn.Final("Done."), AgentTurn.Final("Back."))
        val t = VoiceConversation(
            model.completer,
            tools = listOf(tool("list_events", false), tool("delete_event", true), tool("create_event", true), tool("orrery_file_text", true)),
            tell = { "Done." },
            brief = { brief },
            locked = { locked },
        )
        t.next("What's on today?")
        locked = true
        t.next("Delete the dentist")
        locked = false
        t.next("And now?")
        val offered = model.calls.map { it.third }
        assertEquals(listOf("list_events", "delete_event", "create_event", "orrery_file_text", "orrery_instruct", "read_brief"), offered[0])
        assertEquals(listOf("list_events", "create_event", "orrery_file_text", "orrery_instruct", "read_brief"), offered[1], "no delete behind a lock")
        assertEquals(offered[0], offered[2], "unlocked again, it is back")
        assertEquals(offered[2], t.toolNames)
        val unsaid = VoiceConversation(model.completer, listOf(tool("delete_event", true)), null, { null })
        assertEquals(listOf("delete_event", "read_brief"), unsaid.toolNames, "unlocked unless the host says")
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

    @Test
    fun `a locked conversation reads no messages until the fingerprint, and asks again on a later turn`() = runBlocking {
        var unlocks = 0
        val model = Model(
            AgentTurn.Calls(null, listOf(call("list_unread", "aloud" to true))),
            AgentTurn.Final("Unlock your phone to hear them."),
            AgentTurn.Calls(null, listOf(call("list_unread", "aloud" to true))),
        )
        val unread = tool("list_unread", false) { "Andrea:\nThe code is 4471.\n\n${Briefing.UNREAD_IDS} Andrea = ~bus" }
        val t = VoiceConversation(
            model.completer, listOf(unread, tool("list_events", false)), tell = null, brief = { null },
            locked = { true }, unlock = { ++unlocks > 1 },
        )
        val first = t.next("read me my messages") as VoiceConversation.Next.Say
        assertEquals("Unlock your phone to hear them.", first.line)
        assertTrue(first.inFull.isEmpty() && "4471" !in first.shown, "read out on a locked phone")
        val result = model.calls[1].second.last() as AgentMessage.ToolResults
        assertEquals(DictationAgent.UNLOCK_TO_READ, result.results.single().content)
        val second = t.next("okay, read them") as VoiceConversation.Next.Say
        assertEquals(VoiceConversation.UNREAD_READING, second.line)
        assertTrue("4471" in second.shown)
        assertEquals(2, unlocks, "a no on one turn held the next one too")
        assertTrue("send_message" !in t.toolNames && "send_mail" !in t.toolNames)
    }
}
