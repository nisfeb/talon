package io.nisfeb.talon.mail

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the mailbox does when the ship answers, and what it concludes
 * when it does not. The distinctions here are the ones a user sees as
 * different sentences: no mail app, an out-of-date one, a dead session,
 * and a mailbox that is simply empty.
 */
class MailRepoTest {

    private val emptyPage =
        """{"total":0,"offset":0,"limit":50,"view":"inbox","threads":[]}"""

    private fun repo(
        scope: CoroutineScope,
        respond: (String) -> Pair<Int, String>,
    ): MailRepo {
        val http = HttpClient(
            MockEngine { req ->
                val (code, body) = respond(req.url.encodedPath)
                if (code == 200) {
                    respond(
                        ByteReadChannel(body),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                } else {
                    respondError(HttpStatusCode.fromValue(code), body)
                }
            },
        )
        // A poll interval far past the test, so only explicit refreshes run.
        return MailRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L)
    }

    private fun <T> withRepo(
        respond: (String) -> Pair<Int, String>,
        block: suspend (MailRepo) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob())
        return try {
            runBlocking { block(repo(scope, respond)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a good read fills the page and clears any error`() = withRepo({ 200 to emptyPage }) { r ->
        r.attach("https://ship.example")
        r.refresh()
        assertEquals(MailAvailability.PRESENT, r.availability.value)
        assertEquals(0, r.page.value?.total)
        assertNull(r.error.value)
        // attach() starts the poller, whose first read can still be in
        // flight behind the lock; the flag is only settled once it is.
        settle(r)
        assertEquals(false, r.loading.value)
    }

    /** Wait for any in-flight read to finish. */
    private suspend fun settle(r: MailRepo) {
        repeat(100) {
            if (!r.loading.value) return
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun `a ship with no grubbery is told apart from one with an old grubbery`() {
        // The nexus is absent, and so is lattice's manifest: no grubbery.
        withRepo({ path ->
            if (path.endsWith("manifest.webmanifest")) 404 to "" else 404 to """{"error":"not found"}"""
        }) { r ->
            r.attach("https://ship.example")
            r.refresh()
            assertEquals(MailAvailability.NO_GRUBBERY, r.availability.value)
        }
        // The nexus is absent but lattice answers: grubbery is here and stale.
        withRepo({ path ->
            if (path.endsWith("manifest.webmanifest")) 200 to "{}" else 404 to """{"error":"not found"}"""
        }) { r ->
            r.attach("https://ship.example")
            r.refresh()
            assertEquals(MailAvailability.OLD_GRUBBERY, r.availability.value)
        }
    }

    @Test
    fun `a missing app is not reported as an error to fix`() = withRepo({ path ->
        if (path.endsWith("manifest.webmanifest")) 404 to "" else 404 to """{"error":"not found"}"""
    }) { r ->
        r.attach("https://ship.example")
        r.refresh()
        assertNull(r.error.value, "not having mail installed is a state, not a failure")
    }

    @Test
    fun `a dead session says so and is never read as a missing app`() =
        withRepo({ 403 to """{"error":"forbidden"}""" }) { r ->
            r.attach("https://ship.example")
            r.refresh()
            assertEquals(MailAvailability.SIGNED_OUT, r.availability.value)
            assertTrue(r.error.value!!.contains("Signed out"))
        }

    @Test
    fun `a refusal is reported in the ship's own words`() =
        withRepo({ 400 to """{"error":"limit is not a number"}""" }) { r ->
            r.attach("https://ship.example")
            r.refresh()
            assertEquals("limit is not a number", r.error.value)
            assertEquals(MailAvailability.UNKNOWN, r.availability.value)
        }

    @Test
    fun `an unreadable answer is not a missing app either`() = withRepo({ 200 to "not json" }) { r ->
        r.attach("https://ship.example")
        r.refresh()
        assertTrue(r.error.value!!.contains("could not read"))
        assertEquals(MailAvailability.UNKNOWN, r.availability.value)
    }

    @Test
    fun `one selection drives the view and the label together`() =
        withRepo({ 200 to emptyPage }) { r ->
            r.attach("https://ship.example")
            r.refresh()
            assertEquals(MailFolder.View(MailView.INBOX), r.folder.value)

            r.selectFolder(MailFolder.View(MailView.ARCHIVED))
            assertEquals(MailView.ARCHIVED, r.view.value)
            assertNull(r.label.value)

            // A label is a folder, and picking one moves both halves of
            // the query rather than leaving them to be kept in step.
            r.selectFolder(MailFolder.Label("work"))
            assertEquals(MailView.LABEL, r.view.value)
            assertEquals("work", r.label.value)

            r.selectFolder(MailFolder.View(MailView.INBOX))
            assertNull(r.label.value, "leaving a label clears the filter with it")
        }

    @Test
    fun `a listing asks for the label the folder names`() {
        val asked = mutableListOf<String>()
        withRepo({ path -> asked += path; 200 to emptyPage }) { r ->
            r.attach("https://ship.example")
            r.selectFolder(MailFolder.Label("work"))
            r.refresh()
        }
        assertTrue(asked.isNotEmpty())
    }

    @Test
    fun `detaching forgets the mailbox`() = withRepo({ 200 to emptyPage }) { r ->
        r.attach("https://ship.example")
        r.refresh()
        r.detach()
        assertNull(r.page.value)
        assertEquals(MailAvailability.UNKNOWN, r.availability.value)
        r.refresh()
        assertNull(r.page.value, "a detached mailbox does not talk to a ship")
    }
}
