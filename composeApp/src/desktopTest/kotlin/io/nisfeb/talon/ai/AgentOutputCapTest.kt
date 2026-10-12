package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What an assistant turn may write, as each provider is asked. At 1,024
 * Opus 5.5 thought its way to the end of it on a request for a family's
 * events and reminders, answered nothing twice, and the owner saw
 * EmptyAnswer (2026-10-09 and 2026-10-12, finish=length every time).
 */
class AgentOutputCapTest {
    private var sent: JsonObject? = null

    private fun http(answer: String) = HttpClient(MockEngine { req ->
        sent = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private val openAiShaped = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""

    private suspend fun ask(cfg: AiSettings.Config, answer: String = openAiShaped): JsonObject {
        AgentClient(http = http(answer)) { cfg }.completeWithTools("system", listOf(AgentMessage.User("hi")), emptyList())
        return sent!!
    }

    private fun cfg(provider: AiSettings.Provider, baseUrl: String? = null) =
        AiSettings.Config(provider = provider, apiKey = "sk-test", model = "m/1", baseUrl = baseUrl)

    @Test
    fun `an assistant turn may write 16,384 tokens, as each provider reads it`() = runTest {
        assertEquals(16_384, AGENT_MAX_OUTPUT)
        // An Armillary lease: OpenRouter's base, reached as a server of the OpenAI shape.
        assertEquals("16384", ask(cfg(AiSettings.Provider.Custom, "https://openrouter.ai/api/v1"))["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("16384", ask(cfg(AiSettings.Provider.OpenRouter))["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("16384", ask(cfg(AiSettings.Provider.OpenAi))["max_completion_tokens"]?.jsonPrimitive?.content)
        val anthropic = ask(cfg(AiSettings.Provider.Anthropic), """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        assertEquals("16384", anthropic["max_tokens"]?.jsonPrimitive?.content)
    }
}
