package io.nisfeb.talon.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
