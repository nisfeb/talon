package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Watchwords were taken out with their tables. The step from 51 drops
 * those three and nothing else: with no step, the fallback would have
 * dropped every table, and every device would have read all its
 * messages from the ship again.
 */
class WatchwordsDropMigrationTest {
    @Test
    fun `an upgrade drops the watchword tables and keeps the rest`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-watchwords-drop-").toFile()
        try {
            val path = File(dir, "talon.db").absolutePath
            // No destructive fallback: an open that succeeds ran the step.
            fun open() = Room.databaseBuilder<AppDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(WATCHWORDS_DROP_MIGRATION)
                .build()
            open().apply {
                messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["kept"]}]""", "/chat"))
                close()
            }
            // As a 1.8.1 install left it: version 51, its watchwords in place.
            BundledSQLiteDriver().open(path).apply {
                execSQL("CREATE TABLE watchwords (id INTEGER PRIMARY KEY NOT NULL, term TEXT NOT NULL)")
                execSQL("INSERT INTO watchwords VALUES (1, 'mars')")
                execSQL("CREATE TABLE watchword_hits (id INTEGER PRIMARY KEY NOT NULL, term TEXT NOT NULL)")
                execSQL("CREATE TABLE watchword_chat_excludes (whom TEXT PRIMARY KEY NOT NULL)")
                execSQL("PRAGMA user_version = 51")
                close()
            }
            open().apply {
                assertNotNull(messages().getOne("~bus", "~bus/170141184506"), "the messages stay")
                close()
            }
            val left = BundledSQLiteDriver().open(path).run {
                val st = prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'watchword%'")
                val names = buildList { while (st.step()) add(st.getText(0)) }
                st.close()
                close()
                names
            }
            assertEquals(emptyList(), left)
        } finally {
            dir.deleteRecursively()
        }
    }
}
