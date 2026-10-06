package io.nisfeb.talon.data


import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.execSQL

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
        ThreadUnreadEntity::class,
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
        MessageMediaEntity::class,
        RailItemPrefEntity::class,
        DmInviteEntity::class,
        AssistantHistoryEntity::class,
        AssistantConversationEntity::class,
        LoopEntity::class,
        LoopRunEntity::class,
        NotesNotebookEntity::class,
        NotesFolderEntity::class,
        NotesNoteEntity::class,
        MailRowEntity::class,
        CalendarCacheEntity::class,
        OrreryAccountEntity::class,
        OrreryNoticedEntity::class,
        OrrerySentEntity::class,
        CometDomeEntity::class,
        UrbUnfurlEntity::class,
        FollowedThreadEntity::class,
        OrreryCacheEntity::class,
    ],
    version = 55,
    exportSchema = false,
)
@ConstructedBy(AppDatabaseConstructor::class)
expect abstract class AppDatabase : RoomDatabase {
    abstract fun messages(): MessageDao
    abstract fun reactions(): ReactionDao
    abstract fun unreads(): UnreadDao
    abstract fun threadUnreads(): ThreadUnreadDao
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
    abstract fun messageMedia(): MessageMediaDao
    abstract fun railItemPrefs(): RailItemPrefDao
    abstract fun dmInvites(): DmInviteDao
    abstract fun assistantHistory(): AssistantHistoryDao
    abstract fun assistantConversations(): AssistantConversationDao
    abstract fun loops(): LoopDao
    abstract fun loopRuns(): LoopRunDao
    abstract fun notes(): NotesDao
    abstract fun mailRows(): MailRowDao
    abstract fun calendarCache(): CalendarCacheDao
    abstract fun orreryAccounts(): OrreryAccountDao
    abstract fun orreryNoticed(): OrreryNoticedDao
    abstract fun orrerySent(): OrrerySentDao
    abstract fun cometDomes(): CometDomeDao
    abstract fun urbUnfurls(): UrbUnfurlDao
    abstract fun followedThreads(): FollowedThreadDao
    abstract fun orreryCache(): OrreryCacheDao
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

/**
 * 51 to 52: watchwords were taken out of Talon, and their three tables
 * with them. Named here so every platform drops the same ones; without a
 * step the fallback would have dropped every table instead.
 */
internal val WATCHWORDS_DROP_SQL = listOf(
    "DROP TABLE IF EXISTS `watchword_hits`",
    "DROP TABLE IF EXISTS `watchword_chat_excludes`",
    "DROP TABLE IF EXISTS `watchwords`",
)

/** See [WATCHWORDS_DROP_SQL]. Android runs the same statements its own way. */
val WATCHWORDS_DROP_MIGRATION = object : androidx.room.migration.Migration(51, 52) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        WATCHWORDS_DROP_SQL.forEach { connection.execSQL(it) }
    }
}

/**
 * 52 to 53: a turn keeps what its run did ([AssistantHistoryEntity.log]).
 * One column; the fallback would have dropped every table for it.
 */
internal const val ASSISTANT_LOG_SQL = "ALTER TABLE `assistant_history` ADD COLUMN `log` TEXT NOT NULL DEFAULT ''"

/** See [ASSISTANT_LOG_SQL]. Android runs the same statement its own way. */
val ASSISTANT_LOG_MIGRATION = object : androidx.room.migration.Migration(52, 53) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL(ASSISTANT_LOG_SQL)
    }
}

/**
 * Every migration the desktop and iOS databases take, so neither can miss
 * one the other has: this PR added four, to both by hand. Android runs
 * the same changes through its own Migration objects.
 */
val SHARED_MIGRATIONS = arrayOf(
    MAIL_ROWS_MIGRATION, CALENDAR_ROWS_MIGRATION, ORRERY_ACCOUNTS_MIGRATION, ORRERY_SENT_MIGRATION,
    COMET_DOMES_MIGRATION, ORRERY_HANDOFF_MIGRATION, ORRERY_SHIP_WORK_MIGRATION, MESSAGE_SEARCH_TEXT_MIGRATION,
    URB_UNFURLS_MIGRATION, MESSAGE_STATUS_INDEX_MIGRATION, WATCHWORDS_DROP_MIGRATION, ASSISTANT_LOG_MIGRATION,
    FOLLOWED_THREADS_MIGRATION, ORRERY_CACHE_MIGRATION,
)
