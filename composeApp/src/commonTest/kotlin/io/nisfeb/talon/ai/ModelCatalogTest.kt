package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The catalog against each provider's list shape, as the providers answered it on 2026-09-19. */
class ModelCatalogTest {
    private val seen = mutableListOf<Pair<String, Map<String, String>>>()

    private fun catalog(status: HttpStatusCode = HttpStatusCode.OK) = ModelCatalog(
        HttpClient(MockEngine { req ->
            val url = req.url.toString()
            seen += url to req.headers.entries().associate { it.key to it.value.first() }
            val body = when {
                url.endsWith("/endpoints/zdr") -> """{"data":[
                    {"name":"TypeSafe | typesafe/jev-1.13-20260917","model_id":"typesafe/jev-1.13"},
                    {"name":"Sail | a","model_id":"deepseek/deepseek-v4.1-flash"}]}"""
                "openrouter.ai" in url && url.endsWith("/models") -> """{"data":[
                    {"id":"deepseek/deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","context_length":1048576,"supported_parameters":["tools","max_tokens"]},
                    {"id":"openai/gpt-5","name":"GPT-5","context_length":400000,"supported_parameters":["max_tokens"]}]}"""
                "anthropic.com" in url -> """{"data":[{"id":"claude-opus-5","display_name":"Claude Opus 5","type":"model"}],"has_more":false}"""
                url.endsWith("/key") -> """{"data":{"label":"k"}}"""
                else -> """{"object":"list","data":[{"id":"gpt-5"},{"id":"whisper-1"},{"id":"text-embedding-3-small"},{"id":"gpt-4o-mini-tts"},{"id":"qwen3-8b"}]}"""
            }
            respond(body, status, headersOf("Content-Type", "application/json"))
        }) { install(HttpTimeout) },
    )

    private fun provider(kind: ProviderKind, url: String? = null) = AiProvider("p", kind, "P", url, "sk-test")

    @Test
    fun `openrouter models are marked zdr from its endpoint list, which alone names jev`() = runTest {
        val c = catalog().fetch(provider(ProviderKind.OpenRouter))
        assertTrue(c.jev)
        val byId = c.models.associateBy { it.id }
        assertEquals(setOf("deepseek/deepseek-v4.1-flash", "openai/gpt-5"), byId.keys, "jev is not a chat model")
        assertTrue(byId.getValue("deepseek/deepseek-v4.1-flash").zdr)
        assertFalse(byId.getValue("openai/gpt-5").zdr)
        assertEquals(true, byId.getValue("deepseek/deepseek-v4.1-flash").tools)
        assertEquals(false, byId.getValue("openai/gpt-5").tools)
        assertEquals(1048576, byId.getValue("deepseek/deepseek-v4.1-flash").contextLength)
    }

    @Test
    fun `anthropic is asked with its own headers, openai lists what chats or transcribes`() = runTest {
        val a = catalog().fetch(provider(ProviderKind.Anthropic))
        assertEquals(listOf(ModelInfo("claude-opus-5", "Claude Opus 5", tools = true)), a.models)
        assertEquals("sk-test", seen.last().second["x-api-key"])
        val o = catalog().fetch(provider(ProviderKind.OpenAi))
        assertEquals(listOf("gpt-5", "whisper-1", "qwen3-8b"), o.models.map { it.id })
        assertTrue(o.models.single { it.id == "whisper-1" }.speech)
        assertEquals("Bearer sk-test", seen.last().second["Authorization"])
    }

    @Test
    fun `a server of your own is asked at its address, with or without chat completions`() = runTest {
        catalog().fetch(provider(ProviderKind.OpenAiCompatible, "http://127.0.0.1:1234/v1/chat/completions"))
        assertEquals("http://127.0.0.1:1234/v1/models", seen.last().first)
        catalog().fetch(provider(ProviderKind.OpenAiCompatible, "http://box.local:11434/v1/"))
        assertEquals("http://box.local:11434/v1/models", seen.last().first)
    }

    @Test
    fun `the test button says whether the key works`() = runTest {
        val ok = catalog().check(provider(ProviderKind.OpenRouter))
        assertTrue(ok.ok)
        assertTrue(seen.last().first.endsWith("/api/v1/key"))
        val refused = catalog(HttpStatusCode.Unauthorized).check(provider(ProviderKind.OpenAi))
        assertFalse(refused.ok)
        assertEquals("The key was refused (401).", refused.detail)
    }

    @Test
    fun `jev is offered by an openrouter key whose list names it, or whose list was never fetched`() {
        val fetched = provider(ProviderKind.OpenRouter).withCatalog(Catalog(listOf(ModelInfo("m")), jev = false))
        assertEquals(null, AiProfile(listOf(fetched)).jevProvider())
        assertEquals("p", AiProfile(listOf(fetched.copy(offersJev = true))).jevProvider()?.id)
        assertEquals("p", AiProfile(listOf(provider(ProviderKind.OpenRouter))).jevProvider()?.id)
    }
}
