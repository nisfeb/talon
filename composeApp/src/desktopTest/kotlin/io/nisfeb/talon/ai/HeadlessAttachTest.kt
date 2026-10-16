package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.mail.MailRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a headless run (a dictation, a scheduled loop) asks the ship for
 * before the agent starts. A dictation on the phone waited 13 to 30
 * seconds here: the calendar's poller read everything, the run read it
 * all again behind the same lock, and mail polled alongside.
 */
class HeadlessAttachTest {
    private val asked = CopyOnWriteArrayList<String>()
    private val http = HttpClient(MockEngine { req ->
        asked += req.url.encodedPath
        val body = when {
            req.url.encodedPath.endsWith("/window.json") -> """{"rows":[]}"""
            req.url.encodedPath.endsWith(".json") -> "[]"
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Test
    fun `mail attached without polling asks the ship nothing until a tool does`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            MailRepo(http, scope).attach("https://ship.test", poll = false)
            delay(300) // real time: a launched poll would have asked by now
            assertEquals(emptyList(), asked.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a headless calendar is read once, not twice`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val cal = CalendarRepo(http, scope)
            cal.attach("https://ship.test", poll = false)
            cal.refresh()
            delay(300)
            assertEquals(1, asked.count { it.endsWith("/window.json") }, asked.toString())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the app's calendar still polls`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            CalendarRepo(http, scope).attach("https://ship.test")
            var waited = 0
            while (asked.none { it.endsWith("/window.json") } && waited < 5_000) { delay(20); waited += 20 }
            assertTrue(asked.any { it.endsWith("/window.json") }, "attach with no poll argument stopped polling")
        } finally {
            scope.cancel()
        }
    }
}
