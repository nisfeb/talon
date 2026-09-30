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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a pass does with what it did not get to: what a model or the
 * ship could not answer waits for the next pass, and what the ship now
 * does itself, Talon leaves alone.
 */
class PassKeepsTest {
    private val state = """{"me":"person/me","rev":1,"bodies":[
        {"id":"person/rose","name":"Rose","attrs":{"ship":{"value":"~sampel-palnet"}}}],
        "schema":{"kinds":{},"actions":[]}}"""

    private fun db(dir: File) = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()

    // A claim confirmed in the tray waited in memory for the next pass:
    // a process killed first lost it, after it had left the tray. A
    // failed pass must keep it, whatever the failure, unless the ship
    // could not read the batch at all: that batch is passed over, as a
    // refused item is, and the pass finishes, or it would be refused
    // again first in every pass after and nothing behind it would go.
    @Test
    fun `a confirmed claim waits in the table until the ship takes it, and a refusal ends it`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-confirm-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        var answer = 503
        var observed = 0
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.orreryNoticed().insertIfNew(
                io.nisfeb.talon.data.OrreryNoticedEntity(
                    id = "n1", ship = "~zod", subject = "person/rose", attr = "location", valueJson = "\"the shop\"", atMs = 1,
                    untilMs = null, conf = 70, sourceKind = "talon-dm", sourceId = "talon://chat/~sampel-palnet?id=1", bodyJson = null,
                    whom = "~sampel-palnet", postId = "1", snippet = "at the shop", state = OrreryRepo.CONFIRMING, createdMs = 1,
                ),
            )
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if ("/api/observe" in url) {
                        observed++
                        // As orrery answers one fact it will not take: in
                        // its 200, not as a 400 for the batch.
                        if (answer == 200) {
                            return@MockEngine respond("""{"bodies":[],"observations":[{"ok":false,"error":"attr: unknown"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        return@MockEngine respond("no", io.ktor.http.HttpStatusCode.fromValue(answer))
                    }
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            // When the pass finished, or null where it did not.
            suspend fun pass() = OrreryRepo(http, scope, db, "test", bareClient = http).also { it.pass("https://ship.test", "~zod") }.lastPushMs.value
            suspend fun waiting() = db.orreryNoticed().confirming("~zod").map { it.id }
            pass()
            assertEquals(1, observed)
            assertEquals(listOf("n1"), waiting(), "a busy ship's 503 leaves it waiting")
            answer = 404
            assertNull(pass(), "no failed pass finishes")
            assertEquals(listOf("n1"), waiting(), "and so does a 404 from a desk mid-update: it is not the claim's fault")
            answer = 200
            assertNotNull(pass(), "and the pass finished, so nothing is stuck behind it")
            assertEquals(3, observed, "sent again on each pass")
            assertEquals(emptyList(), waiting(), "the ship refusing it in its answer settles it")
            pass()
            assertEquals(3, observed, "and a refused claim is not sent again")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // Turning the pipe off does not wait for a pass already running. The
    // pass then wrote its cursors back, which made the row again, and the
    // next launch turned the pipe on with the key just revoked.
    @Test
    fun `a pass the owner turned the pipe off under writes nothing back`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-off-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.orreryNoticed().insertIfNew(
                io.nisfeb.talon.data.OrreryNoticedEntity(
                    id = "n1", ship = "~zod", subject = "person/rose", attr = "location", valueJson = "\"the shop\"", atMs = 1,
                    untilMs = null, conf = 70, sourceKind = "talon-dm", sourceId = "talon://chat/~sampel-palnet?id=1", bodyJson = null,
                    whom = "~sampel-palnet", postId = "1", snippet = "at the shop", state = OrreryRepo.CONFIRMING, createdMs = 1,
                ),
            )
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    // The owner turns the pipe off while the facts go up.
                    if ("/api/observe" in url) {
                        db.orreryAccounts().delete("~zod")
                        return@MockEngine respond("""{"bodies":[],"observations":[{"ok":true}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")
            assertNull(db.orreryAccounts().get("~zod"), "the row turning off deleted stays deleted")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun claim(id: String, attr: String = "location", value: String = "\"the shop\"") = io.nisfeb.talon.data.OrreryNoticedEntity(
        id = id, ship = "~zod", subject = "person/rose", attr = attr, valueJson = value, atMs = 1,
        untilMs = null, conf = 70, sourceKind = "talon-dm", sourceId = "talon://chat/~sampel-palnet?id=$id", bodyJson = null,
        whom = "~sampel-palnet", postId = id, snippet = "at the shop", state = OrreryRepo.CONFIRMING, createdMs = 1,
    )

    // A claim that could not be read threw on every pass, before anything
    // moved. And a bad fact costs that fact: the ship refuses it in its
    // answer, beside the good ones.
    @Test
    fun `one bad item costs that item, not its batch or the pass`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-pass-split-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        var took = 0
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            listOf(claim("n1"), claim("n2", attr = "poison"), claim("n3"), claim("n4", value = "{not json"))
                .forEach { db.orreryNoticed().insertIfNew(it) }
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if ("/api/observe" in url) {
                        // As orrery answers: a bad item refused in the 200,
                        // beside the good ones, never a 400 for the batch.
                        val said = (req.body as? TextContent)?.text.orEmpty()
                        val obs = kotlinx.serialization.json.Json.parseToJsonElement(said).jsonObject["observations"]!!.jsonArray
                        val answers = obs.map { if ("poison" in it.toString()) """{"ok":false,"error":"attr: unknown"}""" else """{"ok":true}""" }
                        took += answers.count { "true" in it }
                        return@MockEngine respond(
                            """{"bodies":[],"observations":[${answers.joinToString(",")}]}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = OrreryRepo(http, scope, db, "test", bareClient = http)
            repo.pass("https://ship.test", "~zod")
            assertEquals(2, took, "the two good claims went up beside the one the ship refused")
            assertEquals(emptyList(), db.orreryNoticed().confirming("~zod"))
            assertEquals("unreadable", db.orreryNoticed().get("n4")?.state, "a claim that cannot be read is set aside")
            assertNotNull(repo.lastPushMs.value, "and the pass finished")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // A batch the ship cannot read at all is a batch problem, never one
    // item: the pass fails and is tried again, and nothing is dropped.
    // And a 403 for a batch past the key's scope is not the key refused.
    @Test
    fun `a batch refused as a whole fails the pass rather than every item`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-pass-whole-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        var observes = 0
        var scopeRefusal = false
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            (1..64).forEach { db.orreryNoticed().insertIfNew(claim("n$it")) }
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if ("/api/observe" in url) {
                        observes++
                        if (scopeRefusal) return@MockEngine respond("""{"error":"not in scope: activity","note":"not in scope: activity"}""", io.ktor.http.HttpStatusCode.Forbidden)
                        return@MockEngine respond("unknown field", io.ktor.http.HttpStatusCode.UnprocessableEntity)
                    }
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val first = OrreryRepo(http, scope, db, "test", bareClient = http)
            first.pass("https://ship.test", "~zod")
            assertEquals(1, observes, "one request, not a hunt through the batch")
            assertEquals(64, db.orreryNoticed().confirming("~zod").size, "every claim still waits")
            assertNull(first.lastPushMs.value, "and the pass did not finish")
            scopeRefusal = true
            OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")
            assertNotNull(db.orreryAccounts().get("~zod"), "a 403 for scope leaves the pipe on")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // Turning off cancels the loop, which is the job a loop pass runs in,
    // so the delete after it never ran: the next launch turned the pipe
    // on again with the key the ship had refused. The tests drove pass(),
    // which runs outside the loop, and never saw it.
    @Test
    fun `a key refused in the loop takes the pipe off for good`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-403-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val repo = run {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.orreryNoticed().insertIfNew(claim("n1"))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    // What orrery answers a key it no longer takes.
                    if ("/api/observe" in url) return@MockEngine respond("""{"error":"forbidden","note":"forbidden"}""", io.ktor.http.HttpStatusCode.Forbidden)
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            OrreryRepo(http, scope, db, "test", bareClient = http)
        }
        try {
            repo.attach("https://ship.test", "~zod")
            // The row goes first and the switch after it, so both are
            // waited for: asserting the switch on the row alone raced.
            kotlinx.coroutines.withTimeout(20_000) {
                while (db.orreryAccounts().get("~zod") != null || repo.enabled.value) kotlinx.coroutines.delay(50)
            }
        } finally {
            repo.detach()
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // A model that gave no answer read as one that found nothing: each
    // message was recorded as read and the cursor went past, so an hour
    // of an outage, out of credit or rate limited, was never read by it.
    @Test
    fun `a model with no answer leaves what it would have read for later`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-pass-nomodel-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        var answering = false
        var asked = 0
        val model = object : LocalModel {
            override val rung = "fake"
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
                asked++
                if (!answering) throw io.nisfeb.talon.ai.ModelHttpError(402, "openrouter.ai: out of credit")
                return """{"claims":[]}"""
            }
            override fun close() = Unit
        }
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.contacts().upsert(
                io.nisfeb.talon.data.ContactEntity(
                    "~sampel-palnet", "Rose", null, null,
                    status = "At the shop now, home by eight", statusUpdatedMs = io.nisfeb.talon.util.nowMs() - 60_000,
                ),
            )
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    val body = when {
                        "/api/observe" in url -> """{"bodies":[],"observations":[]}"""
                        "/apps/orrery/api/state" in url -> state
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            suspend fun pass() = OrreryRepo(http, scope, db, "test", book = { setOf("~sampel-palnet") }, bareClient = http, readWith = model)
                .pass("https://ship.test", "~zod")
            pass()
            assertEquals(1, asked, "the model was asked and did not answer")
            assertNull(db.orrerySent().get("~zod", "status:~sampel-palnet"), "so the line is not recorded as read")
            answering = true
            pass()
            assertEquals(2, asked, "the next pass reads it")
            assertNotNull(db.orrerySent().get("~zod", "status:~sampel-palnet"))
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // The ship reads the owner's chats itself (orrery 39): Talon reading
    // them too read every message twice and proposed everything twice.
    @Test
    fun `Talon reads no chats, the ship's reader being the only one`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-pass-nochats-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        var asked = 0
        val model = object : LocalModel {
            override val rung = "fake"
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
                asked++
                return """{"claims":[]}"""
            }
            override fun close() = Unit
        }
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.messages().upsertAll(
                listOf(
                    io.nisfeb.talon.data.MessageEntity(whom = "~bus", id = "2", author = "~bus", sentMs = io.nisfeb.talon.util.nowMs() - 60_000, contentJson = """[{"inline":["The car is fixed"]}]""", kind = "chat"),
                ),
            )
            val asks = mutableListOf<String>()
            var observed = ""
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    asks += url
                    if ("/api/observe" in url) observed += (req.body as? TextContent)?.text.orEmpty()
                    val body = when {
                        "/api/observe" in url -> """{"bodies":[],"observations":[]}"""
                        "/apps/orrery/api/state" in url -> state
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = OrreryRepo(http, scope, db, "test", bareClient = http, readWith = model)
            repo.pass("https://ship.test", "~zod")
            assertNotNull(repo.lastPushMs.value, "the pass ran")
            assertEquals(0, asked, "no chat is read here")
            assertTrue("talon://chat" !in observed, "and nothing from a chat goes up: $observed")
            assertTrue(asks.none { "/apps/calendar/" in it }, "nor is the calendar read to write its events: $asks")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // The ship sends every approved message itself now, chat included:
    // Talon claiming them too would be a second sender racing the first.
    @Test
    fun `Talon claims and sends no messages, the ship's executor being the only one`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-nosend-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val asked = mutableListOf<String>()
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            val http = HttpClient(
                MockEngine { req ->
                    asked += "${req.method.value} ${req.url}"
                    val body = when {
                        "/apps/orrery/api/state" in req.url.toString() -> state
                        "/apps/orrery/api/actions" in req.url.toString() -> """[{"id":"a1","kind":"message","title":"Tell Rose",
                            "payload":{"via":"chat","to":"person/rose","text":"late"},"about":["person/rose"],"status":"approved","by":"owner"}]"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = OrreryRepo(http, scope, db, "test", bareClient = http)
            repo.pass("https://ship.test", "~zod")
            assertNotNull(repo.lastPushMs.value, "the pass ran")
            assertEquals(listOf("a1"), repo.actions.value.map { it.id }, "the approved message is shown as waiting on the ship")
            assertTrue(asked.none { it.startsWith("POST") && "/api/actions/" in it }, "and never claimed here: $asked")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // No state view, no reading, so nothing was read: the cursors stay
    // where they were. They used to jump past the whole pass, and those
    // threads were never read by anything.
    @Test
    fun `a pass that cannot read the state writes nothing`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-no-state-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        var stateUp = false
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.contacts().upsert(io.nisfeb.talon.data.ContactEntity("~sampel-palnet", "Rose", null, null))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    asked += url
                    if ("/apps/orrery/api/state" in url && !stateUp) {
                        return@MockEngine respond("busy", io.ktor.http.HttpStatusCode.InternalServerError)
                    }
                    val body = when {
                        "/api/observe" in url -> """{"bodies":[],"observations":[]}"""
                        "/apps/orrery/api/state" in url -> state
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = { OrreryRepo(http, scope, db, "test", book = { setOf("~sampel-palnet") }, bareClient = http) }
            val first = repo().also { it.pass("https://ship.test", "~zod") }
            // With no view of the ship's bodies the pass stops: run on, it
            // made everyone again from their @p, the twins of merged ones.
            assertNull(first.lastPushMs.value, "the pass stopped at the state and wrote nothing")
            assertEquals(0, asked.count { "/api/observe" in it })

            stateUp = true
            val second = repo().also { it.pass("https://ship.test", "~zod") }
            assertNotNull(second.lastPushMs.value, "the next pass runs")
            assertEquals(1, asked.count { "/api/observe" in it }, "and writes what it knows")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
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
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }

    // Since orrery 44 a settings write answers once it has landed, with
    // the document as stored. Read back straight after, it used to show
    // the old values, which is why the settings looked out of step.
    @Test
    fun `a settings write shows the ship's answer and reads nothing back`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-settings-answer-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        val gets = mutableListOf<String>()
        val puts = mutableListOf<String>()
        try {
            val http = HttpClient(
                MockEngine { req ->
                    val path = req.url.encodedPath
                    if (req.method == HttpMethod.Get) gets += path
                    if (req.method == HttpMethod.Put) puts += path + " " + (req.body as? TextContent)?.text
                    val body = when {
                        req.method == HttpMethod.Put && path.endsWith("/api/chat") ->
                            """{"enabled":true,"dms":["~bus"],"channels":["chat/~host/general"],"people":{},"poll_minutes":5,"send_dms":true}"""
                        req.method == HttpMethod.Put && path.endsWith("/api/mail") ->
                            """{"enabled":true,"poll_minutes":10,"backfill_hours":720}"""
                        req.method == HttpMethod.Put && path.endsWith("/api/generator") ->
                            """{"enabled":true,"url":"https://openrouter.ai/api/v1","model":"m","api_key":"...abcd"}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            val repo = OrreryRepo(http, scope, db, "test", bareClient = http)
            repo.attach("https://ship.test", "~zod")
            gets.clear()
            repo.setChatReader(kotlinx.serialization.json.buildJsonObject { put("enabled", kotlinx.serialization.json.JsonPrimitive(true)) }).getOrThrow()
            assertEquals(ChatReader(true, setOf("~bus"), setOf("chat/~host/general"), sendDms = true), repo.chatReader.value)
            repo.setMailReader(true).getOrThrow()
            assertEquals(true, repo.mailReader.value, "the ship's mail reader, as its answer says")
            assertEquals(listOf("/apps/orrery/api/mail {\"enabled\":true}"), puts.filter { "/api/mail" in it }, "only the switch is sent")
            repo.setGenerator(true).getOrThrow()
            val g = repo.generatorSettings.value!!
            assertTrue(g.enabled && g.keySet, "a key shown by its last four is a key set")
            assertEquals(emptyList(), gets.filter { it.endsWith("/api/chat") || it.endsWith("/api/mail") || it.endsWith("/api/generator") }, "nothing read back")
        } finally {
            // Joined, not just cancelled: work still running on a closed db
            // fails a later test as an uncaught exception.
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            db.close()
            dir.deleteRecursively()
        }
    }
}
