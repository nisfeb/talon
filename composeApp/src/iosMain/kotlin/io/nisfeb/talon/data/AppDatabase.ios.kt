package io.nisfeb.talon.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Room's KSP processor generates the DAO impls, AppDatabase_Impl, and the
// AppDatabaseConstructor actual for each iOS target — we only supply the
// abstract actual + the per-target createAppDatabase factory (mirroring
// the desktop shape: one SQLite file per ship under Application Support).

actual abstract class AppDatabase : RoomDatabase() {
    actual abstract fun messages(): MessageDao
    actual abstract fun reactions(): ReactionDao
    actual abstract fun unreads(): UnreadDao
    actual abstract fun threadUnreads(): ThreadUnreadDao
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
    actual abstract fun messageMedia(): MessageMediaDao
    actual abstract fun railItemPrefs(): RailItemPrefDao
    actual abstract fun dmInvites(): DmInviteDao
    actual abstract fun assistantHistory(): AssistantHistoryDao
    actual abstract fun assistantConversations(): AssistantConversationDao
    actual abstract fun loops(): LoopDao
    actual abstract fun loopRuns(): LoopRunDao
    actual abstract fun notes(): NotesDao
    actual abstract fun mailRows(): MailRowDao
    actual abstract fun calendarCache(): CalendarCacheDao
    actual abstract fun orreryAccounts(): OrreryAccountDao
    actual abstract fun orreryNoticed(): OrreryNoticedDao
    actual abstract fun orrerySent(): OrrerySentDao
    actual abstract fun cometDomes(): CometDomeDao
    actual abstract fun urbUnfurls(): UrbUnfurlDao
    actual abstract fun followedThreads(): FollowedThreadDao
    actual abstract fun orreryCache(): OrreryCacheDao
    actual abstract fun notices(): NoticeDao
    actual abstract fun settingsOutbox(): SettingsOutboxDao
    actual abstract fun marks(): MarkDao
}

internal fun sanitizeShipKey(shipKey: String): String =
    shipKey.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }
        .joinToString("")

private fun documentsDir(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .first() as String

/**
 * Application Support, created if needed with NSFileProtectionComplete
 * so everything in it inherits the protection. Documents is unsafe for
 * data like this: Info.plist sets UIFileSharingEnabled, which publishes
 * that directory to the Files app and over AFC (see IosFiles). Shared
 * with IosShipDataEraser so erase looks where the db actually lives.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun appSupportDir(): String {
    val dir = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory, NSUserDomainMask, true,
    ).first() as String
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(dir)) {
        fm.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
            error = null,
        )
    }
    return dir
}

private val UNTIL_FIRST_UNLOCK = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

/**
 * The databases' own folder in Application Support, protected until the
 * first unlock after a restart rather than whenever the phone is locked.
 * A call keeps Talon running on a locked phone, and under
 * NSFileProtectionComplete every read and write failed about ten seconds
 * after the lock (B4-3, sneagan's call 2026-10-18). SQLite makes -wal
 * and -shm afresh on each open, and a new file takes its folder's
 * protection, so the folder carries it. Credentials stay in the files
 * IosFiles keeps at NSFileProtectionComplete.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun databaseDir(): String {
    val dir = appSupportDir() + "/db"
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(dir)) {
        fm.createDirectoryAtPath(path = dir, withIntermediateDirectories = true, attributes = UNTIL_FIRST_UNLOCK, error = null)
    } else {
        fm.setAttributes(UNTIL_FIRST_UNLOCK, ofItemAtPath = dir, error = null)
    }
    return dir
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun createAppDatabase(shipKey: String): AppDatabase {
    val name = "talon-${sanitizeShipKey(shipKey)}.db"
    val dir = databaseDir()
    // Earlier builds kept the db in Documents, then in Application
    // Support itself; bring it across once, -wal/-shm included, or an
    // upgrade would open an empty db and leave the real one behind. A
    // moved file keeps its own protection, so it is set again here.
    val fm = NSFileManager.defaultManager
    for (from in listOf(documentsDir(), appSupportDir())) {
        for (suffix in listOf("", "-wal", "-shm")) {
            val old = "$from/$name$suffix"
            val new = "$dir/$name$suffix"
            if (fm.fileExistsAtPath(old) && !fm.fileExistsAtPath(new)) {
                runCatching { fm.moveItemAtPath(srcPath = old, toPath = new, error = null) }
            }
        }
    }
    for (suffix in listOf("", "-wal", "-shm")) {
        val file = "$dir/$name$suffix"
        if (fm.fileExistsAtPath(file)) fm.setAttributes(UNTIL_FIRST_UNLOCK, ofItemAtPath = file, error = null)
    }
    val path = "$dir/$name"
    // Explicit factory: the default path resolves @ConstructedBy via
    // K/N findAssociatedObject, which returns null in optimized Release
    // binaries — "Cannot find the associated RoomDatabaseConstructor"
    // at first DB open (caught by the CI simulator launch test).
    return Room.databaseBuilder<AppDatabase>(
        name = path,
        factory = { AppDatabaseConstructor.initialize() },
    )
        .setDriver(BundledSQLiteDriver())
        // Room on JVM/Android falls back to its own background
        // executor; Kotlin/Native has no such default, so a suspend DAO
        // call made from the composition scope ran on the main thread.
        // Every write that goes through a background scope (message
        // sync, settings) was fine, and the one path that doesn't —
        // creating a folder from the tab strip — was not.
        .setQueryCoroutineContext(io.nisfeb.talon.util.ioDispatcher)
        // Migrations from 41 on; older databases still rebuild. See AppDatabase.desktop.kt.
        .addMigrations(*SHARED_MIGRATIONS)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
