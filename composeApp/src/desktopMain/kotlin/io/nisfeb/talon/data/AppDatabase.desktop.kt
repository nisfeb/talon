package io.nisfeb.talon.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File

private const val TAG = "AppDatabase.desktop"

/**
 * Desktop actual for the KMP AppDatabase. Uses the Room 2.7 builder
 * (Room.databaseBuilder<AppDatabase>(name = ...)) wired to the bundled
 * SQLite driver. The bundled driver ships native binaries for Linux,
 * macOS, and Windows so no system-wide SQLite install is required.
 *
 * The desktop schema-evolution story is intentionally simple right now:
 * destructive fallback only. The Android migrations encoded in
 * AppDatabase.android.kt rely on SupportSQLiteDatabase, which the
 * SQLiteConnection-based desktop driver doesn't speak. Re-introducing
 * them on desktop is a Stage F follow-up — for now, a desktop user
 * upgrading hits the destructive path and re-syncs from the ship.
 */
actual abstract class AppDatabase : RoomDatabase() {
    actual abstract fun messages(): MessageDao
    actual abstract fun reactions(): ReactionDao
    actual abstract fun unreads(): UnreadDao
    actual abstract fun contacts(): ContactDao
    actual abstract fun clubs(): ClubDao
    actual abstract fun groups(): GroupDao
    actual abstract fun folders(): FolderDao
    actual abstract fun bookmarks(): BookmarkDao
    actual abstract fun notifyPrefs(): NotifyPreferenceDao
    actual abstract fun groupOrders(): GroupOrderDao
    actual abstract fun reactionUsage(): ReactionUsageDao
    actual abstract fun embeddings(): EmbeddingDao
    actual abstract fun bookmarkFolders(): BookmarkFolderDao
    actual abstract fun watchwords(): WatchwordsDao
    actual abstract fun dailyDigests(): DailyDigestDao
}

/**
 * Open the desktop AppDatabase. The file lives in the platform-
 * standard user-data dir (see [io.nisfeb.talon.util.AppDirs]) and is
 * named `talon-port.db` so it doesn't collide with production
 * Android's `talon.db`. The directory is created on demand.
 *
 * Corruption recovery: Room's build() is lazy, so a corrupt SQLite
 * file doesn't surface until the first DAO query fires inside
 * repo.start's drain loop. There it gets caught by an outer
 * runCatching and re-attempted forever — a backoff hammer with no
 * UI feedback. Instead, ping the database synchronously here. On
 * failure, wipe the file (and its WAL/SHM siblings) and rebuild
 * once. If the second build also fails, throw — at startup the
 * crash is loud and easy to debug, vs the silent backoff loop.
 */
fun createAppDatabase(): AppDatabase {
    val dbFile = File(io.nisfeb.talon.util.AppDirs.userData, "talon-port.db")
    return runCatching { buildAndPing(dbFile) }
        .getOrElse { err ->
            Log.w(TAG, "DB open failed (${err.message}); wiping and retrying once")
            wipeDb(dbFile)
            buildAndPing(dbFile)
        }
}

private fun buildAndPing(dbFile: File): AppDatabase {
    val db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    try {
        // Smoke test — getOne on a sentinel key opens the connection
        // and runs a SELECT. Corrupt or schema-incompatible files
        // throw here; we close + propagate so the outer wipe runs.
        runBlocking { db.unreads().getOne("__smoke_test__") }
        return db
    } catch (t: Throwable) {
        runCatching { db.close() }
        throw t
    }
}

private fun wipeDb(dbFile: File) {
    runCatching { dbFile.delete() }
    runCatching { File(dbFile.parentFile, "${dbFile.name}-shm").delete() }
    runCatching { File(dbFile.parentFile, "${dbFile.name}-wal").delete() }
}
