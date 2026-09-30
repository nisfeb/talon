package io.nisfeb.talon.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.armillary.ArmillaryRepo
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stopped is not failed. A screen left while its read was out, a pass
 * turned off mid-way: each used to be caught as the read failing, and
 * left an error on screen or a wrong answer in a long-lived repo.
 */
class CancelledIsNotFailedTest {
    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun `the helper passes a cancellation on and catches the rest`() = runBlocking<Unit> {
        assertTrue(runSuspendCatching { error("no") }.isFailure)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runSuspendCatching { throw kotlinx.coroutines.CancellationException("stop") }
        }
    }

    @Test
    fun `leaving the task list mid-read leaves no error on the calendar`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/events.json") -> awaitCancellation()
                req.url.encodedPath.endsWith("/window.json") -> respond("""{"rows":[]}""", HttpStatusCode.OK, json)
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        })
        val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply { attach("https://ship.test") }
        try {
            val read = launch { repo.refreshTasks() }
            delay(200)
            read.cancel()
            read.join()
            assertNull(repo.error.value, "stopped, not failed")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a catalog the ship could not send keeps the ZDR marks`() = runBlocking {
        val ai = FakeAiSettings().apply {
            applyRemote(
                AiSettings.Config(
                    provider = AiSettings.Provider.Anthropic, apiKey = "", model = null,
                    savedProfile = AiProfile(
                        listOf(
                            AiProvider(
                                ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary",
                                models = listOf(ModelInfo("stub/alpha", zdr = true)),
                            ),
                        ),
                    ),
                ),
            )
        }
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/catalog") -> respond("down", HttpStatusCode.BadGateway)
                path.endsWith("/api/inference") ->
                    respond("""{"mode":"proxy","base_url":"https://wex.example/v1","key":"k.s","models":["stub/alpha"]}""", HttpStatusCode.OK, json)
                path.endsWith("/api/plans") -> respond("[]", HttpStatusCode.OK, json)
                else -> respond(
                    """{"ship":"~feb","balance":1,"plan":"","subscription":{"active":false},"lease":{},"checkouts":{},"vendor":"~wex","self":"~feb","stale":3}""",
                    HttpStatusCode.OK, json,
                )
            }
        })
        val scope = CoroutineScope(SupervisorJob())
        try {
            ArmillaryRepo(http, scope, ai).apply { attach("https://ship.example", "~feb") }.refresh()
            val row = ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!
            assertEquals("k.s", row.apiKey, "the rest of the answer is taken")
            assertEquals(listOf(true), row.models.map { it.zdr }, "a catalog not sent is not 'nothing is ZDR'")
        } finally {
            scope.cancel()
        }
    }
}
