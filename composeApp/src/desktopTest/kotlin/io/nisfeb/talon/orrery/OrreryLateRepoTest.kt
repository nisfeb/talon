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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Orrery 110's marks through the repo, where every screen that lists
 * actions reads them: shown at once, kept over a list read before the
 * ship applied them, and put back with the reason when refused. Real
 * time throughout: the ship's writer lags its answer here as on ricsul.
 */
class OrreryLateRepoTest {
    private val due = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() - 2 * 3_600_000).toString()

    /** What the ship's writer has applied: status and payload by id. */
    private val status = ConcurrentHashMap<String, String>()
    private val payload = ConcurrentHashMap<String, String>()
    /** Moves the ship has answered and not yet applied, in order. */
    private val taken = CopyOnWriteArrayList<Pair<String, String>>()
    private val posts = CopyOnWriteArrayList<String>()
    /** Applied as soon as answered, as a quiet ship's writer is. */
    @Volatile private var applyAtOnce = false
    @Volatile private var refuse: Pair<HttpStatusCode, String>? = null
    @Volatile private var down = false
    @Volatile private var version: String? = """{"version":110}"""
    /** The ship's own hour from now, to the second, as en-iso writes it. */
    private val snoozedTo = kotlin.time.Instant.fromEpochSeconds(System.currentTimeMillis() / 1000 + 3600).toString()

    private fun row(id: String) =
        """{"id":"$id","kind":"task","title":"Task $id","payload":${payload[id] ?: "{}"},"due":"$due","status":"${status[id]}"}"""

    private fun apply() {
        taken.forEach { (id, op) ->
            if (op.startsWith("status:")) status[id] = op.removePrefix("status:")
            else {
                val (field, value) = op.split('=', limit = 2)
                val o = kotlinx.serialization.json.Json.parseToJsonElement(payload[id] ?: "{}") as kotlinx.serialization.json.JsonObject
                payload[id] = kotlinx.serialization.json.JsonObject(if (value.isEmpty()) o - field else o + (field to kotlinx.serialization.json.JsonPrimitive(value))).toString()
            }
        }
        taken.clear()
    }

    private fun ship(block: suspend (OrreryRepo) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-late-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = { s: HttpStatusCode, body: String -> respond(body, s, headersOf(HttpHeaders.ContentType, "application/json")) }
            if (down && "/api/actions" in path) throw java.io.IOException("connection reset")
            when {
                path == "/apps/orrery/api/version" -> version?.let { json(HttpStatusCode.OK, it) } ?: json(HttpStatusCode.NotFound, """{"error":"no such route"}""")
                path.startsWith("/apps/orrery/api/actions/") && req.method == HttpMethod.Post -> {
                    val body = req.body.toByteArray().decodeToString()
                    posts += "$path $body"
                    refuse?.let { return@MockEngine json(it.first, it.second) }
                    val id = path.removePrefix("/apps/orrery/api/actions/").substringBefore('/')
                    val o = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                    val said = when {
                        path.endsWith("/priority") -> "priority" to (o["priority"] as kotlinx.serialization.json.JsonPrimitive).content
                        path.endsWith("/snooze") -> "snooze_until" to snoozedTo
                        else -> null
                    }
                    if (said != null) taken += id to "${said.first}=${said.second}"
                    else taken += id to "status:" + (o["status"] as kotlinx.serialization.json.JsonPrimitive).content
                    if (applyAtOnce) apply()
                    json(HttpStatusCode.OK, said?.let { """{"ok":true,"id":"$id","${it.first}":"${it.second}"}""" } ?: """{"ok":true}""")
                }
                path == "/apps/orrery/api/actions" -> {
                    val want = req.url.parameters["status"] ?: "open"
                    val rows = status.keys.sorted().filter { id -> if (want == "open") status[id] in OrreryRepo.OPEN_STATUSES else status[id] == want }
                    json(HttpStatusCode.OK, rows.joinToString(",", "[", "]") { row(it) })
                }
                else -> json(HttpStatusCode.OK, "{}")
            }
        })
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        try {
            withTimeout(5_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(20) }
            repo.refreshActions()
            withTimeout(5_000) { while (repo.actions.value.size < status.size || repo.version.value == null) delay(20) }
            block(repo)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun OrreryRepo.late() = lateActions(actions.value, System.currentTimeMillis()).map { it.id }

    @Test
    fun `a snooze leaves Late at once and stays off it while the ship catches up`() {
        status["a1"] = "approved"; payload["a1"] = """{"priority":"high"}"""
        ship { repo ->
            assertEquals(110, repo.version.value)
            assertEquals(listOf("a1"), repo.late())
            repo.mark("a1", snoozeMinutes = SNOOZE_MINUTES)
            assertEquals(emptyList<String>(), repo.late(), "off Late before the ship answers")
            fun held() = repo.actions.value.single().payload["snooze_until"].toString().trim('"')
            // The ship's own time, from its answer, in place of this device's.
            withTimeout(5_000) { while (held() != snoozedTo) delay(20) }
            assertEquals(listOf("/apps/orrery/api/actions/a1/snooze {\"minutes\":60}"), posts.toList())
            // The beacon's read, before the writer has applied it.
            repo.refreshActions()
            assertEquals(emptyList<String>(), repo.late(), "a stale list does not put it back")
            assertEquals(snoozedTo, held())
            apply()
            repo.refreshActions()
            assertEquals(emptyList<String>(), repo.late())
        }
    }

    @Test
    fun `not high and mark high change every list at once and stay changed`() {
        status["a1"] = "approved"; payload["a1"] = """{"priority":"high"}"""
        status["a2"] = "proposed"
        ship { repo ->
            repo.mark("a1", high = false)
            repo.mark("a2", high = true)
            assertFalse(repo.actions.value.first { it.id == "a1" }.high)
            assertTrue(repo.actions.value.first { it.id == "a2" }.high)
            assertEquals(listOf("a2"), repo.late(), "a proposal marked high and past its due is late")
            withTimeout(5_000) { while (taken.size < 2) delay(20) }
            assertEquals(
                setOf("/apps/orrery/api/actions/a1/priority {\"priority\":\"\"}", "/apps/orrery/api/actions/a2/priority {\"priority\":\"high\"}"),
                posts.toSet(),
            )
            repo.refreshActions()
            assertEquals(listOf("a2"), repo.late(), "held over the list read before the ship applied them")
            apply()
            repo.refreshActions()
            assertEquals(listOf("a2"), repo.late())
            // Once shown, the ship's word wins again: unmarked on another device.
            payload["a2"] = "{}"
            repo.refreshActions()
            assertEquals(emptyList<String>(), repo.late())
        }
    }

    // transition-ok moves a proposal only to approved or dismissed, and the
    // ship answers before its writer applies: done is sent once approved shows.
    @Test
    fun `done on a late proposal approves and then marks done and never comes back between`() {
        status["a1"] = "proposed"; payload["a1"] = """{"priority":"high"}"""
        ship { repo ->
            repo.answer("a1", "done")
            assertEquals(emptyList<String>(), repo.actions.value.map { it.id }, "gone from every list at once")
            withTimeout(5_000) { while (taken.isEmpty()) delay(20) }
            assertEquals(listOf("/apps/orrery/api/actions/a1 {\"status\":\"approved\"}"), posts.toList(), "done waits for approved")
            apply()
            // The beacon reads it approved, between the two moves.
            repo.refreshActions()
            assertEquals(emptyList<String>(), repo.actions.value.map { it.id }, "not back as approved")
            withTimeout(10_000) { while (posts.size < 2) delay(20) }
            assertEquals("/apps/orrery/api/actions/a1 {\"status\":\"done\"}", posts[1])
            apply()
            repo.refreshActions()
            assertEquals(emptyList<String>(), repo.actions.value.map { it.id })
            assertNull(repo.answerProblem.value)
        }
    }

    @Test
    fun `done on an approved one is one move`() {
        status["a1"] = "approved"; payload["a1"] = """{"priority":"high"}"""
        applyAtOnce = true
        ship { repo ->
            repo.answer("a1", "done")
            withTimeout(5_000) { while (posts.isEmpty()) delay(20) }
            Thread.sleep(300)
            assertEquals(listOf("/apps/orrery/api/actions/a1 {\"status\":\"done\"}"), posts.toList())
        }
    }

    @Test
    fun `a refused mark puts the action back as it was and says why`() {
        status["a1"] = "approved"; payload["a1"] = """{"priority":"high"}"""
        ship { repo ->
            for ((code, why) in listOf(
                HttpStatusCode.Forbidden to "read only key",
                HttpStatusCode.NotFound to "no such action",
                HttpStatusCode.Conflict to "only an open action is marked",
            )) {
                refuse = code to """{"error":"$why"}"""
                repo.mark("a1", snoozeMinutes = SNOOZE_MINUTES)
                withTimeout(5_000) { while (repo.answerProblem.value == null) delay(20) }
                assertEquals("Snooze did not go through: $why.", repo.answerProblem.value)
                assertEquals(listOf("a1"), repo.late(), "back on Late")
                repo.refreshActions()
                assertEquals(listOf("a1"), repo.late(), "and it stays back")
            }
            refuse = HttpStatusCode.Conflict to """{"error":"only an open action is marked"}"""
            repo.mark("a1", high = false)
            withTimeout(5_000) { while (repo.answerProblem.value == null) delay(20) }
            assertTrue(repo.actions.value.single().high, "still high")
            assertEquals("Not high did not go through: only an open action is marked.", repo.answerProblem.value)
        }
    }

    @Test
    fun `a mark the ship never hears is said as no answer and kept as it was`() {
        status["a1"] = "approved"; payload["a1"] = """{"priority":"high"}"""
        ship { repo ->
            down = true
            repo.mark("a1", snoozeMinutes = SNOOZE_MINUTES)
            withTimeout(5_000) { while (repo.answerProblem.value == null) delay(20) }
            assertEquals("Snooze did not go through: your ship did not answer. Try it again.", repo.answerProblem.value)
            assertEquals(listOf("a1"), repo.late())
            down = false
            repo.answer("a1", "done")
            assertNull(repo.answerProblem.value, "the next answer clears it")
        }
    }

    @Test
    fun `an orrery older than 110 says so and the version is read once`() {
        status["a1"] = "approved"
        version = null
        ship { repo ->
            assertEquals(0, repo.version.value, "no route: an older orrery")
            version = """{"version":110}"""
            repo.opened()
            Thread.sleep(300)
            assertEquals(0, repo.version.value, "an answer is kept for the login")
        }
    }

    // A late one's nag shares the action's slot: done on another device,
    // the approved one leaves the list and its last nag goes with it.
    @Test
    fun `an approved one gone from the list takes its nag back`() {
        status["a1"] = "approved"; payload["a1"] = """{"priority":"high"}"""
        ship { repo ->
            val cleared = CopyOnWriteArrayList<String>()
            repo.onActions = { _, clear -> cleared += clear }
            status["a1"] = "done"
            repo.refreshActions()
            assertTrue("a1" in cleared, cleared.toString())
        }
    }
}
