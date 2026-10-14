package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.nisfeb.talon.notify.NoticeRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The non-chat notice store: ordering, read-state, the live-domain query, pruning, and the 55->56 migration. */
class NoticeStoreTest {
    private fun tmpDb(): Pair<AppDatabase, File> {
        val dir = createTempDirectory(prefix = "talon-notice-").toFile()
        // No destructive fallback + the shared migrations: a CREATE TABLE that
        // does not match NoticeEntity fails on open rather than wiping.
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*SHARED_MIGRATIONS)
            .build()
        return db to dir
    }

    private fun n(id: String, domain: String = "assistant", ts: Long = 1, read: Boolean = false) =
        NoticeEntity(id, domain, "k", "title", "body", ts, read, "tgt:$id")

    @Test
    fun `records read back newest first`() = runBlocking<Unit> {
        val (db, dir) = tmpDb()
        try {
            val d = db.notices()
            d.put(n("a", ts = 100)); d.put(n("b", ts = 300)); d.put(n("c", ts = 200))
            assertEquals(listOf("b", "c", "a"), d.stream().first().map { it.id })
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test
    fun `read and mark-all drop the unread count`() = runBlocking<Unit> {
        val (db, dir) = tmpDb()
        try {
            val d = db.notices()
            d.put(n("a")); d.put(n("b"))
            assertEquals(2, d.unreadCount().first())
            d.markRead("a")
            assertEquals(1, d.unreadCount().first())
            d.markAllRead(null)
            assertEquals(0, d.unreadCount().first())
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test
    fun `mark-all for one domain leaves the others unread`() = runBlocking<Unit> {
        val (db, dir) = tmpDb()
        try {
            val d = db.notices()
            d.put(n("a", domain = "assistant")); d.put(n("b", domain = "account"))
            d.markAllRead("assistant")
            assertEquals(1, d.unreadCount().first(), "the account notice is still unread")
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test
    fun `domains lists each present domain once`() = runBlocking<Unit> {
        val (db, dir) = tmpDb()
        try {
            val d = db.notices()
            d.put(n("a", domain = "assistant")); d.put(n("b", domain = "assistant")); d.put(n("c", domain = "account"))
            assertEquals(setOf("assistant", "account"), d.domains().first().toSet())
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test
    fun `prune keeps only the newest beyond the count`() = runBlocking<Unit> {
        val (db, dir) = tmpDb()
        try {
            val d = db.notices()
            for (i in 1..5) d.put(n("n$i", ts = i.toLong()))
            d.pruneBeyond(3)
            assertEquals(listOf("n5", "n4", "n3"), d.stream().first().map { it.id })
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test
    fun `prune drops anything older than the cutoff`() = runBlocking<Unit> {
        val (db, dir) = tmpDb()
        try {
            val d = db.notices()
            d.put(n("old", ts = 1)); d.put(n("new", ts = 1_000_000))
            d.pruneOlderThan(500_000)
            assertEquals(listOf("new"), d.stream().first().map { it.id })
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test
    fun `the repo records on its scope and prunes past the age window`() = runBlocking<Unit> {
        val (db, dir) = tmpDb()
        val scope = CoroutineScope(SupervisorJob())
        try {
            var clock = 1_700_000_000_000L
            val repo = NoticeRepo(db.notices(), scope) { clock }
            repo.record("old", "assistant", "k", "t", "b", "tgt")
            await { db.notices().stream().first().any { it.id == "old" } }
            clock += NoticeRepo.MAX_AGE_MS + 1
            repo.record("new", "assistant", "k", "t", "b", "tgt")
            await { db.notices().stream().first().map { it.id } == listOf("new") }
            assertEquals(listOf("new"), db.notices().stream().first().map { it.id }, "the age-stale old notice was pruned")
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); db.close(); dir.deleteRecursively() }
    }

    // The migration must recreate a table Room accepts, or the fallback wipes
    // every local table (notes, loops, assistant history). Seed a v55 db (no
    // notice table), reopen, and let MIGRATION_55_56 run; a wrong NOTICE_SQL
    // throws here instead of silently dropping data.
    @Test
    fun `migration from 55 recreates a notice table Room accepts`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-notice-mig-").toFile()
        val path = File(dir, "t.db").absolutePath
        fun db() = Room.databaseBuilder<AppDatabase>(path)
            .setDriver(BundledSQLiteDriver()).addMigrations(*SHARED_MIGRATIONS).build()
        try {
            db().also { it.notices().put(n("x")); it.close() }
            BundledSQLiteDriver().open(path).also { c ->
                c.execSQL("DROP TABLE notice")
                c.execSQL("PRAGMA user_version = 55")
                c.close()
            }
            val db = db()
            try {
                db.notices().put(n("y"))
                assertEquals(listOf("y"), db.notices().stream().first().map { it.id })
            } finally { db.close() }
        } finally { dir.deleteRecursively() }
    }

    private fun await(check: suspend () -> Boolean) = runBlocking {
        val until = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(25)
    }
}
