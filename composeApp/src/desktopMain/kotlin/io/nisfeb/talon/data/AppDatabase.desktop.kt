package io.nisfeb.talon.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

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
 */
fun createAppDatabase(): AppDatabase {
    val dbFile = File(io.nisfeb.talon.util.AppDirs.userData, "talon-port.db")
    return Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
