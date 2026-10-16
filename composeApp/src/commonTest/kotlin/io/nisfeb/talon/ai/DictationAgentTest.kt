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

    @Test
    fun `a dictation is told to act, not the loop's default to reading`() {
        val cfg = AiSettings.Config(provider = AiSettings.Provider.OpenAi, apiKey = "k", model = "m")
        val prompt = DictationAgent.prompt(cfg)
        assertTrue(DictationAgent.role in prompt)
        assertTrue(DATA_NOT_INSTRUCTIONS in prompt, "the content-is-data rule is not negotiable")
        assertFalse("Default to reading" in prompt)
        assertTrue("orrery_file_text" in prompt)
        // The owner's own knowledge prompt, where they wrote one.
        assertTrue(DictationAgent.prompt(cfg.copy(urbitKnowledgePrompt = "MY SHIP NOTES")).startsWith("MY SHIP NOTES"))
    }

    private fun tool(name: String, write: Boolean) = Tool(spec(name), write) { "ok" }

    @Test
    fun `a locked phone keeps orrery and the calendar but sends nothing to anyone`() {
        val all = listOf(
            tool("search_history", false), tool("read_mail", false),
            tool("orrery_file_text", true), tool("orrery_instruct", true), tool("orrery_observe", true),
            tool("create_event", true), tool("update_event", true), tool("create_task", true), tool("complete_task", true),
            tool("send_mail", true), tool("send_message", true), tool("delete_event", true),
            tool("share_calendar", true), tool("send_event", true), tool("orrery_configure", true), tool("orrery_correct", true),
        )
        val locked = DictationAgent.toolsFor(all, locked = true).map { it.spec.name }.toSet()
        assertEquals(
            setOf(
                "search_history", "read_mail", "orrery_file_text", "orrery_instruct", "orrery_observe",
                "create_event", "update_event", "create_task", "complete_task",
            ),
            locked,
        )
        assertEquals(all.map { it.spec.name }, DictationAgent.toolsFor(all, locked = false).map { it.spec.name })
    }

    @Test
    fun `a tool offered twice is offered once under its name`() {
        val tools = DictationAgent.toolsFor(listOf(tool("find_person", false), tool("find_person", false)), locked = false)
        assertEquals(listOf("find_person", "find_person_2"), tools.map { it.spec.name })
    }
}
