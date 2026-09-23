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
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
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
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            suspend fun pass() = OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")
            suspend fun waiting() = db.orreryNoticed().confirming("~zod").map { it.id }
            pass()
            assertEquals(1, observed)
            assertEquals(listOf("n1"), waiting(), "a busy ship's 503 leaves it waiting")
            answer = 404
            pass()
            assertEquals(listOf("n1"), waiting(), "and so does a 404 from a desk mid-update: it is not the claim's fault")
            assertEquals(0L, db.orreryAccounts().get("~zod")!!.calendarCursor, "no failed pass moves a cursor")
            answer = 200
            pass()
            assertEquals(3, observed, "sent again on each pass")
            assertEquals(emptyList(), waiting(), "the ship refusing it in its answer settles it")
            assertTrue(db.orreryAccounts().get("~zod")!!.calendarCursor > 0, "and the pass finished, so nothing is stuck behind it")
            pass()
            assertEquals(3, observed, "and a refused claim is not sent again")
        } finally {
            scope.cancel()
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
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
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
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")
            assertNull(db.orreryAccounts().get("~zod"), "the row turning off deleted stays deleted")
        } finally {
            scope.cancel()
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
    fun `one bad item costs that item, not its batch or the pass`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-split-").toFile()
        val db = db(dir)
        val scope = CoroutineScope(SupervisorJob())
        var took = 0
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
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
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")
            assertEquals(2, took, "the two good claims went up beside the one the ship refused")
            assertEquals(emptyList(), db.orreryNoticed().confirming("~zod"))
            assertEquals("unreadable", db.orreryNoticed().get("n4")?.state, "a claim that cannot be read is set aside")
            assertTrue(db.orreryAccounts().get("~zod")!!.calendarCursor > 0, "and the pass finished")
        } finally {
            scope.cancel()
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
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
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
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")
            assertEquals(1, observes, "one request, not a hunt through the batch")
            assertEquals(64, db.orreryNoticed().confirming("~zod").size, "every claim still waits")
            assertEquals(0L, db.orreryAccounts().get("~zod")!!.calendarCursor, "and the pass did not finish")
            scopeRefusal = true
            OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")
            assertNotNull(db.orreryAccounts().get("~zod"), "a 403 for scope leaves the pipe on")
        } finally {
            scope.cancel()
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
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 90_000L))
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
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
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
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    // A model that gave no answer read as one that found nothing: each
    // post was recorded as read and the cursor went past, so an hour of
    // an outage, out of credit or rate limited, was never read by it.
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
            val start = io.nisfeb.talon.util.nowMs() - 3_600_000
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", messagesCursor = start, mailCursor = 90_000L))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.messages().upsertAll(
                listOf(
                    io.nisfeb.talon.data.MessageEntity(whom = "~sampel-palnet", id = "1", author = "~sampel-palnet", sentMs = start + 60_000, contentJson = """[{"inline":["I'm at the shop now"]}]""", kind = "chat"),
                    io.nisfeb.talon.data.MessageEntity(whom = "~sampel-palnet", id = "2", author = "~sampel-palnet", sentMs = start + 120_000, contentJson = """[{"inline":["See you at eight"]}]""", kind = "chat"),
                ),
            )
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    val body = when {
                        "/api/observe" in url -> """{"bodies":[],"observations":[]}"""
                        "/apps/orrery/api/state" in url -> state
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            suspend fun pass() = OrreryRepo(http, scope, db, "test", bareClient = http, readWith = model).pass("https://ship.test", "~zod")
            pass()
            assertEquals(1, asked, "it stops at the first post the model does not answer for")
            assertTrue(db.orreryAccounts().get("~zod")!!.messagesCursor < start + 60_000, "and the cursor stays behind it")
            assertNull(db.orrerySent().get("~zod", "msg:~sampel-palnet/1"), "which is not recorded as read")
            answering = true
            pass()
            assertEquals(3, asked, "the next pass reads both")
            assertNotNull(db.orrerySent().get("~zod", "msg:~sampel-palnet/2"))
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    // The ship answers a proposal that has an open twin, the same kind
    // and title, with that twin. A replacement made before the old one was
    // dismissed came back as the old action, which was then dismissed:
    // every reply that moved a due lost the action.
    @Test
    fun `moving a due makes a new action and leaves no action lost`() = runBlocking<Unit> {
        val statuses = mutableMapOf("a1" to "approved")
        val dues = mutableMapOf("a1" to "2026-09-24T17:00:00Z")
        // Orrery answers a status change before its writer applies it:
        // here the change lands on the next read of the actions.
        val landing = mutableMapOf<String, String>()
        var next = 2
        val http = HttpClient(
            MockEngine { req ->
                val url = req.url.toString()
                val said = (req.body as? TextContent)?.text.orEmpty()
                val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(said).jsonObject }.getOrNull()
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    req.method == HttpMethod.Post && url.substringBefore('?').endsWith("/api/act") -> {
                        val twin = statuses.entries.firstOrNull { it.value in setOf("proposed", "approved", "claimed") }
                        if (twin != null) respond("""{"id":"${twin.key}","status":"${twin.value}","existing":true}""", headers = json)
                        else {
                            val id = "a${next++}"
                            statuses[id] = "proposed"
                            dues[id] = o?.get("due")?.jsonPrimitive?.content.orEmpty()
                            respond("""{"id":"$id","status":"proposed","existing":false}""", headers = json)
                        }
                    }
                    req.method == HttpMethod.Post && "/api/actions/" in url -> {
                        val id = url.substringAfter("/api/actions/").substringBefore('/').substringBefore('?')
                        val want = o?.get("status")?.jsonPrimitive?.content ?: statuses.getValue(id)
                        landing[id] = want
                        respond("""{"id":"$id","status":"$want"}""", headers = json)
                    }
                    req.method == HttpMethod.Get && "/api/actions" in url -> {
                        statuses.putAll(landing)
                        landing.clear()
                        val want = url.substringAfter("status=", "")
                        // As orrery reads "open": proposed, approved or claimed.
                        val open = setOf("proposed", "approved", "claimed")
                        respond(
                            statuses.filter { if (want == "open") it.value in open else it.value == want }.keys.joinToString(",", "[", "]") { id ->
                                """{"id":"$id","kind":"task","title":"Call the shop","status":"${statuses[id]}"}"""
                            },
                            headers = json,
                        )
                    }
                    else -> respond("[]", headers = json)
                }
            },
        )
        val repo = OrreryRepo(http, CoroutineScope(SupervisorJob()), db(createTempDirectory(prefix = "talon-move-").toFile()), "test", bareClient = http)
        val old = OrreryAction("a1", "task", "Call the shop", kotlinx.serialization.json.JsonObject(emptyMap()), emptyList(), dues.getValue("a1"), "approved", "generator")
        repo.move(OrreryApi(http, http, "https://ship.test"), "k1.secret", old, Brief.Direction("a1", dueMs = io.nisfeb.talon.ui.parseIsoUtc("2026-09-25T17:00:00Z")))
        statuses.putAll(landing)
        assertEquals("dismissed", statuses["a1"], "the old one goes")
        val made = statuses.keys.single { it != "a1" }
        assertEquals("approved", statuses[made], "a new one stands, approved again as the old one was")
        assertEquals("2026-09-25T17:00:00Z", dues[made])
        // Dismissed with a new due in the same breath is dismissed: no
        // replacement made only to be closed.
        statuses["a9"] = "proposed"
        repo.move(OrreryApi(http, http, "https://ship.test"), "k1.secret", old.copy(id = "a9", status = "proposed"), Brief.Direction("a9", status = "dismissed", dueMs = io.nisfeb.talon.ui.parseIsoUtc("2026-09-27T17:00:00Z")))
        statuses.putAll(landing)
        assertEquals("dismissed", statuses["a9"])
        assertEquals(3, statuses.size, "and nothing made for it")
        // One the owner has settled since the brief named it is left be.
        repo.move(OrreryApi(http, http, "https://ship.test"), "k1.secret", old.copy(status = "done"), Brief.Direction("a1", dueMs = io.nisfeb.talon.ui.parseIsoUtc("2026-09-26T17:00:00Z")))
        assertEquals(3, statuses.size, "nothing made again")
    }

    // Orrery 39 reads the owner's chats on the ship: Talon reading them
    // too read every message twice. But it reads only the DMs and
    // channels picked for it, from authors it can name, and Talon
    // stepping aside from every chat left the rest read by nobody.
    @Test
    fun `Talon reads what the ship's chat reader does not`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-pass-shipchats-").toFile()
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
            val start = io.nisfeb.talon.util.nowMs() - 3_600_000
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", messagesCursor = start, mailCursor = 90_000L))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            db.messages().upsertAll(
                listOf(
                    // In a DM the ship reads, from Rose, whose body carries her ship.
                    io.nisfeb.talon.data.MessageEntity(whom = "~sampel-palnet", id = "1", author = "~sampel-palnet", sentMs = start + 60_000, contentJson = """[{"inline":["I'm at the shop now"]}]""", kind = "chat"),
                    // In a DM nobody picked for the ship.
                    io.nisfeb.talon.data.MessageEntity(whom = "~bus", id = "2", author = "~bus", sentMs = start + 120_000, contentJson = """[{"inline":["The car is fixed"]}]""", kind = "chat"),
                ),
            )
            var observed = ""
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if ("/api/observe" in url) observed += (req.body as? TextContent)?.text.orEmpty()
                    val body = when {
                        url.substringBefore('?').endsWith("/api/chat") -> """{"enabled":true,"dms":["~sampel-palnet"],"channels":[],"people":{}}"""
                        // The generator's key, which the ship's reader reads with.
                        url.substringBefore('?').endsWith("/api/generator") -> """{"enabled":true,"api_key_set":true}"""
                        "/api/observe" in url -> """{"bodies":[],"observations":[]}"""
                        // Rose's body carries her ship, as orrery keeps it: that
                        // is how its chat reader names her.
                        "/apps/orrery/api/state" in url -> state.replace(""""name":"Rose",""", """"name":"Rose","ship":"~sampel-palnet",""")
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        "/apps/auspex/api/inbox" in url -> """{"total":0,"offset":0,"limit":20,"view":"all","threads":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            OrreryRepo(http, scope, db, "test", bareClient = http, readWith = model).pass("https://ship.test", "~zod")
            assertEquals(1, asked, "the one the ship does not read, read here")
            assertNotNull(db.orrerySent().get("~zod", "msg:~sampel-palnet/1"), "the ship's is marked read, so turning its reader off hands it to nobody twice")
            assertNotNull(db.orrerySent().get("~zod", "msg:~bus/2"))
            assertTrue("person/rose" in observed && "last-contact" in observed, "contact is recorded from the ship's post too: its reader writes none")
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
            // With no view of the ship's bodies the pass stops: run on, it
            // made everyone again from their @p, the twins of merged ones.
            assertEquals(0L, after.calendarCursor, "the pass stopped at the state and wrote nothing")
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
