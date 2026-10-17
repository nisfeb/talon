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
import kotlinx.coroutines.launch
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
        /** How often the repo's own poll reads; far past the test unless a test is about the poll. */
        pollMs: Long = 60 * 60 * 1000L,
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
        return try {
            runBlocking { block(MailRepo(http, scope, pollIntervalMs = pollMs), hits) }
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

    private fun unread(id: String, subject: String) = row(id, subject).replace("\"unread\":false", "\"unread\":true")

    // ─── the poll, wherever the reader is ───────────────────────────

    // The poll read only the listing on screen, and new mail was announced
    // from the inbox alone: after one search or folder switch no notice
    // came, and the Mail section's pip stood still.
    @Test
    fun `new mail is announced and lights the pip while another folder is open`() {
        val inboxRows = AtomicReference(listOf(row("0v1", "one")))
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/inbox") && req.url.parameters["view"] == "inbox" -> jsonOk(this, pageWith(*inboxRows.get().toTypedArray()))
                path.endsWith("/api/inbox") -> jsonOk(this, pageWith())
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
            }
        }, pollMs = 200) { r, _ ->
            val news = CopyOnWriteArrayList<MailNotification>()
            r.onNewMail = { news += it }
            r.attach("https://ship.example")
            settle(r)
            r.selectFolder(MailFolder.View(MailView.SENT))
            until("the sent listing") { r.page.value?.threads?.isEmpty() == true }
            inboxRows.set(listOf(row("0v1", "one"), unread("0v9", "news")))
            until("the new mail's notice") { news.isNotEmpty() }
            assertTrue(r.inboxUnread.value, "the pip")
            assertEquals(MailFolder.View(MailView.SENT), r.folder.value, "and the reader stays where they were")
            assertTrue(r.page.value!!.threads.isEmpty(), "the listing on screen is still Sent's")
        }
    }

    // A 403 stopped the poll, and signing in again to the same ship never
    // started it: mail was read only when somebody opened it.
    @Test
    fun `the poll a dead session stopped starts again with the next read that goes through`() {
        val signedOut = java.util.concurrent.atomic.AtomicBoolean(true)
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                signedOut.get() -> respondError(HttpStatusCode.Forbidden, """{"error":"forbidden"}""")
                path.endsWith("/api/inbox") -> jsonOk(this, pageWith(row("0v1", "one")))
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
            }
        }, pollMs = 200) { r, hits ->
            r.attach("https://ship.example")
            until("signed out") { r.availability.value == MailAvailability.SIGNED_OUT }
            signedOut.set(false)
            fun reads() = hits.count { it.endsWith("/api/inbox") }
            val stopped = reads()
            delay(800)
            assertEquals(stopped, reads(), "stopped while signed out")
            r.refresh()
            assertEquals(MailAvailability.PRESENT, r.availability.value)
            val back = reads()
            until("the poll's own read") { reads() > back }
        }
    }

    // ─── what is kept when the ship does not take it ────────────────

    // "What was written is still here until Talon closes", said of a draft
    // that was in neither Drafts nor anywhere the list could open it.
    @Test
    fun `a draft the ship did not take is kept whole, to open again`() = withRepo({ req ->
        val path = req.url.encodedPath
        when {
            path.endsWith("/api/draft") -> respond(NGINX_502, HttpStatusCode.BadGateway, headersOf("Content-Type", "text/html"))
            path.endsWith("/api/inbox") -> jsonOk(this, pageWith())
            path.endsWith("/api/whoami") -> jsonOk(this, whoami)
            else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
        }
    }) { r, _ ->
        r.attach("https://ship.example")
        settle(r)
        val draft = Draft(id = "0vd", to = listOf("~nec"), subject = "Plans", body = "half a thought")
        val file = MailRepo.Outgoing("x".encodeToByteArray(), "notes.txt", "text/plain")
        r.keepDraft(draft, listOf(file))
        until("the draft kept here") { r.unsent.value.isNotEmpty() }
        val kept = r.unsent.value.single()
        assertEquals("\"Plans\" was not saved: the draft did not reach the ship. It is kept here until Talon closes.", kept.line)
        // What the list's Open gives back: the text, and the file.
        val reopened = io.nisfeb.talon.ui.screens.unsentIntent(kept)
        assertEquals("half a thought" to listOf("notes.txt"), reopened.body to reopened.edits.files.map { it.displayName })
        r.keepDraft(draft.copy(body = "a whole thought"))
        until("the newer one in its place") { r.unsent.value.singleOrNull()?.draft?.body == "a whole thought" }
    }

    // ─── an attachment, with our own ship down ──────────────────────

    // A ship that did not answer was read as "no copy here", so the network
    // was asked and waited on, and the sender was blamed.
    @Test
    fun `an attachment asked of our own ship while it is down throws, and a refusal does not`() = withRepo({ req ->
        val path = req.url.encodedPath
        when {
            "/api/blob/" in path || path.endsWith("/api/fetch-blob") -> respond(NGINX_502, HttpStatusCode.BadGateway, headersOf("Content-Type", "text/html"))
            path.endsWith("/api/inbox") -> jsonOk(this, pageWith())
            path.endsWith("/api/whoami") -> jsonOk(this, whoami)
            else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
        }
    }) { r, _ ->
        r.attach("https://ship.example")
        settle(r)
        assertTrue(runCatching { r.blob("0vh", "a.pdf", "application/pdf") }.exceptionOrNull() is AuspexError.Unreachable)
        assertTrue(runCatching { r.fetchBlob("0vh", "~nec") }.exceptionOrNull() is AuspexError.Unreachable)
    }

    // ─── filters and lists, past the sheet ──────────────────────────

    // Saved from the organise sheet, on the sheet's scope: closing it while
    // a busy ship held the write cancelled it.
    @Test
    fun `a filter or a list saved from a sheet that closes still lands`() {
        val landed = CopyOnWriteArrayList<String>()
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                req.method.value == "POST" -> { delay(600); landed += path.substringAfter("/api/"); jsonOk(this, "{}") }
                path.endsWith("/api/inbox") -> jsonOk(this, pageWith())
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                else -> jsonOk(this, "[]")
            }
        }) { r, hits ->
            r.attach("https://ship.example")
            settle(r)
            val writes: List<Pair<String, suspend () -> Boolean>> = listOf(
                "rule" to { r.saveRule(Rule("r1", from = "~zod")) },
                "rule-delete" to { r.deleteRule("r1") },
                "list" to { r.saveList(MailingList("team", listOf("~nec"))) },
                "list-delete" to { r.deleteList("team") },
            )
            for ((route, write) in writes) {
                val sheet = CoroutineScope(SupervisorJob())
                sheet.launch { write() }
                delay(150)
                sheet.cancel()
                until("$route to land") { route in landed }
            }
            until("the lists read again behind them") { hits.any { it.endsWith("/api/rules") } && hits.any { it.endsWith("/api/lists") } }
        }
    }

    // ─── filing to Lattice ──────────────────────────────────────────

    @Test
    fun `a filing that fails is said in words, never the error's own text`() {
        val down = java.util.concurrent.atomic.AtomicBoolean(true)
        withRepo({ req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/inbox") -> jsonOk(this, pageWith())
                path.endsWith("/api/whoami") -> jsonOk(this, whoami)
                req.method.value == "POST" && down.get() -> throw java.io.IOException("Connection refused")
                req.method.value == "POST" -> respondError(HttpStatusCode.InternalServerError, "<html>crash</html>")
                else -> respondError(HttpStatusCode.NotFound, "")
            }
        }) { r, _ ->
            r.attach("https://ship.example")
            settle(r)
            until("our ship known") { r.ourShip != null }
            assertNull(r.publishToLattice("Plans", "0vt", "# Plans"))
            assertEquals("Could not file to Lattice: your ship did not answer. Try again when it's back.", r.problem.value)
            down.set(false)
            assertNull(r.publishToLattice("Plans", "0vt", "# Plans"))
            assertEquals("Could not file to Lattice: your ship did not keep it. Try again shortly.", r.problem.value)
        }
    }
}
