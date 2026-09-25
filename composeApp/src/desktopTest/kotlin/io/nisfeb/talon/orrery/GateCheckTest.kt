package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.OrreryAccountEntity
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The check that chooses Jev's threshold: the gate over messages Talon
 * already holds, one line each and a count per threshold; and Stop
 * stops it, leaving no failure behind.
 */
class GateCheckTest {
    private val asked = AtomicInteger()

    /** A repo over a ship and a decision model; the model answers [answering] calls, then thinks forever. */
    private fun withRepo(answering: Int = Int.MAX_VALUE, block: suspend (OrreryRepo) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-gatecheck-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            val now = nowMs()
            db.messages().upsertAll(
                listOf("The car is fixed now", "Moving to Leeds in May", "lol ok then see you").mapIndexed { i, t ->
                    MessageEntity("~bus", "~bus/$i", "~bus", now - 60_000 + i, """[{"inline":["$t"]}]""", "/chat")
                },
            )
            val http = HttpClient(MockEngine { req ->
                val json = { body: String -> respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
                when {
                    req.url.host == "decide.test" -> {
                        if (asked.incrementAndGet() > answering) awaitCancellation()
                        json("""{"answers":{"worth_reading":{"type":"noul","noul":0.8}},"usage":{"input_tokens":100,"cost":0.00001}}""")
                    }
                    "/apps/orrery/api/state" in req.url.toString() -> json("""{"me":"person/me","rev":1,"bodies":[],"schema":{"kinds":{},"actions":[]}}""")
                    else -> json("[]")
                }
            })
            val config = AiSettings.Config(
                provider = AiSettings.Provider.Anthropic, apiKey = "", model = null,
                savedProfile = AiProfile(listOf(AiProvider("or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-or"))),
            )
            val settings = MutableStateFlow(DecideSettings(on = true, url = "https://decide.test/v1"))
            val repo = OrreryRepo(
                http, scope, db, "test",
                cloud = CloudTriage(MutableStateFlow(false)) { config },
                decide = DecideControl(settings) { settings.value = it },
                bareClient = http,
            ).apply { attach("https://ship.test", "~zod") }
            block(repo)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the check reads each held message once and counts what each threshold lets through`() = withRepo { repo ->
        val check = repo.checkGate(limit = 10).getOrThrow()
        assertEquals(3, check.total)
        assertTrue(check.lines.all { it.startsWith("0.80 read | ~bus: ") }, check.lines.toString())
        assertEquals(listOf(0.2, 0.25, 0.3, 0.35, 0.4).map { it to 3 }, check.readAt)
        assertEquals(0, check.failed)
    }

    @Test
    fun `Stop stops the check, and no failure is left showing`() = withRepo(answering = 1) { repo ->
        repo.startGateCheck(limit = 10)
        withTimeout(5_000) { while ((repo.gateCheck.value?.done ?: 0) < 1) delay(20) }
        repo.stopGateCheck()
        // Long enough for a swallowed cancellation to have written its "failure" back.
        delay(500)
        assertNull(repo.gateCheck.value, "stopped is nothing to show, not a failed check")
    }
}
