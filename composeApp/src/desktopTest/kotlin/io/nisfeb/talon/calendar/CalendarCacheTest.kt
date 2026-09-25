package io.nisfeb.talon.calendar

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.CALENDAR_ROWS_MIGRATION
import io.nisfeb.talon.data.ORRERY_ACCOUNTS_MIGRATION
import io.nisfeb.talon.data.ORRERY_SENT_MIGRATION
import io.nisfeb.talon.data.COMET_DOMES_MIGRATION
import io.nisfeb.talon.data.ORRERY_HANDOFF_MIGRATION
import io.nisfeb.talon.data.ORRERY_SHIP_WORK_MIGRATION
import io.nisfeb.talon.data.MESSAGE_SEARCH_TEXT_MIGRATION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The calendar paints what it last read, rather than an empty grid, while the ship is asked again. */
class CalendarCacheTest {

    private lateinit var tmp: File
    private val dbPath get() = File(tmp, "talon.db").absolutePath

    @BeforeTest
    fun setUp() {
        tmp = createTempDirectory(prefix = "talon-calendar-cache-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun db() = Room.databaseBuilder<AppDatabase>(name = dbPath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(CALENDAR_ROWS_MIGRATION, ORRERY_ACCOUNTS_MIGRATION, ORRERY_SENT_MIGRATION, COMET_DOMES_MIGRATION, ORRERY_HANDOFF_MIGRATION, ORRERY_SHIP_WORK_MIGRATION, MESSAGE_SEARCH_TEXT_MIGRATION)
        .build()

    private val window = """{"rows":[{"id":"0v1","cal":"home","meta":{"name":"Dentist"},"l":100,"r":200}]}"""
    private val calendars = """[{"id":"home","name":"Home","color":"#ff0000","kind":"local","count":1}]"""

    private fun repo(scope: CoroutineScope, db: AppDatabase, respond: (String) -> Pair<Int, String>) =
        CalendarRepo(
            HttpClient(
                MockEngine { req ->
                    val (code, body) = respond(req.url.encodedPath)
                    if (code == 200) {
                        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    } else {
                        respondError(HttpStatusCode.fromValue(code), body)
                    }
                },
            ),
            scope,
            pollIntervalMs = 60 * 60 * 1000L,
            cache = db.calendarCache(),
        )

    @Test
    fun `a cold start shows the last window while the ship is out of reach`() = runBlocking {
        val db = db()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val warm = repo(scope, db) { path ->
                when {
                    "/window" in path -> 200 to window
                    "/calendars" in path -> 200 to calendars
                    else -> 404 to """{"error":"none"}"""
                }
            }
            warm.attach("https://ship.example")
            // The window lands early in a refresh and the save comes at its
            // end, so wait for what was actually written, not for the flow.
            // The parts are written in turn; wait for the last one asserted.
            waitFor { db.calendarCache().read("calendars").isNotEmpty() }
            warm.detach()

            val cold = repo(scope, db) { 503 to """{"error":"down"}""" }
            cold.attach("https://ship.example")
            waitFor { cold.rows.value?.isNotEmpty() == true }
            assertEquals(listOf("Dentist"), cold.rows.value?.map { it.name })
            assertEquals(listOf("Home"), cold.calendars.value.map { it.name })
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
        }
    }

    private val month = """{"rows":[{"id":"0v2","cal":"home","meta":{"name":"Sports day"},"l":300,"r":400}]}"""

    @Test
    fun `a cold start shows the month the screen last read, not an empty grid`() = runBlocking {
        val db = db()
        val scope = CoroutineScope(SupervisorJob())
        try {
            // The widget's window is asked for first, the screen's month after.
            var windows = 0
            val warm = repo(scope, db) { path ->
                when {
                    "/window" in path -> 200 to (if (windows++ == 0) window else month)
                    "/calendars" in path -> 200 to calendars
                    else -> 404 to """{"error":"none"}"""
                }
            }
            warm.attach("https://ship.example")
            waitFor("the warm window") { warm.rows.value?.isNotEmpty() == true }
            warm.loadRange(250, 500)
            assertEquals(listOf("Sports day"), warm.rangeRows.value?.map { it.name })
            waitFor("the range in the cache") { db.calendarCache().read("range").isNotEmpty() }
            warm.detach()

            val cold = repo(scope, db) { 503 to """{"error":"down"}""" }
            cold.attach("https://ship.example")
            waitFor("the cold month") { cold.rangeRows.value?.isNotEmpty() == true }
            assertEquals(listOf("Sports day"), cold.rangeRows.value?.map { it.name }, "the screen's own month, from the cache")
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
        }
    }

    private suspend fun waitFor(what: String = "", done: suspend () -> Boolean) {
        repeat(250) {
            if (done()) return
            delay(20)
        }
        error("timed out waiting for $what")
    }
}
