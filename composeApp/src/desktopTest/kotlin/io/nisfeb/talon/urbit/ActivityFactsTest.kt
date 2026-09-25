package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ThreadUnreadEntity
import io.nisfeb.talon.data.UnreadEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The %activity facts that clear badges: a read done on another client,
 * and a source the ship stopped tracking. A thread's fact is the
 * thread's alone; taken for its channel's, it cleared or kept the
 * wrong badge.
 */
class ActivityFactsTest {
    private val dir = createTempDirectory(prefix = "talon-activity-facts-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val repo = TlonChatRepo(db)
    private val nest = "chat/~host/general"

    @AfterTest
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun fact(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val caughtUp = """{"count":0,"notify-count":0,"recency":1777000000000,"notify":false,"unread":null}"""
    private val threadSource = """{"thread":{"key":{"id":"~bus/170.141.184","time":"170.141.184"},"channel":"$nest","group":"~host/group"}}"""

    private fun seed() = runBlocking {
        db.unreads().upsert(UnreadEntity("~sampel", 7, 2, 1L, null))
        db.unreads().upsert(UnreadEntity(nest, 3, 0, 1L, null))
        db.threadUnreads().upsert(ThreadUnreadEntity(nest, "170141184", 4, 1, 1L))
    }

    @Test
    fun `a read on another client clears that chat's badge and no other`() = runBlocking {
        seed()
        repo.applyActivityUpdate(fact("""{"read":{"source":{"dm":{"ship":"~sampel"}},"activity":$caughtUp}}"""))
        assertEquals(0, db.unreads().getOne("~sampel")?.count)
        assertEquals(3, db.unreads().getOne(nest)?.count)
        assertEquals(4, db.threadUnreads().getOne(nest, "170141184")?.count)
    }

    @Test
    fun `a thread's read clears the thread, not its channel`() = runBlocking {
        seed()
        repo.applyActivityUpdate(fact("""{"read":{"source":$threadSource,"activity":$caughtUp}}"""))
        assertEquals(0, db.threadUnreads().getOne(nest, "170141184")?.count)
        assertEquals(3, db.unreads().getOne(nest)?.count, "the channel's own badge stands")
    }

    @Test
    fun `a source the ship dropped takes its badge with it`() = runBlocking {
        seed()
        repo.applyActivityUpdate(fact("""{"del":$threadSource}"""))
        assertNull(db.threadUnreads().getOne(nest, "170141184"))
        assertEquals(3, db.unreads().getOne(nest)?.count)
        repo.applyActivityUpdate(fact("""{"del":{"channel":{"nest":"$nest","group":"~host/group"}}}"""))
        assertNull(db.unreads().getOne(nest))
        assertEquals(7, db.unreads().getOne("~sampel")?.count)
    }
}
