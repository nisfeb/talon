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
    ],
    version = 22,
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
                    MIGRATION_20_21, MIGRATION_21_22,
                )
                .fallbackToDestructiveMigration()
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
