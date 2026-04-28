// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/GroupDao.kt
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Upsert
    suspend fun upsertGroups(groups: List<GroupEntity>)

    @Upsert
    suspend fun upsertChannelGroups(entries: List<ChannelGroupEntity>)

    @Query("SELECT * FROM groups")
    fun streamGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM channel_groups")
    fun streamChannelGroups(): Flow<List<ChannelGroupEntity>>

    @Query("SELECT * FROM channel_groups WHERE nest = :nest LIMIT 1")
    suspend fun channelGroupFor(nest: String): ChannelGroupEntity?

    /** Snapshot of every known group. Used by bootstrap reconciliation. */
    @Query("SELECT * FROM groups")
    suspend fun allGroups(): List<GroupEntity>

    /** Snapshot of every known channel→group mapping. Used by bootstrap reconciliation. */
    @Query("SELECT * FROM channel_groups")
    suspend fun allChannelGroups(): List<ChannelGroupEntity>

    @Query("SELECT * FROM groups WHERE flag = :flag LIMIT 1")
    suspend fun getGroup(flag: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE flag = :flag LIMIT 1")
    fun streamGroup(flag: String): Flow<GroupEntity?>

    @Query("SELECT * FROM channel_groups WHERE groupFlag = :flag")
    fun streamChannelsForGroup(flag: String): Flow<List<ChannelGroupEntity>>

    @Query("DELETE FROM groups WHERE flag = :flag")
    suspend fun deleteGroup(flag: String)

    @Query("DELETE FROM channel_groups WHERE groupFlag = :flag")
    suspend fun deleteChannelsForGroup(flag: String)

    @Query("DELETE FROM channel_groups WHERE nest = :nest")
    suspend fun deleteChannelGroup(nest: String)

    @Query("UPDATE channel_groups SET pinnedPostId = :pinnedPostId WHERE nest = :nest")
    suspend fun setPinnedPostId(nest: String, pinnedPostId: String?)

    @Query("SELECT pinnedPostId FROM channel_groups WHERE nest = :nest LIMIT 1")
    fun streamPinnedPostId(nest: String): Flow<String?>

    @Query("SELECT pinnedPostId FROM channel_groups WHERE nest = :nest LIMIT 1")
    suspend fun pinnedPostIdFor(nest: String): String?
}
