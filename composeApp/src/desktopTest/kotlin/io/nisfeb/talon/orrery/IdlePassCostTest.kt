package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
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
import kotlin.test.assertTrue

/**
 * What a pass with nothing new costs the ship.
 *
 * Every request into a grubbery app is about a second of the ship's
 * single thread and they queue, so a ten-minute pass that asks a dozen
 * times is a ship that feels slow to the owner. Measured on
 * ~ricsul-bilwyt on 2026-09-20: the pass read the state three times and
 * listed two hundred mail threads on every wake. Twelve requests an
 * idle pass, against four now.
 */
class IdlePassCostTest {
    private val state = """{"me":"person/me","rev":1,"bodies":[],"schema":{"kinds":{},"actions":[]}}"""

    @Test
    fun `an idle pass reads the state once and lists no mail beyond the cursor`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-idle-pass-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val asked = mutableListOf<String>()
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret", mailCursor = 5_000L))
            // The scope was measured lately, so this pass does not ask again.
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    asked += url
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/orrery/api/actions" in url -> "[]"
                        "/apps/auspex/api/inbox" in url ->
                            // Everything here is older than the cursor, so one page settles it.
                            """{"total":3,"offset":0,"limit":20,"view":"all","threads":[
                                {"id":"t1","subject":"Older","last":4000,"count":1},
                                {"id":"t2","subject":"Older still","last":3000,"count":1}]}"""
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        "/apps/calendar/events.json" in url -> "[]"
                        "/apps/calendar/calendars.json" in url -> "[]"
                        else -> "{}"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
            // One ship for both clients: the owner's and the key's.
            OrreryRepo(http, scope, db, "test", bareClient = http).pass("https://ship.test", "~zod")

            fun count(part: String) = asked.count { part in it }
            assertEquals(1, count("/apps/orrery/api/state"), "one state read; the triage and the brief take that one:\n" + asked.joinToString("\n"))
            assertEquals(1, count("/apps/auspex/api/inbox"), "one listing, the cursor's page:\n" + asked.joinToString("\n"))
            assertTrue(asked.none { "limit=200" in it }, "no two-hundred-thread listing while a cursor stands")
            assertTrue(asked.any { "limit=20" in it }, "the cursor's page is a small one")
            assertEquals(1, count("/apps/orrery/api/actions"), "one actions listing for the open list, the mirror and the brief")
            assertEquals(0, count("/apps/calendar/calendars.json"), "the calendar list is read only where the pass writes")
            assertEquals(0, count("/apps/calendar/events.json"), "and the whole listing only where there is something to mirror")
            assertEquals(0, count("/apps/orrery/api/schema"), "the scope was measured lately")
            // Measured with this harness: twelve requests before, four now.
            assertTrue(asked.size <= 4, "an idle pass asks the ship ${asked.size} times:\n" + asked.joinToString("\n"))
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }
}
