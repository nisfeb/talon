package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One-shot completions, as each provider is spoken to: where the call
 * goes, with what key and cap, what comes back as the answer and what it
 * cost, and what a refusal or an empty balance says.
 */
class AiClientTest {
    private class Sent(val url: String, val headers: Map<String, String>, val body: String)

    private val sent = mutableListOf<Sent>()

    private fun client(cfg: AiSettings.Config, status: HttpStatusCode = HttpStatusCode.OK, answer: String) =
        AiClient(
            http = HttpClient(MockEngine { req ->
                sent += Sent(req.url.toString(), req.headers.entries().associate { it.key to it.value.joinToString() }, req.body.toByteArray().decodeToString())
                respond(answer, status, headersOf("Content-Type", "application/json"))
            }) { install(HttpTimeout) },
            settingsProvider = { cfg },
        )

    private fun cfg(provider: AiSettings.Provider, model: String? = null, baseUrl: String? = null) =
        AiSettings.Config(provider = provider, apiKey = "sk-test", model = model, baseUrl = baseUrl)

    private fun body() = Json.parseToJsonElement(sent.single().body).jsonObject

    @Test
    fun anthropicTakesTheTextAfterAThinkingBlockAndPricesTheCall() = runTest {
        val c = client(
            cfg(AiSettings.Provider.Anthropic, model = "claude-sonnet-4-5"),
            answer = """{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"Hello"}],
                "usage":{"input_tokens":1000000,"output_tokens":0}}""",
        )
        assertEquals("Hello", c.complete("be brief", "hi", maxOutputTokens = 50))
        val s = sent.single()
        assertEquals("https://api.anthropic.com/v1/messages", s.url)
        assertEquals("sk-test" to "2023-06-01", s.headers["x-api-key"] to s.headers["anthropic-version"])
        assertEquals("be brief", body()["system"]?.jsonPrimitive?.content)
        assertEquals("50", body()["max_tokens"]?.jsonPrimitive?.content)
        assertEquals(3.0, c.lastCostUsd, "a million input tokens at Sonnet's list price")
    }

    @Test
    fun openRouterAsksForItsCostAndSaysWhatItWas() = runTest {
        val c = client(
            cfg(AiSettings.Provider.OpenRouter),
            answer = """{"choices":[{"message":{"content":"Hi"}}],"usage":{"prompt_tokens":3,"completion_tokens":1,"cost":0.0042}}""",
        )
        assertEquals("Hi", c.complete(null, "hi"))
        val s = sent.single()
        assertEquals("https://openrouter.ai/api/v1/chat/completions", s.url)
        assertEquals("Bearer sk-test", s.headers["Authorization"])
        assertEquals("anthropic/claude-sonnet-4", body()["model"]?.jsonPrimitive?.content, "the default model when none is set")
        assertTrue("usage" in body())
        assertEquals(0.0042, c.lastCostUsd)
    }

    @Test
    fun openAiCapsTheAnswerByItsOwnName() = runTest {
        client(cfg(AiSettings.Provider.OpenAi), answer = """{"choices":[{"message":{"content":"Hi"}}]}""").complete("sys", "hi", maxOutputTokens = 7)
        assertEquals("7", body()["max_completion_tokens"]?.jsonPrimitive?.content)
        assertEquals("system", body()["messages"].toString().substringAfter("\"role\":\"").substringBefore('"'), "the system prompt goes first")
    }

    @Test
    fun aCustomServerIsCalledAtItsChatEndpointAndNeedsAModel() = runTest {
        val answer = """{"choices":[{"message":{"content":"ok"}}]}"""
        client(cfg(AiSettings.Provider.Custom, model = "llama", baseUrl = "http://box.local/v1/"), answer = answer).complete(null, "hi")
        client(cfg(AiSettings.Provider.Custom, model = "llama", baseUrl = "http://box.local/v1/chat/completions"), answer = answer).complete(null, "hi")
        assertEquals(listOf("http://box.local/v1/chat/completions"), sent.map { it.url }.distinct())
        assertFailsWith<IllegalStateException> { client(cfg(AiSettings.Provider.Custom, baseUrl = "http://box.local"), answer = answer).complete(null, "hi") }
        assertFailsWith<IllegalStateException> { client(cfg(AiSettings.Provider.Custom, model = "llama"), answer = answer).complete(null, "hi") }
    }

    @Test
    fun aRefusalSaysWhoRefusedAndWhy() = runTest {
        val e = assertFailsWith<ModelHttpError> {
            client(cfg(AiSettings.Provider.Anthropic), HttpStatusCode.Unauthorized, """{"error":{"message":"invalid x-api-key"}}""").complete(null, "hi")
        }
        assertEquals(401, e.status)
        assertEquals("api.anthropic.com 401: invalid x-api-key", e.message)
    }

    @Test
    fun anEmptyBalanceSaysWhereToTopUp() = runTest {
        val e = assertFailsWith<ModelHttpError> {
            client(cfg(AiSettings.Provider.OpenRouter), HttpStatusCode.PaymentRequired, """{"error":{"message":"insufficient credits"}}""").complete(null, "hi")
        }
        assertTrue(isOutOfCredit(e.message), e.message)
    }

    @Test
    fun anAnswerThatIsNotJsonOrHasNoTextIsAnError() = runTest {
        assertTrue(assertFailsWith<IllegalStateException> { client(cfg(AiSettings.Provider.OpenAi), answer = "<html>").complete(null, "hi") }.message!!.contains("bad JSON"))
        // No text at all is no answer, said in words (it was "no content in response").
        assertTrue(!assertFailsWith<NoAnswer> { client(cfg(AiSettings.Provider.Anthropic), answer = """{"content":[]}""").complete(null, "hi") }.outOfRoom)
    }

    @Test
    fun aProviderThatGivesOnlyTokensHasNoKnownCost() = runTest {
        val c = client(cfg(AiSettings.Provider.OpenAi), answer = """{"choices":[{"message":{"content":"Hi"}}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""")
        c.complete(null, "hi")
        assertNull(c.lastCostUsd)
    }

    // A user's catch-ups came back as the word "null" (2026-10-31): the
    // model thought through all 512 tokens and sent "content": null.

    @Test
    fun aNullAnswerIsNoAnswerAndNeverTheWordNull() = runTest {
        val c = client(
            cfg(AiSettings.Provider.Custom, model = "m", baseUrl = "https://llm.example/v1"),
            answer = """{"choices":[{"message":{"role":"assistant","content":null,"reasoning":"so much thought"},"finish_reason":"length"}],
                "usage":{"prompt_tokens":900,"completion_tokens":16384}}""",
        )
        val e = assertFailsWith<NoAnswer> { c.complete("sys", "hi") }
        assertTrue(e.outOfRoom, "it stopped at the cap")
        assertEquals("the model ran out of room before it answered", e.message, "what Copy error details carries")
        assertEquals("the model sent no answer", NoAnswer(outOfRoom = false).message)
    }

    @Test
    fun aBlankAnswerThatStoppedIsNoAnswerWithRoomLeft() = runTest {
        val c = client(
            cfg(AiSettings.Provider.OpenRouter, model = "m"),
            answer = """{"choices":[{"message":{"role":"assistant","content":"  "},"finish_reason":"stop"}]}""",
        )
        assertTrue(!assertFailsWith<NoAnswer> { c.complete("sys", "hi") }.outOfRoom)
    }

    @Test
    fun anthropicThatOnlyThoughtBeforeItsCapIsNoAnswer() = runTest {
        val c = client(
            cfg(AiSettings.Provider.Anthropic, model = "claude-sonnet-5-5"),
            answer = """{"content":[{"type":"thinking","thinking":"hmm"}],"stop_reason":"max_tokens"}""",
        )
        assertTrue(assertFailsWith<NoAnswer> { c.complete("sys", "hi") }.outOfRoom)
    }

    @Test
    fun everyCallHasTheAssistantsRoomUnlessToldLess() = runTest {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"fine"},"finish_reason":"stop"}]}"""
        client(cfg(AiSettings.Provider.Custom, model = "m", baseUrl = "https://llm.example/v1"), answer = ok).complete("s", "u")
        assertEquals("16384", body()["max_tokens"]?.jsonPrimitive?.content)
        sent.clear()
        client(cfg(AiSettings.Provider.OpenAi, model = "gpt-6"), answer = ok).complete("s", "u")
        assertEquals("16384", body()["max_completion_tokens"]?.jsonPrimitive?.content, "OpenAI's own name for it")
        sent.clear()
        client(cfg(AiSettings.Provider.Anthropic, model = "claude-sonnet-5-5"), answer = """{"content":[{"type":"text","text":"fine"}]}""").complete("s", "u")
        assertEquals("16384", body()["max_tokens"]?.jsonPrimitive?.content)
    }

    @Test
    fun aCatchUpAsksForTheSameRoom() = runTest {
        val c = client(
            cfg(AiSettings.Provider.Custom, model = "m", baseUrl = "https://llm.example/v1"),
            answer = """{"choices":[{"message":{"role":"assistant","content":"Rose is late"},"finish_reason":"stop"}]}""",
        )
        val said = io.nisfeb.talon.data.MessageEntity("~bus", "1", "~bus", 1L, """[{"inline":["running late"]}]""", "/chat")
        assertEquals("Rose is late", AiFeatures(c).catchMeUp(listOf(said)) { it })
        assertEquals("16384", body()["max_tokens"]?.jsonPrimitive?.content, "it was 512")
    }

    @Test
    fun noAnswerSaysWhatHappenedInWords() {
        assertEquals(
            "Couldn't catch you up: the model used all its room before it answered. Try again, or pick a model that thinks less.",
            modelProblem("Couldn't catch you up", NoAnswer(outOfRoom = true)).line,
        )
        assertEquals("Couldn't catch you up: the model sent back no answer. Try again.", modelProblem("Couldn't catch you up", NoAnswer(outOfRoom = false)).line)
    }

    @Test
    fun triageInTheCloudHasRoomWhatAModelOnTheDeviceNeedsDoesNotLimit() = runTest {
        val http = HttpClient(MockEngine { req ->
            sent += Sent(req.url.toString(), emptyMap(), req.body.toByteArray().decodeToString())
            respond("""{"choices":[{"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val rung = io.nisfeb.talon.orrery.CloudRung(http) { cfg(AiSettings.Provider.Custom, model = "m", baseUrl = "https://llm.example/v1") }
        assertEquals("{}", rung.open().complete("sys", "user", null, 256))
        assertEquals("16384", body()["max_tokens"]?.jsonPrimitive?.content, "256 is a grammar-bound model's room")
    }
}
