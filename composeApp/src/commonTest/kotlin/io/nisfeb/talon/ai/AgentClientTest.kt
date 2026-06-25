package io.nisfeb.talon.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the agent wire translation for both provider dialects: requests
 * carry tools + the replayed conversation in the right shape, and
 * tool-call vs final responses parse back into [AgentTurn]. This is the
 * part that only fails at runtime against a live API otherwise.
 */
class AgentClientTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun args(vararg p: Pair<String, String>) =
        buildJsonObject { p.forEach { (k, v) -> put(k, v) } }

    private val convo = listOf(
        AgentMessage.User("hi"),
        AgentMessage.Assistant("let me look", listOf(ToolCall("c1", "search_history", args("query" to "launch")))),
        AgentMessage.ToolResults(listOf(ToolResult("c1", "search_history", "whom=~bus post=1 from=~bus: ship it"))),
    )
    private val tool = ToolSpec("search_history", "search", buildJsonObject { put("type", "object") })

    @Test
    fun `anthropic request shape — tools, tool_use and tool_result`() {
        val req = buildAnthropicRequest("m", "SYS", convo, listOf(tool), 256)
        assertEquals("SYS", req["system"]!!.jsonPrimitive.content)
        assertEquals("search_history", req["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        // input_schema (not "parameters") is the Anthropic key.
        assertTrue(req["tools"]!!.jsonArray[0].jsonObject.containsKey("input_schema"))
        val msgs = req["messages"]!!.jsonArray
        // assistant turn carries a tool_use block
        val asstContent = msgs[1].jsonObject["content"]!!.jsonArray
        assertTrue(asstContent.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" })
        // tool result is a user message with a tool_result block keyed by tool_use_id
        val tr = msgs[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", tr["type"]!!.jsonPrimitive.content)
        assertEquals("c1", tr["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai request shape — system first, function tools, tool role`() {
        val req = buildOpenAiRequest("m", "SYS", convo, listOf(tool), 256)
        val msgs = req["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function", req["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        // assistant tool_calls present; tool result becomes a role:tool message
        assertTrue(msgs[2].jsonObject["tool_calls"]!!.jsonArray.isNotEmpty())
        assertEquals("tool", msgs[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("c1", msgs[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parse anthropic tool_use yields Calls with decoded args`() {
        val body = json.parseToJsonElement(
            """
            {"content":[
              {"type":"text","text":"checking"},
              {"type":"tool_use","id":"c9","name":"send_message","input":{"whom":"~bus","text":"yo"}}
            ],"stop_reason":"tool_use"}
            """.trimIndent(),
        ).jsonObject
        val turn = parseAnthropicTurn(body)
        assertTrue(turn is AgentTurn.Calls)
        turn as AgentTurn.Calls
        assertEquals("checking", turn.text)
        assertEquals("send_message", turn.calls.single().name)
        assertEquals("~bus", turn.calls.single().args["whom"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parse anthropic text-only yields Final`() {
        val body = json.parseToJsonElement(
            """{"content":[{"type":"text","text":"all done"}],"stop_reason":"end_turn"}""",
        ).jsonObject
        assertEquals(AgentTurn.Final("all done"), parseAnthropicTurn(body))
    }

    @Test
    fun `parse openai tool_calls decodes the arguments string`() {
        val body = json.parseToJsonElement(
            """
            {"choices":[{"message":{"role":"assistant","content":null,
              "tool_calls":[{"id":"t1","type":"function",
                "function":{"name":"react","arguments":"{\"whom\":\"~bus\",\"post\":\"5\",\"emoji\":\"👍\"}"}}]}}]}
            """.trimIndent(),
        ).jsonObject
        val turn = parseOpenAiTurn(body)
        assertTrue(turn is AgentTurn.Calls)
        turn as AgentTurn.Calls
        val call = turn.calls.single()
        assertEquals("react", call.name)
        assertEquals("5", call.args["post"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parse openai content-only yields Final`() {
        val body = json.parseToJsonElement(
            """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""",
        ).jsonObject
        assertEquals(AgentTurn.Final("hello"), parseOpenAiTurn(body))
    }
}
