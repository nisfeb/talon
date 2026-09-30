package io.nisfeb.talon.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the agent loop's trust boundary and control flow with a scripted
 * model: write tools must clear confirmation before executing, reads
 * never prompt, and the loop is bounded by the step cap.
 */
class AgentLoopTest {

    private fun spec(name: String) = ToolSpec(name, name, buildJsonObject { put("type", "object") })
    private fun call(name: String) = ToolCall("id-$name", name, JsonObject(emptyMap()))

    /** A model that emits a fixed list of turns, one per round. */
    private fun scripted(vararg turns: AgentTurn) = object {
        var i = 0
        val completer = AgentLoop.Completer { _, _, _ -> turns[i++] }
    }

    @Test
    fun `read tool runs without confirmation`() = runBlocking {
        var ran = false
        var confirmAsked = false
        val read = Tool(spec("read"), write = false) { ran = true; "ok" }
        val loop = AgentLoop(
            scripted(AgentTurn.Calls(null, listOf(call("read"))), AgentTurn.Final("done")).completer,
            listOf(read),
        )
        val out = loop.run("q", confirm = { _, _ -> confirmAsked = true; true })
        assertEquals("done", out)
        assertTrue(ran)
        assertFalse(confirmAsked, "reads must not ask for confirmation")
    }

    @Test
    fun `declined write does not execute and reports back`() = runBlocking {
        var executed = false
        val write = Tool(spec("send"), write = true) { executed = true; "sent" }
        val loop = AgentLoop(
            scripted(AgentTurn.Calls(null, listOf(call("send"))), AgentTurn.Final("ok")).completer,
            listOf(write),
        )
        val out = loop.run("q", confirm = { _, _ -> false })
        assertEquals("ok", out)
        assertFalse(executed, "a declined write must never run")
    }

    @Test
    fun `confirmed write executes`() = runBlocking {
        var executed = false
        val write = Tool(spec("send"), write = true) { executed = true; "sent" }
        val loop = AgentLoop(
            scripted(AgentTurn.Calls(null, listOf(call("send"))), AgentTurn.Final("ok")).completer,
            listOf(write),
        )
        loop.run("q", confirm = { _, _ -> true })
        assertTrue(executed)
    }

    @Test
    fun `at the step cap the model answers without tools rather than being cut off`() = runBlocking {
        // Model asks for the read tool every time it has one, and answers
        // only once it has none: the loop's last turn must take them away.
        var reads = 0
        val read = Tool(spec("read"), write = false) { reads++; "ok" }
        val completer = AgentLoop.Completer { _, _, tools ->
            if (tools.isEmpty()) AgentTurn.Final("did three reads; the fourth is left")
            else AgentTurn.Calls(null, listOf(call("read")))
        }
        val loop = AgentLoop(completer, listOf(read), maxSteps = 3)
        val out = loop.run("q", confirm = { _, _ -> true })
        assertEquals("did three reads; the fourth is left", out)
        assertEquals(3, reads, "the cap still bounds the work")
    }

    @Test
    fun `a model that still will not answer gets a way to carry on, not a dead end`() = runBlocking {
        val read = Tool(spec("read"), write = false) { "ok" }
        val completer = AgentLoop.Completer { _, _, _ -> AgentTurn.Calls(null, listOf(call("read"))) }
        val out = AgentLoop(completer, listOf(read), maxSteps = 2).run("q", confirm = { _, _ -> true })
        assertTrue("carry on" in out, out)
    }

    @Test
    fun `unknown tool is reported, loop continues`() = runBlocking {
        val turns = listOf(AgentTurn.Calls(null, listOf(call("ghost"))), AgentTurn.Final("recovered"))
        var told: List<AgentMessage> = emptyList()
        val loop = AgentLoop(AgentLoop.Completer { _, messages, _ -> told = messages; turns[told.count { it is AgentMessage.ToolResults }] }, emptyList())
        assertEquals("recovered", loop.run("q", confirm = { _, _ -> true }))
        val said = told.filterIsInstance<AgentMessage.ToolResults>().single().results.single().content
        assertEquals("Error: unknown tool 'ghost'.", said)
    }
}
