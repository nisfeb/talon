package io.nisfeb.talon.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Android-side actual for the KMP AppDatabase. Empty class body —
 * Room's compiler synthesises everything from the expect declaration's
 * `@Database` metadata. The factory function below is what the rest of
 * the Android module (composeApp) calls to obtain an instance.
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
}

/**
 * Mirror of the production `AppDatabase.build` so the composeApp Android
 * APK opens an equivalent database. The migrations are copied verbatim
 * from app/AppDatabase.kt so a user migrating between the two APKs sees
 * the same schema evolution. The DB file name is owned by the caller —
 * production passes per-ship names; composeApp will eventually do the
 * same once the ship-switching plumbing lands here.
 */
fun createAppDatabase(context: Context, name: String): AppDatabase {
    return Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        name,
    )
        .addMigrations(
            MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
            MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
            MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
            MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
            MIGRATION_29_30,
        )
        // dropAllTables = true preserves the pre-2.7 behaviour: when
        // Room can't find a migration path, drop everything and rebuild.
        // Last-resort safety net for users on versions older than 17.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}

private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE folder_members ADD COLUMN kind TEXT NOT NULL DEFAULT 'whom'"
        )
    }
}

private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS pins")
    }
}

private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN image TEXT")
    }
}

private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE channel_groups ADD COLUMN pinnedPostId TEXT")
    }
}

private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
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

private val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
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

private val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_messages_whom_parentId_sentMs " +
                "ON messages (whom, parentId, sentMs)"
        )
    }
}

private val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
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

private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Per-row send state for our own outgoing channel posts. Old
        // rows get NULL, which is the "not tracked / sent" path the UI
        // already treats as the default. See MessageEntity.status
        // KDoc for the value contract.
        db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT")
    }
}

private val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Host-defined channel order within each group. Pre-existing
        // rows get 0 (the default), which keeps the alpha-by-nest
        // tiebreaker the home list already uses; the next bootstrap
        // populates real values from the %groups scry's iteration
        // order. See ChannelGroupEntity.ordinal KDoc.
        db.execSQL(
            "ALTER TABLE channel_groups ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 0",
        )
    }
}

private val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Derived media index table. Empty after migration — the
        // first launch on the new version runs a one-shot backfill
        // that walks existing messages and re-derives rows. See
        // MessageMediaEntity KDoc.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_media (
                whom TEXT NOT NULL,
                messageId TEXT NOT NULL,
                url TEXT NOT NULL,
                category TEXT NOT NULL,
                displayText TEXT,
                sentMs INTEGER NOT NULL,
                author TEXT NOT NULL,
                PRIMARY KEY (whom, messageId, url)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_message_media_whom_category_sentMs " +
                "ON message_media (whom, category, sentMs)"
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
