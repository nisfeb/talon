package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryAccountEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Orrery after a ship that did not answer: a first check that failed is
 * tried again until the beacon runs, turning it off with a reader whose
 * state could not be read still turns that reader off or says it did not,
 * and a refinement asked for from a dialog then closed still lands.
 */
class OrreryRecoveryTest {
    private val page = "<html><body><h1>502 Bad Gateway</h1></body></html>"

    /** Checks of the ship that answer nginx's page before orrery answers. */
    private val downFor = AtomicInteger(0)
    /** Everything answers nginx's page: the ship is down. */
    @Volatile private var down = false
    /** Settings documents that cannot be read, and ones the ship does not have. */
    @Volatile private var unreadable = setOf<String>()
    @Volatile private var absent = setOf<String>()
    @Volatile private var refineHoldMs = 0L
    @Volatile private var actions = """[{"id":"a1","kind":"task","title":"Buy goggles","status":"proposed","by":"generator"}]"""
    private val asked = CopyOnWriteArrayList<String>()
    private val writes = CopyOnWriteArrayList<String>()

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        asked += path
        val json = { status: HttpStatusCode, body: String -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        val doc = path.substringAfter("/apps/orrery/api/", "")
        when {
            down -> respond(page, HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
            doc == "state" && downFor.getAndDecrement() > 0 -> respond(page, HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
            path.endsWith("/beacon/rev") -> respond("event: old /rev\ndata: 1\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            doc == "actions" -> json(HttpStatusCode.OK, actions)
            doc == "actions/a1/refine" -> {
                delay(refineHoldMs)
                json(HttpStatusCode.OK, """{"ok":true,"note":"","action":{"id":"a1","kind":"task","title":"Buy blue goggles","status":"proposed","by":"generator"},
                    "extras":[{"id":"a2","kind":"task","title":"Book the pool","status":"proposed","by":"generator"}]}""")
            }
            doc in absent -> json(HttpStatusCode.NotFound, """{"error":"no such document"}""")
            req.method == HttpMethod.Put -> { writes += doc + " " + req.body.toByteArray().decodeToString(); json(HttpStatusCode.OK, "{}") }
            doc in unreadable || doc == "settings" || doc.endsWith("/last") -> json(HttpStatusCode.InternalServerError, """{"error":"busy"}""")
            doc.startsWith("clients/") -> json(HttpStatusCode.OK, "{}")
            else -> json(HttpStatusCode.OK, "{}")
        }
    })

    /** A repo attached to the ship; [keyed] is a device that holds a key, whose pipe then runs its own passes. */
    private fun attached(probeRetryFirstMs: Long = 50, keyed: Boolean = false, block: suspend (OrreryRepo, AppDatabase) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-orrery-recovery-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http, probeRetryFirstMs = probeRetryFirstMs)
        try {
            if (keyed) db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "c1.secret"))
            repo.attach("https://ship.test", "~zod")
            block(repo, db)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun until(what: String, ok: () -> Boolean) =
        withTimeout(10_000) { while (!ok()) delay(20) }.also { assertTrue(ok(), what) }

    // A phone started before its wifi, or a 502 while the ship restarted:
    // the beacon and the actions started only when the first check said
    // present, and Settings said "Checking your ship for Orrery." all day.
    @Test
    fun `a first check that failed is tried again, and the beacon starts once orrery answers`() {
        downFor.set(2)
        attached { repo, _ ->
            until("the failed check is said, in words") { repo.error.value != null }
            assertEquals("Your ship did not answer. Talon asks again by itself.", repo.error.value)
            until("orrery answers in the end") { repo.availability.value == OrreryAvailability.PRESENT }
            until("the beacon runs") { asked.any { it.endsWith("/beacon/rev") } }
            until("what is waiting is read") { repo.actions.value.map { it.id } == listOf("a1") }
            assertEquals(null, repo.error.value, "the check's own failure goes with the answer")
            assertEquals(3, asked.count { it == "/apps/orrery/api/state" }, "one check, and two more after it failed")
        }
    }

    @Test
    fun `a ship that answers its first check is not asked again`() = attached { repo, _ ->
        until("present") { repo.availability.value == OrreryAvailability.PRESENT }
        delay(300)
        assertEquals(1, asked.count { it == "/apps/orrery/api/state" })
    }

    // Each reader was turned off only if its state could be read, and with
    // orrery's presence unknown none was: the page said Orrery was off.
    @Test
    fun `a reader whose state could not be read is turned off all the same`() = attached { repo, _ ->
        until("present") { repo.availability.value == OrreryAvailability.PRESENT }
        unreadable = setOf("chat", "mail", "generator")
        assertEquals(emptyList(), repo.switchOff())
        assertEquals(
            listOf("chat {\"enabled\":false,\"send_dms\":false}", "mail {\"enabled\":false}", "generator {\"enabled\":false}"),
            writes.toList(),
        )
    }

    @Test
    fun `a reader the ship does not have is nothing to stop`() = attached { repo, _ ->
        until("present") { repo.availability.value == OrreryAvailability.PRESENT }
        unreadable = setOf("chat", "generator")
        absent = setOf("mail")
        assertEquals(emptyList(), repo.switchOff())
        assertEquals(listOf("chat", "generator"), writes.map { it.substringBefore(' ') })
    }

    @Test
    fun `with the ship down from the start, turning off says what it could not stop`() {
        down = true
        attached(probeRetryFirstMs = 60_000, keyed = true) { repo, db ->
            until("the check failed") { repo.error.value != null }
            assertEquals(OrreryAvailability.UNKNOWN, repo.availability.value)
            var off = false
            val before = asked.size
            assertEquals(
                listOf("reading your chats", "reading your mail", "writing your brief", "taking back this device's key"),
                repo.switchOff { off = true },
            )
            // The chat reader's state, its write, and the key: after the first
            // write found nobody there, the mail reader and the brief were not
            // asked. It made eleven requests, each a timeout on a real ship.
            assertEquals(5, asked.size - before, asked.drop(before).toString())
            assertTrue(off, "off here all the same")
            assertTrue(db.orreryAccounts().get("~zod") != null, "the key is kept, to give back at the next start")
        }
    }

    @Test
    fun `a refinement asked for from a dialog then closed still lands, and what it filed beside it raises nothing`() = attached { repo, _ ->
        until("present") { repo.availability.value == OrreryAvailability.PRESENT }
        until("the open list read") { repo.actions.value.isNotEmpty() }
        val raised = CopyOnWriteArrayList<String>()
        repo.onActions = { raise, _ -> raised += raise.map { it.id } }
        refineHoldMs = 400
        val dialog = CoroutineScope(SupervisorJob())
        dialog.launch { repo.refine("a1", "blue ones") }
        delay(100)
        dialog.coroutineContext.job.cancelAndJoin()
        until("the revision lands though the dialog went") { repo.actions.value.any { it.title == "Buy blue goggles" } }
        assertEquals(listOf("a1", "a2"), repo.actions.value.map { it.id })
        // The ship lists them both now: the one filed beside it was seen in the dialog.
        actions = """[{"id":"a1","kind":"task","title":"Buy blue goggles","status":"proposed","by":"generator"},
            {"id":"a2","kind":"task","title":"Book the pool","status":"proposed","by":"generator"}]"""
        repo.refreshActions()
        assertEquals(emptyList(), raised.toList())
    }
}
