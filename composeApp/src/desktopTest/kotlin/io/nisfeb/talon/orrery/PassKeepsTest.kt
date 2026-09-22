package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryAccountEntity
import io.nisfeb.talon.data.OrrerySentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a pass does with what it did not get to.
 *
 * A pass reads a bounded amount: ten mail threads, twenty model runs.
 * The cursors used to move past everything the listing held anyway, so
 * the eleventh thread of a busy morning was never read by anything. And
 * a message it sent was recorded afterwards, so a pass the phone stopped
 * in between sent it a second time.
 */
class PassKeepsTest {
    private val state = """{"me":"person/me","rev":1,"bodies":[
        {"id":"person/rose","name":"Rose","attrs":{"ship":{"value":"~sampel-palnet"}}}],
        "schema":{"kinds":{},"actions":[]}}"""

    private fun db(dir: File) = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()

    /** A thread listed as newer than the cursor, newest first. */
    private fun thread(n: Int) = """{"id":"t$n","subject":"Thread $n","last":${9_000 + n},"count":1}"""

    @Test
    fun `the mail cursor stays behind the threads a pass did not read`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-keeps-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val asked = mutableListOf<String>()
        // Twelve waiting, newest first; a pass reads ten.
        val waiting = (12 downTo 1).map(::thread)
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 5_000L))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    asked += url
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/orrery/api/actions" in url -> "[]"
                        "/apps/auspex/api/thread/" in url -> {
                            val id = url.substringAfterLast('/')
                            """{"id":"$id","subject":"Thread","messages":[
                                {"id":"m-$id","from":"~sampel-palnet","to":["~zod"],"sent":9000,"body":"a note"}]}"""
                        }
                        "/apps/auspex/api/inbox" in url ->
                            if ("offset=0" in url || "offset" !in url) {
                                """{"total":12,"offset":0,"limit":20,"view":"all","threads":[${waiting.joinToString(",")}]}"""
                            } else {
                                """{"total":12,"offset":20,"limit":20,"view":"all","threads":[]}"""
                            }
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = { OrreryRepo(http, scope, db, "test", bareClient = http) }
            repo().pass("https://ship.test", "~zod")

            val read = asked.count { "/apps/auspex/api/thread/" in it }
            assertEquals(OrreryRepo.MAIL_THREADS_PER_PASS, read, "a pass reads ten threads")
            val cursor = db.orreryAccounts().get("~zod")!!.mailCursor
            assertTrue(
                cursor < 9_003L,
                "the cursor stays behind the oldest thread the pass left (t2 at 9002), and is $cursor",
            )

            // The next pass reads the two it left, and not the ten it read.
            asked.clear()
            repo().pass("https://ship.test", "~zod")
            val again = asked.filter { "/apps/auspex/api/thread/" in it }.map { it.substringAfterLast('/') }
            assertEquals(listOf("t2", "t1"), again, "only what was left, and each thread once")
            assertEquals(9_012L, db.orreryAccounts().get("~zod")!!.mailCursor, "and now the cursor is past them all")
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an approved message is written down before it is sent`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-send-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val action = """{"id":"a1","kind":"message","title":"Tell Rose","status":"approved","by":"generator",
            "about":["person/rose"],"payload":{"via":"chat","to":"person/rose","text":"on my way"}}"""
        var recordedWhenSent: String? = null
        var sends = 0
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        // The claim: this install's, as the ship's own history says.
                        req.method == HttpMethod.Post && "/apps/orrery/api/actions/a1" in url -> {
                            val said = (req.body as? TextContent)?.text.orEmpty()
                            if ("claimed" in said) """{"id":"a1","status":"claimed","by":"mine"}""" else "{}"
                        }
                        "status=claimed" in url -> """[{"id":"a1","status":"claimed","history":[{"status":"claimed","by":"mine"}]}]"""
                        "/apps/orrery/api/actions" in url -> "[$action]"
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            // The DM is the send, and what was written down when it went.
            val dm: suspend (String, String) -> Unit = { _, _ ->
                sends++
                recordedWhenSent = db.orrerySent().get("~zod", "sent:a1")?.value
            }
            OrreryRepo(http, scope, db, "test", bareClient = http, sendDm = dm).pass("https://ship.test", "~zod")
            assertEquals(1, sends, "sent once")
            assertEquals(
                OrreryRepo.UNCONFIRMED,
                recordedWhenSent,
                "the record was already there when the message went out, so a pass that dies here sends nothing twice",
            )
            assertNotNull(db.orrerySent().get("~zod", "sent:a1"), "and it stands afterwards")

            // The same listing again: it is reported, not sent again.
            OrreryRepo(http, scope, db, "test", bareClient = http, sendDm = dm).pass("https://ship.test", "~zod")
            assertEquals(1, sends, "and never twice")
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a send that fails leaves nothing behind, so it can be approved again`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-fail-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val action = """{"id":"a1","kind":"message","title":"Tell Rose","status":"approved","by":"generator",
            "about":["person/rose"],"payload":{"via":"chat","to":"person/rose","text":"on my way"}}"""
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    run {
                        val body = when {
                            "/apps/orrery/api/state" in url -> state
                            req.method == HttpMethod.Post && "/apps/orrery/api/actions/a1" in url -> {
                                val said = (req.body as? TextContent)?.text.orEmpty()
                                if ("claimed" in said) """{"id":"a1","status":"claimed","by":"mine"}""" else "{}"
                            }
                            "status=claimed" in url -> """[{"id":"a1","status":"claimed","history":[{"status":"claimed","by":"mine"}]}]"""
                            "/apps/orrery/api/actions" in url -> "[$action]"
                            "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                            "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                            else -> "[]"
                        }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            )
            OrreryRepo(
                http, scope, db, "test", bareClient = http,
                sendDm = { _, _ -> error("the ship would not take the DM") },
            ).pass("https://ship.test", "~zod")
            assertNull(db.orrerySent().get("~zod", "sent:a1"), "nothing stands for a message that did not go")
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the executor claims chat and nothing else`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-one-channel-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        // Everything the ship carries out itself as of orrery 34, all
        // approved and all waiting: a task, a calendar action, and
        // messages by telegram and by mail. Talon touches none of them.
        val theirs = listOf(
            """{"id":"t1","kind":"task","title":"Book the ferry","status":"approved","by":"generator","about":[],"payload":{}}""",
            """{"id":"c1","kind":"calendar","title":"Dinner","status":"approved","by":"reader","about":[],
                "payload":{"title":"Dinner","starts":"2026-10-02T00:00:00Z"}}""",
            """{"id":"m1","kind":"message","title":"Tell Rose","status":"approved","by":"generator","about":["person/rose"],
                "payload":{"via":"telegram","to":"person/rose","text":"on my way"}}""",
            """{"id":"m2","kind":"message","title":"Tell Rose","status":"approved","by":"generator","about":["person/rose"],
                "payload":{"via":"mail","to":"person/rose","text":"on my way"}}""",
        )
        val posted = mutableListOf<String>()
        var dms = 0
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if (req.method == HttpMethod.Post) posted += url
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/orrery/api/actions" in url -> "[" + theirs.joinToString(",") + "]"
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            OrreryRepo(
                http, scope, db, "test", bareClient = http,
                sendDm = { _, _ -> dms++ },
            ).pass("https://ship.test", "~zod")

            assertEquals(0, dms, "nothing was sent: not one of these is a chat message")
            assertTrue(
                posted.none { "/apps/orrery/api/actions/" in it },
                "no action was claimed or moved:\n" + posted.joinToString("\n"),
            )
            assertTrue(
                posted.none { "/apps/calendar" in it },
                "and nothing was written to the calendar:\n" + posted.joinToString("\n"),
            )
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    // No state view, no reading, so nothing was read: the cursors stay
    // where they were. They used to jump past the whole pass, and those
    // threads were never read by anything.
    @Test
    fun `a pass that cannot read the state reads nothing and moves no cursor`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-no-state-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        var stateUp = false
        val waiting = (12 downTo 1).map(::thread)
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 5_000L))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    asked += url
                    if ("/apps/orrery/api/state" in url && !stateUp) {
                        return@MockEngine respond("busy", io.ktor.http.HttpStatusCode.InternalServerError)
                    }
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/orrery/api/actions" in url -> "[]"
                        "/apps/auspex/api/thread/" in url -> {
                            val id = url.substringAfterLast('/')
                            """{"id":"$id","subject":"Thread","messages":[
                                {"id":"m-$id","from":"~sampel-palnet","to":["~zod"],"sent":9000,"body":"a note"}]}"""
                        }
                        "/apps/auspex/api/inbox" in url ->
                            if ("offset=0" in url || "offset" !in url) {
                                """{"total":12,"offset":0,"limit":20,"view":"all","threads":[${waiting.joinToString(",")}]}"""
                            } else {
                                """{"total":12,"offset":20,"limit":20,"view":"all","threads":[]}"""
                            }
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = { OrreryRepo(http, scope, db, "test", bareClient = http) }
            repo().pass("https://ship.test", "~zod")
            val after = db.orreryAccounts().get("~zod")!!
            assertTrue(after.calendarCursor > 0L, "the pass ran to the end and wrote its cursors")
            assertEquals(5_000L, after.mailCursor, "and did not move past mail it never read")
            assertEquals(0, asked.count { "/apps/auspex/api/thread/" in it })

            stateUp = true
            repo().pass("https://ship.test", "~zod")
            assertEquals(OrreryRepo.MAIL_THREADS_PER_PASS, asked.count { "/apps/auspex/api/thread/" in it }, "read on the next pass")
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    // The revoke is the step that can fail, so it goes first. After it,
    // an unreachable ship left the switch on, the loop dead and every
    // record gone.
    @Test
    fun `turning the pipe off on an unreachable ship takes nothing apart`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-off-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "cal:default/e1", "situation/dinner|d", 1L))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if (req.method == HttpMethod.Delete && "/apps/orrery/api/clients/c1" in url) {
                        return@MockEngine respond("unreachable", io.ktor.http.HttpStatusCode.BadGateway)
                    }
                    respond(if ("/api/state" in url) state else "[]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = OrreryRepo(http, scope, db, "test", bareClient = http)
            repo.attach("https://ship.test", "~zod")
            val until = System.currentTimeMillis() + 5_000
            while (!repo.enabled.value && System.currentTimeMillis() < until) kotlinx.coroutines.delay(20)
            assertTrue(repo.enabled.value, "on to begin with")

            assertTrue(repo.disable().isFailure, "the revoke failed, and says so")
            assertTrue(repo.enabled.value, "so the pipe is still on")
            assertNotNull(db.orreryAccounts().get("~zod"), "its key still here")
            assertNotNull(db.orrerySent().get("~zod", "cal:default/e1"), "and what it had written, remembered")
            repo.detach()
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }
}
