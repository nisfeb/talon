package io.nisfeb.talon.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Room's KSP processor generates the DAO impls, AppDatabase_Impl, and the
// AppDatabaseConstructor actual for each iOS target — we only supply the
// abstract actual + the per-target createAppDatabase factory (mirroring
// the desktop shape: one SQLite file per ship under Documents).

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
    actual abstract fun watchwords(): WatchwordsDao
    actual abstract fun dailyDigests(): DailyDigestDao
    actual abstract fun messageMedia(): MessageMediaDao
    actual abstract fun railItemPrefs(): RailItemPrefDao
    actual abstract fun dmInvites(): DmInviteDao
    actual abstract fun assistantHistory(): AssistantHistoryDao
    actual abstract fun assistantConversations(): AssistantConversationDao
    actual abstract fun loops(): LoopDao
    actual abstract fun loopRuns(): LoopRunDao
    actual abstract fun notes(): NotesDao
}

internal fun sanitizeShipKey(shipKey: String): String =
    shipKey.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }
        .joinToString("")

private fun documentsDir(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .first() as String

fun createAppDatabase(shipKey: String): AppDatabase {
    val path = "${documentsDir()}/talon-${sanitizeShipKey(shipKey)}.db"
    return Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
