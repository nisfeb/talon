package io.nisfeb.talon.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * KMP-aware copy of the production AppDatabase. The `@Database`
 * annotation lives on the expect declaration so Room's KSP processor
 * (which runs per-target) sees the same entity list + version on both
 * Android and desktop. Each target supplies an `actual abstract class
 * AppDatabase` via the `AppDatabaseConstructor` indirection — Room 2.7
 * generates the platform-specific Impl from there.
 *
 * The companion `createAppDatabase(...)` factory functions for each
 * target (see androidMain/desktopMain) are responsible for choosing the
 * file path, attaching migrations, and wiring the SQLite driver
 * appropriate for the target.
 *
 * Schema mirrors the production app/AppDatabase exactly so a migration
 * tool could read either DB without translation. The version stays in
 * lockstep with production for the same reason. composeApp uses a
 * different file name (`talon-port.db` on desktop) to keep the two
 * databases separate while the port is in flight.
 */
@Database(
    entities = [
        MessageEntity::class,
        ReactionEntity::class,
        UnreadEntity::class,
        ContactEntity::class,
        ClubEntity::class,
        GroupEntity::class,
        ChannelGroupEntity::class,
        FolderEntity::class,
        FolderMemberEntity::class,
        BookmarkEntity::class,
        NotifyPreferenceEntity::class,
        GroupOrderEntity::class,
        ReactionUsageEntity::class,
        MessageEmbeddingEntity::class,
        BookmarkFolderEntity::class,
        BookmarkFolderMemberEntity::class,
        WatchwordEntity::class,
        WatchwordHitEntity::class,
        WatchwordChatExcludeEntity::class,
        DailyDigestEntity::class,
    ],
    version = 29,
    exportSchema = false,
)
@ConstructedBy(AppDatabaseConstructor::class)
expect abstract class AppDatabase : RoomDatabase {
    abstract fun messages(): MessageDao
    abstract fun reactions(): ReactionDao
    abstract fun unreads(): UnreadDao
    abstract fun contacts(): ContactDao
    abstract fun clubs(): ClubDao
    abstract fun groups(): GroupDao
    abstract fun folders(): FolderDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun notifyPrefs(): NotifyPreferenceDao
    abstract fun groupOrders(): GroupOrderDao
    abstract fun reactionUsage(): ReactionUsageDao
    abstract fun embeddings(): EmbeddingDao
    abstract fun bookmarkFolders(): BookmarkFolderDao
    abstract fun watchwords(): WatchwordsDao
    abstract fun dailyDigests(): DailyDigestDao
}

/**
 * Room 2.7 KMP requires every `expect`-declared database to be paired
 * with an `expect object` constructor that the compiler-generated
 * `AppDatabase_Impl` plugs into. We deliberately suppress
 * NO_ACTUAL_FOR_EXPECT here because Room's KSP processor synthesises
 * the actual on each target — there's no hand-written counterpart to
 * point the compiler at.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
