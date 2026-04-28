package io.nisfeb.talon.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 27,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
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

    companion object {
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE folder_members ADD COLUMN kind TEXT NOT NULL DEFAULT 'whom'"
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Pinning is gone; reordering replaces it. Drop the
                // obsolete table so the schema matches the current
                // entities list.
                db.execSQL("DROP TABLE IF EXISTS pins")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Notebook (diary) and gallery (heap) posts carry a
                // meta with title + cover image. Stash them alongside
                // the content so list screens don't need to re-parse.
                db.execSQL("ALTER TABLE messages ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN image TEXT")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Channel-scoped pinned post (Tlon's order[0]).
                db.execSQL(
                    "ALTER TABLE channel_groups ADD COLUMN pinnedPostId TEXT"
                )
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Per-message sentence embeddings powering semantic
                // search. Composite PK matches `messages` (whom, id).
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_embeddings (
                        whom TEXT NOT NULL,
                        id TEXT NOT NULL,
                        vector BLOB NOT NULL,
                        dim INTEGER NOT NULL,
                        textHash INTEGER NOT NULL,
                        PRIMARY KEY (whom, id)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // streamChannelsForGroup(flag) was scanning the entire
                // channel_groups table on every GroupHomeScreen open
                // and on every channel_groups invalidation propagating
                // through contactMapFlow.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channel_groups_groupFlag " +
                        "ON channel_groups (groupFlag)"
                )
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchwords (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        term TEXT NOT NULL,
                        notify INTEGER NOT NULL,
                        createdMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_watchwords_term " +
                        "ON watchwords (term)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchword_hits (
                        term TEXT NOT NULL,
                        whom TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        sentMs INTEGER NOT NULL,
                        snippet TEXT NOT NULL,
                        PRIMARY KEY (term, whom, postId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_watchword_hits_term_sentMs " +
                        "ON watchword_hits (term, sentMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_watchword_hits_sentMs " +
                        "ON watchword_hits (sentMs)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchword_chat_excludes (
                        whom TEXT NOT NULL PRIMARY KEY
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Chat-open was scanning whom's slice and sorting by
                // sentMs every time — fine for warm reads but seconds
                // of stall during heavy refresh upserts. The composite
                // covers stream(whom), streamReplies, streamReplyCounts,
                // and the cursor lookups in one shot.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_whom_parentId_sentMs " +
                        "ON messages (whom, parentId, sentMs)"
                )
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Bookmark organization — folders + a join table
                // mirroring the conversation-folders pattern. Both
                // tables sync to %settings buckets `bookmark-folders`
                // / `bookmark-folder-members`.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmark_folders (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmark_folder_members (
                        folderId INTEGER NOT NULL,
                        whom TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        ordinal INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (folderId, whom, postId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bookmark_folder_members_whom_postId " +
                        "ON bookmark_folder_members (whom, postId)"
                )
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_digests (
                        ship TEXT NOT NULL,
                        dateLocal TEXT NOT NULL,
                        generatedAtMs INTEGER NOT NULL,
                        summaryText TEXT,
                        itemsJson TEXT NOT NULL,
                        weatherJson TEXT,
                        PRIMARY KEY (ship, dateLocal)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Open the Room database for a specific ship. Each ship's
         * data lives in its own file so switching ships is a clean
         * cut — no cross-ship rows, no filter predicates. The legacy
         * single-ship file (`talon.db`) is auto-adopted as the first
         * ship's DB so one-ship users don't lose anything.
         */
        fun build(context: Context, ship: String): AppDatabase {
            val filename = shipDbFilename(context, ship)
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                filename,
            )
                .addMigrations(
                    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
                    MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                    MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
                    MIGRATION_26_27,
                )
                // dropAllTables = true preserves the pre-2.7 behavior:
                // when Room can't find a migration path, drop everything
                // and rebuild. Last-resort safety net for users on
                // versions older than 17 (the oldest explicit migration
                // start above).
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        /**
         * Pick the DB filename for [ship]. If the user has the legacy
         * single-file `talon.db` sitting around, the very first ship
         * we see after upgrade adopts it — otherwise we'd orphan it.
         */
        private fun shipDbFilename(context: Context, ship: String): String {
            val safe = ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")
            val legacy = context.getDatabasePath("talon.db")
            val perShip = "talon-$safe.db"
            if (legacy.exists() && !context.getDatabasePath(perShip).exists()) {
                // Rename so the first ship gets its existing data.
                val dest = context.getDatabasePath(perShip)
                runCatching { legacy.renameTo(dest) }
                // Rename the WAL / journal siblings too.
                for (suffix in listOf("-wal", "-shm", "-journal")) {
                    val src = context.getDatabasePath("talon.db$suffix")
                    if (src.exists()) {
                        runCatching { src.renameTo(context.getDatabasePath("$perShip$suffix")) }
                    }
                }
            }
            return perShip
        }
    }
}
