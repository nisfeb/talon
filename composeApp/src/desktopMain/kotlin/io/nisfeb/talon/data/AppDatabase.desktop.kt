package io.nisfeb.talon.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    actual abstract fun messageMedia(): MessageMediaDao
    actual abstract fun railItemPrefs(): RailItemPrefDao
}

/**
 * Open the desktop AppDatabase for [shipKey]. Per-ship file (matching
 * production Android's `talon-${ship}.db` convention) so DM lists,
 * unread counts, etc. don't bleed across ships when the user switches.
 * `shipKey` is the patp (e.g. `~zod`) when signed in, or
 * `__loggedout__` for the pre-login graph.
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
fun createAppDatabase(shipKey: String): AppDatabase {
    val dbFile = File(
        io.nisfeb.talon.util.AppDirs.userData,
        "talon-port-${sanitizeShipKey(shipKey)}.db",
    )
    sweepOldOrphans(dbFile.parentFile)
    return try {
        buildAndPing(dbFile)
    } catch (timeout: DatabaseOpenTimeoutException) {
        // Don't wipe on timeout — the file might be perfectly fine,
        // the disk is just unreachable. Surface to caller (Main.kt
        // shows a dialog and exits) rather than corrupting good data.
        throw timeout
    } catch (err: Throwable) {
        Log.w(TAG, "DB open failed (${err.message}); wiping and retrying once")
        wipeDb(dbFile)
        buildAndPing(dbFile)
    }
}

// Patps are filesystem-safe on Linux/macOS but the leading `~` is
// awkward enough to strip; replace anything outside [a-zA-Z0-9_-]
// to be conservative across platforms (Windows reserved chars,
// shell special chars).
//
// `internal` so the desktopTest source set can call it directly
// instead of going through a full Room db open.
internal fun sanitizeShipKey(shipKey: String): String =
    shipKey.map { c ->
        if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
    }.joinToString("")

/**
 * Thrown when the SQLite open exceeds [SMOKE_TEST_TIMEOUT_MS]. Distinct
 * from corruption — Main.kt translates this to a "your data directory
 * is unavailable" dialog rather than wiping the (possibly fine) DB.
 * Wedged-disk causes: unreachable NFS, stalled FUSE, multi-second
 * Windows AV scan on a freshly-created file.
 */
class DatabaseOpenTimeoutException internal constructor(
    val dbPath: String,
    cause: Throwable? = null,
) : RuntimeException("DB open exceeded ${SMOKE_TEST_TIMEOUT_MS}ms: $dbPath", cause)

private const val SMOKE_TEST_TIMEOUT_MS = 15_000L

private fun buildAndPing(dbFile: File): AppDatabase {
    val db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    try {
        // Smoke test under a hard timeout. A wedged disk would
        // otherwise hang main thread forever — no window, no error.
        // 15s is generous for a healthy disk's open; anything past
        // it surfaces as a real failure the user can act on.
        runBlocking {
            withTimeout(SMOKE_TEST_TIMEOUT_MS) {
                db.unreads().getOne("__smoke_test__")
            }
        }
        return db
    } catch (t: TimeoutCancellationException) {
        runCatching { db.close() }
        throw DatabaseOpenTimeoutException(dbFile.absolutePath, t)
    } catch (t: Throwable) {
        runCatching { db.close() }
        throw t
    }
}

private fun wipeDb(dbFile: File) {
    // SQLite journal files: WAL (-shm + -wal) when WAL mode is on,
    // -journal in rollback mode. We don't know the mode of a corrupt
    // file so try all three. A residual journal that survives the
    // wipe can re-corrupt the next-built file.
    val siblings = listOf(
        dbFile,
        File(dbFile.parentFile, "${dbFile.name}-shm"),
        File(dbFile.parentFile, "${dbFile.name}-wal"),
        File(dbFile.parentFile, "${dbFile.name}-journal"),
    )
    for (target in siblings) {
        if (deleteWithRetries(target)) continue
        // On Windows the bundled SQLite driver's native handle close
        // can lag the JVM File API close, so the delete fails even
        // after db.close(). Falling back to a rename moves the
        // corrupt file aside so the rebuild gets a fresh slot
        // regardless. The orphan stays on disk for postmortem.
        // nanoTime() included so two recovery cycles within the same
        // millisecond don't collide on the orphan name.
        val orphan = File(
            target.parentFile,
            "${target.name}.corrupt-${System.currentTimeMillis()}-${System.nanoTime()}",
        )
        if (!runCatching { target.renameTo(orphan) }.getOrDefault(false)) {
            // renameTo can return false silently (cross-device,
            // locked) without throwing. If we got here, neither
            // delete nor rename worked — log so the rebuild's
            // eventual failure is traceable.
            Log.w(
                TAG,
                "wipeDb: failed to delete or rename ${target.name}; " +
                    "next open may inherit corruption",
            )
        }
    }
}

/**
 * Delete `*.corrupt-*` files in the data dir older than 7 days.
 * The wipe-and-retry path on Windows can leave renamed orphans
 * behind (when delete keeps failing); without this sweep they
 * accumulate forever in userData. 7 days is enough for postmortem
 * if needed and short enough to keep the dir tidy.
 */
private fun sweepOldOrphans(dir: File?) {
    if (dir == null || !dir.isDirectory) return
    val cutoff = System.currentTimeMillis() - 7L * 24 * 3600_000L
    runCatching {
        dir.listFiles { f -> f.name.contains(".corrupt-") }
            ?.filter { it.lastModified() in 1..cutoff }
            ?.forEach { runCatching { it.delete() } }
    }
}

/**
 * Three attempts with a 50ms sleep between, so a Windows handle
 * release window can pass before we declare it stuck. Returns true
 * on success, false on persistent failure.
 */
private fun deleteWithRetries(target: File): Boolean {
    if (!target.exists()) return true
    repeat(3) { attempt ->
        if (runCatching { target.delete() }.getOrDefault(false)) return true
        if (!target.exists()) return true
        if (attempt < 2) Thread.sleep(50)
    }
    return false
}
