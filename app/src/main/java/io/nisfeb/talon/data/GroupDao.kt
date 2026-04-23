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
}
