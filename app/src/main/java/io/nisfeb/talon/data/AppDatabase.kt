package io.nisfeb.talon.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        PinEntity::class,
        GroupOrderEntity::class,
    ],
    version = 16,
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
    abstract fun pins(): PinDao
    abstract fun groupOrders(): GroupOrderDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "talon.db"
            )
                .fallbackToDestructiveMigration()
                .build()
    }
}
