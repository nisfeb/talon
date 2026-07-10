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
    fun `loop stops at the step cap when the model never finishes`() = runBlocking {
        // Model always asks for the read tool again — never returns Final.
        val read = Tool(spec("read"), write = false) { "ok" }
        val completer = AgentLoop.Completer { _, _, _ ->
            AgentTurn.Calls(null, listOf(call("read")))
        }
        val loop = AgentLoop(completer, listOf(read), maxSteps = 3)
        val out = loop.run("q", confirm = { _, _ -> true })
        assertTrue(out.contains("Stopped after 3 steps"), "expected step-cap message, got: $out")
    }

    @Test
    fun `unknown tool is reported, loop continues`() = runBlocking {
        val loop = AgentLoop(
            scripted(AgentTurn.Calls(null, listOf(call("ghost"))), AgentTurn.Final("recovered")).completer,
            emptyList(),
        )
        assertEquals("recovered", loop.run("q", confirm = { _, _ -> true }))
    }
}
