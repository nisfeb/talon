package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Upsert
    suspend fun upsertGroups(groups: List<GroupEntity>)

    @Upsert
    suspend fun upsertChannelGroups(entries: List<ChannelGroupEntity>)

    /**
     * Insert or refresh the columns %groups owns, leaving
     * [ChannelGroupEntity.pinnedPostId] as it is.
     *
     * A channel's pin comes from the %channels agent's `order`, not
     * from %groups, but both write this row. `@Upsert` overwrites
     * every column, and the %groups parser has no pin to supply, so a
     * group reconcile blanked every pin it touched and the pinned
     * banner had nothing to draw. On a fresh row the pin starts null,
     * which the orders pass then fills in.
     */
    @Query(
        """
        INSERT INTO channel_groups (nest, groupFlag, title, pinnedPostId, ordinal)
        VALUES (:nest, :groupFlag, :title, NULL, :ordinal)
        ON CONFLICT(nest) DO UPDATE SET
            groupFlag = excluded.groupFlag,
            title = excluded.title,
            ordinal = excluded.ordinal
        """,
    )
    suspend fun upsertChannelGroupKeepingPin(
        nest: String,
        groupFlag: String,
        title: String?,
        ordinal: Int,
    )

    /** [upsertChannelGroupKeepingPin] for a batch, in one transaction. */
    @Transaction
    suspend fun upsertChannelGroupsKeepingPin(entries: List<ChannelGroupEntity>) {
        for (e in entries) {
            upsertChannelGroupKeepingPin(e.nest, e.groupFlag, e.title, e.ordinal)
        }
    }

    @Query("SELECT * FROM groups")
    fun streamGroups(): Flow<List<GroupEntity>>

    /**
     * Name search over groups for the search screen. Matches the
     * group's title (case-insensitive substring). Prefix matches sort
     * first (a query of "des" ranks "Design" above "Wednesday"), then
     * alphabetical — same relevance shape as [ContactDao.search].
     * `ESCAPE '\'` so `%` / `_` in the user's text aren't wildcards.
     */
    @Query("""
        SELECT * FROM groups
        WHERE title IS NOT NULL
          AND title LIKE '%' || :q || '%' ESCAPE '\' COLLATE NOCASE
        ORDER BY
          CASE WHEN title LIKE :q || '%' ESCAPE '\' COLLATE NOCASE THEN 0 ELSE 1 END,
          title COLLATE NOCASE
        LIMIT 20
    """)
    fun searchGroups(q: String): Flow<List<GroupEntity>>

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

    /**
     * Update the pinned-post slot on an existing channel_groups row.
     * Returns the number of rows affected — `0` indicates the row
     * doesn't exist yet (channel hasn't been bootstrapped via
     * `%groups` or the live channel-add event), in which case the
     * write is silently lost. Callers should log the zero case.
     */
    @Query("UPDATE channel_groups SET pinnedPostId = :pinnedPostId WHERE nest = :nest")
    suspend fun setPinnedPostId(nest: String, pinnedPostId: String?): Int

    @Query("SELECT pinnedPostId FROM channel_groups WHERE nest = :nest LIMIT 1")
    fun streamPinnedPostId(nest: String): Flow<String?>

    @Query("SELECT pinnedPostId FROM channel_groups WHERE nest = :nest LIMIT 1")
    suspend fun pinnedPostIdFor(nest: String): String?
}
