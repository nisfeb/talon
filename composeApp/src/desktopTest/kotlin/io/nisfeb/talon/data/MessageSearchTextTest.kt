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
        .addMigrations(MESSAGE_SEARCH_TEXT_MIGRATION)
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

    @Test
    fun `rows from before the column keep their data and get their words at start`() = runBlocking<Unit> {
        db().also { it.messages().upsert(MessageEntity("~bus", "1", "~bus", 1, story, "/chat")); it.close() }
        // Wind the file back to 48: no column, the row as it was.
        BundledSQLiteDriver().open(path).also { c ->
            c.execSQL("ALTER TABLE messages DROP COLUMN searchText")
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
