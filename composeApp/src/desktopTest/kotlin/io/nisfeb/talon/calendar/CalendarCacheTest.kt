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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        .addMigrations(CALENDAR_ROWS_MIGRATION)
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
            scope.cancel()
            db.close()
        }
    }

    private suspend fun waitFor(done: suspend () -> Boolean) {
        repeat(250) {
            if (done()) return
            delay(20)
        }
        error("timed out")
    }
}
