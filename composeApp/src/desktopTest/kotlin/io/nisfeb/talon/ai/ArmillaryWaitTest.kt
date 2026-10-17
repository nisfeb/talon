package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Armillary's proxy waits up to ten minutes for an upstream (armillary
 * 33), a model on the owner's own hardware among them, so every call
 * through Armillary waits as long, and a call elsewhere keeps its own.
 */
class ArmillaryWaitTest {
    private val waits = CopyOnWriteArrayList<Long?>()
    private val http = HttpClient(MockEngine { req ->
        waits += req.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
        val body = if (req.url.encodedPath.endsWith("/audio/transcriptions")) """{"text":"hi"}"""
        else """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Test
    fun `chat, the assistant and transcription through Armillary wait eleven minutes`() = runBlocking {
        val cfg = paidSettings(speech = true)
        assertEquals(ARMILLARY_WAIT_MS, cfg.forFeature(AiFeature.CatchUp).upstreamTimeoutMs)
        AiClient(http = http) { cfg.forFeature(AiFeature.CatchUp) }.complete("system", "user")
        AgentClient(http = http) { cfg.forFeature(AiFeature.Assistant) }.completeWithTools("system", listOf(AgentMessage.User("hi")), emptyList())
        ArmillaryTranscribe.transcribe(http, cfg.profile().armillaryHearing()!!, ByteArray(44), "wav")
        assertEquals(listOf<Long?>(ARMILLARY_WAIT_MS, ARMILLARY_WAIT_MS, ARMILLARY_WAIT_MS), waits.toList())
    }

    @Test
    fun `a call that is not through Armillary keeps its own wait`() = runBlocking {
        val own = AiSettings.Config(provider = AiSettings.Provider.Custom, apiKey = "k", model = "m", baseUrl = "http://127.0.0.1:1234/v1")
        assertEquals(0L, own.upstreamTimeoutMs)
        AiClient(http = http) { own }.complete("system", "user", timeoutMs = 45_000)
        AgentClient(http = http) { own }.completeWithTools("system", listOf(AgentMessage.User("hi")), emptyList())
        assertEquals(listOf<Long?>(45_000, 120_000), waits.toList())
    }
}
