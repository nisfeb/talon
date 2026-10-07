package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A page read again (a chat opened, a catch-up) rewrote every row, each
 * row's media its own transaction, and woke every screen on the table
 * for nothing new. Only what differs is written now, in one go.
 */
class PageWriteTest {
    private val dir: File = Files.createTempDirectory("page-write-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()

    @AfterTest
    fun close() { db.close(); dir.deleteRecursively() }

    private fun image(n: Int) = """[{"inline":[{"link":{"href":"https://x.com/$n.jpg","content":"img"}}]}]"""
    private fun page(a: String = image(1)) = listOf(
        MessageEntity("~bus", "170141184001", "~bus", 1_000, a, "/chat"),
        MessageEntity("~bus", "170141184002", "~bus", 2_000, image(2), "/chat"),
    )

    @Test
    fun `a page read again writes nothing, a changed row is written with its media`() = runBlocking {
        db.messages().upsertAllWithMedia(db.messageMedia(), page())
        val media = db.messageMedia().totalCount()
        assertEquals(2, media)
        // Take the first row's media away: a rewrite would put it back.
        db.messageMedia().replaceForMessage("~bus", "170141184001", emptyList())
        db.messages().upsertAllWithMedia(db.messageMedia(), page())
        assertEquals(1, db.messageMedia().totalCount(), "unchanged rows were not written again")
        db.messages().upsertAllWithMedia(db.messageMedia(), page(a = image(3)))
        assertEquals(2, db.messageMedia().totalCount(), "the changed row and its media were")
        assertEquals(image(3), db.messages().getOne("~bus", "170141184001")?.contentJson)
    }
}
