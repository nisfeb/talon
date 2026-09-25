package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The boot-time dedupe run against a real database: it deletes rows,
 * so what it keeps is the point. [DottedIdDedupeTest] has the planner.
 */
class DottedIdDedupeRunTest {
    private val dir = createTempDirectory(prefix = "talon-dedupe-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val repo = TlonChatRepo(db)

    @AfterTest
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private val image = """[{"inline":[{"link":{"href":"https://x.com/a.jpg","content":"img"}}]}]"""

    @Test
    fun `a lone dotted row moves with its media, a twin keeps its own words, reactions merge`() = runBlocking {
        db.messages().upsertWithMedia(db.messageMedia(), MessageEntity("~bus", "~bus/170.141.184", "~bus", 1_000, image, "/chat"))
        db.messages().upsert(MessageEntity("~bus", "~bus/170.141.185", "~bus", 2_000, """[{"inline":["stale"]}]""", "/chat"))
        db.messages().upsert(MessageEntity("~bus", "~bus/170141185", "~bus", 2_000, """[{"inline":["kept"]}]""", "/chat"))
        db.reactions().upsert(ReactionEntity("~bus", "~bus/170.141.184", "~nec", "👍"))

        repo.dedupeDottedIds()

        assertEquals(image, db.messages().getOne("~bus", "~bus/170141184")?.contentJson)
        assertNull(db.messages().getOne("~bus", "~bus/170.141.184"))
        assertEquals(1, db.messageMedia().totalCount(), "the image moved, not copied or lost")
        assertEquals("""[{"inline":["kept"]}]""", db.messages().getOne("~bus", "~bus/170141185")?.contentJson)
        assertNull(db.messages().getOne("~bus", "~bus/170.141.185"))
        assertEquals("👍", db.reactions().get("~bus", "~bus/170141184", "~nec")?.emoji)
        assertNull(db.reactions().get("~bus", "~bus/170.141.184", "~nec"))

        repo.dedupeDottedIds()
        assertEquals(image, db.messages().getOne("~bus", "~bus/170141184")?.contentJson, "a second boot changes nothing")
    }
}
