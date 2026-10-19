package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 59 adds the lattice key. The step from 58 makes the table and keeps the
 * rest: with no step, the fallback would have dropped every table. Room
 * checks the tables it opens against the entities, so a statement that
 * drifts from them fails the open here.
 */
class LatticeKeysMigrationTest {
    @Test
    fun `an upgrade adds the key table and keeps orrery's key`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-lattice-keys-").toFile()
        try {
            val path = File(dir, "talon.db").absolutePath
            fun open() = Room.databaseBuilder<AppDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*SHARED_MIGRATIONS)
                .build()
            open().apply {
                orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
                close()
            }
            // As 1.8.30-rc14 left it: version 58, no table.
            BundledSQLiteDriver().open(path).apply {
                execSQL("DROP TABLE lattice_keys")
                execSQL("PRAGMA user_version = 58")
                close()
            }
            open().apply {
                assertEquals("k1.secret", orreryAccounts().get("~zod")?.token, "kept")
                latticeKeys().upsert(LatticeKeyEntity("~zod", "abc", "abc.secret"))
                assertEquals(LatticeKeyEntity("~zod", "abc", "abc.secret", revoked = false), latticeKeys().get("~zod"))
                latticeKeys().upsert(LatticeKeyEntity("~zod", "abc", "abc.secret", revoked = true))
                assertEquals(true, latticeKeys().get("~zod")?.revoked)
                close()
            }
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }
}
