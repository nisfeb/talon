package io.nisfeb.talon.mail

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
import io.nisfeb.talon.data.MAIL_ROWS_MIGRATION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mail kept across a restart: the table's migration, and a cold start that shows what was read. */
class MailDiskTest {

    private lateinit var tmp: File
    private val dbPath get() = File(tmp, "talon.db").absolutePath

    @BeforeTest
    fun setUp() {
        tmp = createTempDirectory(prefix = "talon-mail-disk-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    // No destructive fallback: a migration that does not match the entity fails here.
    private fun db() = Room.databaseBuilder<AppDatabase>(name = dbPath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MAIL_ROWS_MIGRATION, CALENDAR_ROWS_MIGRATION, ORRERY_ACCOUNTS_MIGRATION)
        .build()

    @Test
    fun `the mail table arrives by migration and everything else stays`() = runBlocking {
        db().also { it.mailRows().listing("inbox"); it.close() }
        // Wind the file back to 41: the table gone, a row of other data in place.
        BundledSQLiteDriver().open(dbPath).also { c ->
            c.execSQL("DROP TABLE mail_rows")
            c.execSQL("INSERT INTO rail_item_prefs (itemName, visible) VALUES ('Mail', 0)")
            c.execSQL("PRAGMA user_version = 41")
            c.close()
        }
        val db = db()
        try {
            assertEquals(emptyList(), db.mailRows().listing("inbox"))
            assertEquals(false, db.railItemPrefs().streamAll().first().single { it.itemName == "Mail" }.visible)
        } finally {
            db.close()
        }
    }

    private val page = """{"total":1,"offset":0,"limit":50,"view":"inbox","threads":[
        {"id":"0v1","subject":"Invoice","from":"~zod","snippet":"pay me","verdict":"verified",
         "forged":false,"count":1,"last":5,"unread":true,"participants":["~zod","~nec"],
         "unreadable":0,"archived":false,"labels":[]}]}"""

    private val thread = """{"id":"0v1","participants":["~zod","~nec"],"last":5,"unreadable":0,
        "archived":false,"labels":[],"messages":[{"id":"m1","from":"~zod","to":["~nec"],
        "subject":"Invoice","body":"pay me","sent":5,"verdict":"verified","read":false}]}"""

    private fun repo(scope: CoroutineScope, db: AppDatabase, respond: (String) -> Pair<Int, String>): MailRepo {
        val http = HttpClient(
            MockEngine { req ->
                val (code, body) = respond(req.url.encodedPath)
                if (code == 200) {
                    respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                } else {
                    respondError(HttpStatusCode.fromValue(code), body)
                }
            },
        )
        return MailRepo(
            http, scope, pollIntervalMs = 60 * 60 * 1000L,
            rows = db.mailRows(), threadDir = File(tmp, "mail").absolutePath,
        )
    }

    @Test
    fun `a cold start shows the last listing and thread while the ship is out of reach`() = runBlocking {
        val db = db()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val warm = repo(scope, db) { path -> 200 to if ("/thread/" in path) thread else page }
            warm.attach("https://ship.example")
            waitFor { warm.page.value != null }
            warm.loadThread("0v1")
            warm.detach()

            val cold = repo(scope, db) { 503 to """{"error":"down"}""" }
            cold.attach("https://ship.example")
            waitFor { cold.page.value != null }
            assertEquals(listOf("Invoice"), cold.page.value?.threads?.map { it.subject })
            assertEquals(1, cold.page.value?.total)
            assertEquals("pay me", cold.storedThread("0v1")?.messages?.single()?.body)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private suspend fun waitFor(done: () -> Boolean) {
        repeat(250) {
            if (done()) return
            delay(20)
        }
        error("timed out")
    }
}
