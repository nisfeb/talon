package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-user display order for groups on the home screen. Only groups
 * the user has explicitly reordered live here; unordered groups fall
 * back to alphabetical. `ordinal` is rewritten in bulk on each
 * reorder (no fractional indexing).
 */
@Entity(tableName = "group_orders")
data class GroupOrderEntity(
    @PrimaryKey val flag: String,
    val ordinal: Int,
)
