package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Search matches the words a message shows, not its story JSON: a
 * mention, a link, a line break or a picture no longer makes a message
 * a hit for "ship", "link", "break" or "image".
 */
class MessageSearchTextTest {
    private val dir = createTempDirectory(prefix = "talon-searchtext-").toFile()
    private val path = File(dir, "talon.db").absolutePath

    // No destructive fallback: a migration that does not match the entity fails here.
    private fun db() = Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*SHARED_MIGRATIONS)
        .build()

    @AfterTest
    fun cleanUp() { dir.deleteRecursively() }

    /** A mention, a link, a line break and a picture around two words. */
    private val story = """[{"inline":[{"ship":"~nec"}," meet ",{"link":{"href":"https://x.test","content":"here"}},{"break":null},"tomorrow"]},""" +
        """{"block":{"image":{"src":"https://x.test/a.png","alt":"","height":0,"width":0}}}]"""

    private suspend fun AppDatabase.find(word: String) = messages().search(escapeLikeNeedle(word)).first().map { it.id }

    @Test
    fun `the words shown are found, the JSON around them is not`() = runBlocking<Unit> {
        val db = db()
        try {
            db.messages().upsert(MessageEntity("~bus", "1", "~bus", 1, story, "/chat", title = "Plans"))
            for (word in listOf("meet", "here", "tomorrow", "~nec", "plans")) assertEquals(listOf("1"), db.find(word), word)
            for (word in listOf("ship", "link", "break", "image", "inline", "href")) assertEquals(emptyList(), db.find(word), word)
        } finally {
            db.close()
        }
    }

    // A link's address and a mention's @p were not found: the words
    // carried the label (cut short, or named otherwise) and the reader's
    // name for the ship (review A5-14).
    @Test
    fun `a link's address and a mention's at-p are found`() = runBlocking<Unit> {
        val long = "https://example.test/a/very/long/path/to/a/page"
        val story = """[{"inline":[{"ship":"~nec"}," see ",{"link":{"href":"https://x.test/plan","content":"the plan"}}," and ",{"link":{"href":"$long","content":"$long"}}]}]"""
        val db = db()
        val names = io.nisfeb.talon.ui.ShipNames.resolve
        val generation = io.nisfeb.talon.ui.ShipNames.generation.value
        io.nisfeb.talon.ui.ShipNames.setResolver(generation - 1) { if (it == "~nec") "Nec" else names(it) }
        try {
            db.messages().upsert(MessageEntity("~bus", "1", "~bus", 1, story, "/chat"))
            for (word in listOf("~nec", "https://x.test/plan", "x.test/plan", long, "Nec", "the plan")) assertEquals(listOf("1"), db.find(word), word)
        } finally {
            io.nisfeb.talon.ui.ShipNames.setResolver(generation) { names(it) }
            db.close()
        }
    }

    /** The search text of row [id] set as an older Talon left it, straight in the file. */
    private fun storedAsBefore(id: String, text: String) = BundledSQLiteDriver().open(path).also { c ->
        c.execSQL("UPDATE messages SET searchText = '$text' WHERE id = '$id'")
        c.close()
    }

    private fun started(db: AppDatabase, block: suspend () -> Unit) = runBlocking<Unit> {
        val ship = io.nisfeb.talon.urbit.FakeShip("~zod")
        val repo = io.nisfeb.talon.urbit.TlonChatRepo(db)
        repo.start(io.nisfeb.talon.urbit.UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") })
        try { kotlinx.coroutines.withTimeout(20_000) { block() } } finally { repo.stopAndJoinForTest() }
    }

    @Test
    fun `rows from before the extras get them at the next start, once`() = runBlocking<Unit> {
        val link = """[{"inline":[{"link":{"href":"https://x.test/plan","content":"the plan"}}]}]"""
        db().also {
            it.messages().upsert(MessageEntity("~bus", "1", "~bus", 1, link, "/chat"))
            it.messages().upsert(MessageEntity("~bus", "2", "~bus", 2, """[{"inline":["plain words"]}]""", "/chat"))
            it.close()
        }
        storedAsBefore("1", "the plan")
        var db = db()
        try {
            assertEquals(emptyList(), db.find("https://x.test/plan"))
            started(db) { while (db.find("https://x.test/plan") != listOf("1")) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("2"), db.find("plain"), "a row with no link or mention keeps what it had")
        } finally {
            db.close()
        }
        storedAsBefore("1", "the plan")
        db = db()
        try {
            started(db) { kotlinx.coroutines.delay(500) }
            assertEquals(emptyList(), db.find("https://x.test/plan"), "once: the second start leaves it")
        } finally {
            db.close()
        }
    }

    @Test
    fun `rows from before the column keep their data and get their words at start`() = runBlocking<Unit> {
        db().also { it.messages().upsert(MessageEntity("~bus", "1", "~bus", 1, story, "/chat")); it.close() }
        // Wind the file back to 48: no column, the row as it was.
        BundledSQLiteDriver().open(path).also { c ->
            c.execSQL("ALTER TABLE messages DROP COLUMN searchText")
            c.execSQL("ALTER TABLE assistant_history DROP COLUMN log") // added at 53
            c.execSQL("ALTER TABLE loop DROP COLUMN askBeforeSharing") // added at 58
            c.execSQL("PRAGMA user_version = 48")
            c.close()
        }
        val db = db()
        try {
            assertNull(db.messages().getOne("~bus", "1")?.searchText, "migrated rows start without it")
            assertEquals(listOf("1"), db.find("tomorrow"), "found the old way meanwhile")
            assertEquals(1, db.messages().fillSearchText(500))
            assertEquals(0, db.messages().fillSearchText(500), "each row once")
            assertEquals(emptyList(), db.find("ship"))
            assertEquals(listOf("1"), db.find("tomorrow"))
        } finally {
            db.close()
        }
    }
}
