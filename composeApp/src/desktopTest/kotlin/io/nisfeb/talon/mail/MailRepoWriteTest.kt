package io.nisfeb.talon.mail

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A write shows at once and is only confirmed by the re-read that
 * follows it, so what matters is what the repo does when the ship says
 * no. Three cases, and they are different on purpose: a 404 on a write
 * is the thing being written to having vanished (re-read, do not roll
 * back, and above all do not cry wolf about the app being gone); any
 * other refusal puts back exactly what the optimistic edit touched and
 * nothing a refresh landed meanwhile; and a 404 is only ever read as
 * "no mail app" on the inbox path that probes for one.
 */
class MailRepoWriteTest {

    private val whoami = """{"ship":"~zod"}"""

    private fun row(id: String, subject: String) =
        """{"id":"$id","subject":"$subject","from":"~zod","snippet":"",""" +
            """"verdict":"verified","forged":false,"count":1,"last":1,"unread":false,""" +
            """"participants":["~zod"],"unreadable":0,"archived":false,"labels":[]}"""

    private fun pageWith(vararg rows: String) =
        """{"total":${rows.size},"offset":0,"limit":50,"view":"inbox","threads":[${rows.joinToString(",")}]}"""

    private fun threadJson(id: String) =
        """{"id":"$id","messages":[{"id":"${id}m1","from":"~zod","to":["~nec"],"subject":"s",""" +
            """"body":"b","sent":1,"prev":null,"verdict":"verified","read":false}],""" +
            """"participants":["~zod"],"last":1,"unreadable":0,"archived":false,"labels":[]}"""

    private fun jsonOk(scope: MockRequestHandleScope, body: String) =
        scope.respond(
            ByteReadChannel(body),
            HttpStatusCode.OK,
            headersOf("Content-Type", "application/json"),
        )

    private fun <T> withRepo(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        block: suspend (MailRepo, List<String>) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob())
        val hits = CopyOnWriteArrayList<String>()
        val http = HttpClient(
            MockEngine { req ->
                hits += req.method.value + " " + req.url.encodedPath
                handler(req)
            },
        )
        // A poll interval far past the test, so only explicit refreshes run.
        return try {
            runBlocking { block(MailRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L), hits) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        repeat(500) {
            if (cond()) return
            delay(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Wait for the poller's opening read to finish. */
    private suspend fun settle(r: MailRepo) = until("the opening read to finish") {
        !r.loading.value && r.page.value != null
    }

    @Test
    fun `a 404 on a write is a vanished thread, not a vanished app`() {
        val inbox = pageWith(row("0v1", "one"))
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/inbox") -> jsonOk(this, inbox)
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                // The thread another client deleted: the write's target is
                // gone, which says nothing about the nexus being installed.
                path.endsWith("/api/archive") ->
                    respondError(HttpStatusCode.NotFound, """{"error":"no such thread"}""")
                else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
            }
        }) { r, hits ->
            r.attach("https://ship.example")
            settle(r)
            assertEquals(MailAvailability.PRESENT, r.availability.value)
            assertEquals(listOf("0v1"), r.page.value!!.threads.map { it.id })

            r.setArchived("0v1", true)

            // The write is refused 404; the repo re-reads rather than
            // probing for an install, and the ship's unchanged listing
            // drops the optimistic edit by simply containing the row.
            until("the row to come back from the ship") {
                r.page.value?.threads?.map { it.id } == listOf("0v1")
            }
            assertEquals(
                MailAvailability.PRESENT,
                r.availability.value,
                "a deleted thread must not read as an uninstalled app",
            )
            assertNull(r.error.value, "a vanished write target is not a failure to report")
            assertEquals(0, r.rollbacks.value, "and not a refusal to roll back either")
            assertEquals(1, hits.count { it.endsWith("/api/archive") }, "the write was poked once")
            assertTrue(
                hits.count { it.endsWith("/api/inbox") } >= 2,
                "the answer to a 404 on a write is a re-read",
            )
            assertTrue(
                hits.none { it.contains("desks/stock") },
                "no install probe over a deleted thread",
            )
        }
    }

    @Test
    fun `a refused write rolls back its own thread and row, and keeps what a refresh landed`() {
        val inbox = AtomicReference(pageWith(row("0v1", "one"), row("0v2", "two")))
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/inbox") -> jsonOk(this, inbox.get())
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                path.endsWith("/api/thread/0v1") -> jsonOk(this, threadJson("0v1"))
                path.endsWith("/api/archive") -> {
                    // Hold the write long enough for a refresh to land
                    // between the optimistic edit and the refusal.
                    delay(300)
                    respondError(HttpStatusCode.InternalServerError, """{"error":"writer is busy"}""")
                }
                else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
            }
        }) { r, _ ->
            r.attach("https://ship.example")
            settle(r)
            r.loadThread("0v1")
            assertEquals(false, r.cachedThread("0v1")!!.archived)

            r.setArchived("0v1", true)
            // The optimistic half is synchronous: the row leaves the
            // inbox listing and the cached thread is marked, long before
            // the held write can answer.
            assertEquals(listOf("0v2"), r.page.value!!.threads.map { it.id })
            assertEquals(true, r.cachedThread("0v1")!!.archived)

            // A refresh lands while the write is still out, with newer
            // rows for everything.
            inbox.set(pageWith(row("0v1", "one-v2"), row("0v2", "two-v2")))
            r.refresh()
            assertEquals(listOf("one-v2", "two-v2"), r.page.value!!.threads.map { it.subject })

            // The counter moves just before the problem is said: wait for both.
            until("the refusal to roll back and be said") { r.rollbacks.value == 1 && r.problem.value != null }
            val rows = r.page.value!!.threads.associateBy { it.id }
            assertEquals("one", rows.getValue("0v1").subject, "the acted-on row goes back to the snapshot")
            assertEquals(
                "two-v2",
                rows.getValue("0v2").subject,
                "a refresh that landed while the write was out is newer than the snapshot, and stays",
            )
            assertEquals(false, r.cachedThread("0v1")!!.archived, "the thread is put back too")
            assertEquals("The ship did not do that: writer is busy", r.problem.value)
            assertEquals(MailAvailability.PRESENT, r.availability.value)
            // In the read's error it was gone again with the next read
            // that went well, often before anyone saw it.
            r.refresh()
            assertEquals("The ship did not do that: writer is busy", r.problem.value, "a read that goes well does not wipe it")
            r.clearProblem()
            assertNull(r.problem.value)
        }
    }

    @Test
    fun `read, unread and delete show at once, and leaving a ship leaves none of its mail`() {
        val unreadRow = row("0v1", "one").replace(""""unread":false""", """"unread":true""")
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/inbox") -> jsonOk(this, pageWith(unreadRow, row("0v2", "two")))
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                path.endsWith("/api/thread/0v1") -> jsonOk(this, threadJson("0v1"))
                // Held, so what shows is the local edit and not a re-read.
                else -> { delay(5_000); jsonOk(this, "{}") }
            }
        }) { r, _ ->
            r.attach("https://ship.example")
            settle(r)
            r.loadThread("0v1")
            r.markRead(listOf("0v1m1"), "0v1")
            assertEquals(false, r.page.value!!.threads.first { it.id == "0v1" }.unread)
            assertEquals(true, r.cachedThread("0v1")!!.messages.single().read)
            r.markUnread(listOf("0v1m1"), "0v1")
            assertEquals(true, r.page.value!!.threads.first { it.id == "0v1" }.unread)
            assertEquals(false, r.cachedThread("0v1")!!.messages.single().read)
            r.deleteThread("0v1")
            assertEquals(listOf("0v2"), r.page.value!!.threads.map { it.id })
            assertNull(r.cachedThread("0v1"))

            r.detach()
            assertNull(r.page.value, "a ship signed out of shows none of its mail")
            assertEquals(MailAvailability.UNKNOWN, r.availability.value)
        }
    }

    @Test
    fun `a 404 off the inbox path never flips availability, but the inbox probe still does`() {
        // Every route that is not the inbox read: a 404 is an ordinary
        // refusal with the ship's reason, never an install prompt.
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/inbox") -> jsonOk(this, pageWith())
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                path.endsWith("/api/rule") ->
                    respondError(HttpStatusCode.NotFound, """{"error":"rule table gone"}""")
                path.endsWith("/api/fetch-blob") ->
                    respondError(HttpStatusCode.NotFound, """{"error":"blob host gone"}""")
                else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
            }
        }) { r, hits ->
            r.attach("https://ship.example")
            settle(r)
            assertEquals(MailAvailability.PRESENT, r.availability.value)

            r.saveRule(Rule("r1", from = "~zod"))
            assertEquals("rule table gone", r.error.value, "the refusal carries the ship's reason")
            assertEquals(
                MailAvailability.PRESENT,
                r.availability.value,
                "a missing route on a write is not a missing app",
            )
            assertTrue(hits.none { it.endsWith("/api/rules") }, "a refused save is not re-read")

            r.fetchBlob("0vhash", "~nec")
            assertEquals("blob host gone", r.error.value)
            assertEquals(MailAvailability.PRESENT, r.availability.value)
            assertTrue(hits.none { it.contains("desks/stock") }, "no probe off the inbox path")
        }

        // The same status on the inbox read itself is the one place the
        // probe runs: there a 404 does mean the nexus is gone.
        withRepo({ req ->
            if (req.url.encodedPath.endsWith("desks/stock")) {
                respondError(HttpStatusCode.NotFound, "")
            } else {
                respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
            }
        }) { r, hits ->
            r.attach("https://ship.example")
            r.refresh()
            assertEquals(
                MailAvailability.NO_GRUBBERY,
                r.availability.value,
                "the inbox path is the one place a 404 means the app is gone",
            )
            assertNull(r.error.value, "not having mail installed is a state, not a failure")
            assertTrue(hits.any { it.contains("desks/stock") }, "and the probe did run")
        }
    }
}
