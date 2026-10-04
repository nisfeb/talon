package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.URB_UNFURLS_MIGRATION
import io.nisfeb.talon.data.MESSAGE_STATUS_INDEX_MIGRATION
import io.nisfeb.talon.data.UrbUnfurlDao
import io.nisfeb.talon.data.UrbUnfurlEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * urb:// preview cards kept across starts. A remote page took forty
 * seconds and more to read over Ames, at every start, before its card
 * showed: the kept card shows at once, and the page is read again only
 * once a day.
 */
class UrbUnfurlKeptTest {
    private val dir = createTempDirectory(prefix = "talon-unfurls-").toFile()
    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    private class Kept : UrbUnfurlDao {
        val rows = mutableMapOf<String, UrbUnfurlEntity>()
        override suspend fun get(urbUrl: String) = rows[urbUrl]
        override suspend fun put(row: UrbUnfurlEntity) { rows[row.urbUrl] = row }
    }

    private val asked = CopyOnWriteArrayList<String>()

    /** The ship's lattice: [body] as fetch answers it, or a 500 for null. */
    private fun ship(body: String?) = HttpClient(MockEngine { req ->
        asked += req.url.parameters["url"].orEmpty()
        if (body == null) respond("", HttpStatusCode.InternalServerError)
        else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private val day = UrbUnfurlCache.KEEP_MS
    private fun cards(url: String, body: String?, kept: Kept, now: Long) = runBlocking {
        UrbUnfurlCache.cards(ship(body), "https://ship.test", "c=1", url, kept) { now }.toList()
    }

    // Each test its own address: the in-memory cache is the app's, shared.

    @Test
    fun `a card read is kept, and shown from there the next time without asking`() {
        val url = "urb://~zod/kept-1"
        val kept = Kept()
        assertEquals(listOf("Trail Cleanup"), cards(url, """{"body":"# Trail Cleanup\nSaturday at 9.","mark":"gmi"}""", kept, now = 1_000).map { it.title })
        assertEquals(UrbUnfurlEntity(url, "Trail Cleanup", "Saturday at 9.", 1_000), kept.rows[url])
        asked.clear()
        assertEquals(listOf("Trail Cleanup"), cards(url, null, kept, now = 1_000 + day - 1).map { it.title })
        assertTrue(asked.isEmpty(), "within a day the ship is not asked: $asked")
    }

    @Test
    fun `a day on, the kept card shows at once and the page's own follows`() {
        val url = "urb://~zod/kept-2"
        val kept = Kept().apply { rows[url] = UrbUnfurlEntity(url, "Old title", "old", 0) }
        val shown = cards(url, """{"body":"# New title\nnew","mark":"gmi"}""", kept, now = day + 1)
        assertEquals(listOf("Old title", "New title"), shown.map { it.title })
        assertEquals("New title", kept.rows.getValue(url).title)
        assertEquals(day + 1, kept.rows.getValue(url).fetchedAtMs)
    }

    @Test
    fun `a read that fails keeps the old card and is tried again next time`() {
        val url = "urb://~zod/kept-3"
        val kept = Kept().apply { rows[url] = UrbUnfurlEntity(url, "Old title", "old", 0) }
        assertEquals(listOf("Old title"), cards(url, null, kept, now = day + 1).map { it.title })
        assertEquals(0L, kept.rows.getValue(url).fetchedAtMs, "a failure is not an answer, so the day does not restart")
    }

    @Test
    fun `a page with no card is kept as none, and not asked for again that day`() {
        val url = "urb://~zod/kept-4"
        val kept = Kept()
        assertEquals(emptyList(), cards(url, """{"mark":"gmi"}""", kept, now = 5))
        assertEquals(UrbUnfurlEntity(url, null, null, 5), kept.rows[url])
        asked.clear()
        assertEquals(emptyList(), cards(url, null, kept, now = 10))
        assertTrue(asked.isEmpty())
    }

    // No destructive fallback: a migration that does not match the entity fails here.
    @Test
    fun `the kept cards' table comes in by migration, with the rest of the database kept`() = runBlocking<Unit> {
        val path = File(dir, "talon.db").absolutePath
        fun open() = Room.databaseBuilder<AppDatabase>(name = path).setDriver(BundledSQLiteDriver())
            .addMigrations(URB_UNFURLS_MIGRATION, MESSAGE_STATUS_INDEX_MIGRATION, io.nisfeb.talon.data.WATCHWORDS_DROP_MIGRATION, io.nisfeb.talon.data.ASSISTANT_LOG_MIGRATION, io.nisfeb.talon.data.FOLLOWED_THREADS_MIGRATION).build()
        open().also { it.cometDomes().put(io.nisfeb.talon.data.CometDomeEntity("~sampel", "gw-btc")); it.close() }
        // Wind the file back to 49: no table.
        BundledSQLiteDriver().open(path).also { c ->
            c.execSQL("DROP TABLE urb_unfurls")
            c.execSQL("ALTER TABLE assistant_history DROP COLUMN log") // added at 53
            c.execSQL("PRAGMA user_version = 49")
            c.close()
        }
        val db = open()
        try {
            assertEquals("gw-btc", db.cometDomes().get("~sampel")?.registry, "the rest is kept")
            assertNull(db.urbUnfurls().get("urb://~zod/x"))
            db.urbUnfurls().put(UrbUnfurlEntity("urb://~zod/x", "T", null, 1))
            assertEquals(UrbUnfurlEntity("urb://~zod/x", "T", null, 1), db.urbUnfurls().get("urb://~zod/x"))
        } finally {
            db.close()
        }
    }
}
