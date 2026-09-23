package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 46 to 47 takes Talon's chat and calendar records away and makes the
 * accounts table again without its two cursors. That table holds the
 * install's orrery key, so the one thing this must not do is lose it.
 */
class OrreryHandoffMigrationTest {
    @Test
    fun `the key and the mail cursor survive, and the chat and calendar records go`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-handoff-").toFile()
        val path = File(dir, "talon.db").absolutePath
        // No destructive fallback: a migration that does not match the entity fails here.
        fun db() = Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(ORRERY_HANDOFF_MIGRATION)
            .build()
        try {
            db().also { it.orreryAccounts().get("~zod"); it.close() }
            // Wind the file back to 46, as a tester's phone has it.
            BundledSQLiteDriver().open(path).also { c ->
                c.execSQL("DROP TABLE orrery_accounts")
                c.execSQL(ORRERY_ACCOUNTS_SQL)
                c.execSQL(ORRERY_CHANNELS_SQL)
                c.execSQL("INSERT INTO orrery_accounts VALUES ('~zod', 'c1', 'c1.secret', 111, 222, 333)")
                c.execSQL("INSERT INTO orrery_channels VALUES ('chat/~host/general')")
                listOf("msg:~bus/1", "cal:default/e1", "occ:default/e1/5", "person:~bus", "mail:t1", "plan:x").forEach {
                    c.execSQL("INSERT INTO orrery_sent VALUES ('~zod', '$it', 'v', 1)")
                }
                c.execSQL("PRAGMA user_version = 46")
                c.close()
            }
            val db = db()
            try {
                assertEquals(OrreryAccountEntity("~zod", "c1", "c1.secret", mailCursor = 222), db.orreryAccounts().get("~zod"))
                assertEquals(
                    listOf("mail:t1", "person:~bus", "plan:x"),
                    db.orrerySent().under("~zod", "").map { it.key }.sorted(),
                )
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(path).also { c ->
                val left = c.prepare("SELECT name FROM sqlite_master WHERE name = 'orrery_channels'").use { it.step() }
                c.close()
                assertFalse(left, "the channels this install read are gone")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
