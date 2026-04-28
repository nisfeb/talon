// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/ReactionUsageEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity

/**
 * Local tally of how often the user has reacted with a given emoji
 * shortcode. Drives the frequency-ranked suggestions in the reaction
 * picker. Purely local — not synced.
 */
@Entity(tableName = "reaction_usage", primaryKeys = ["shortcode"])
data class ReactionUsageEntity(
    val shortcode: String,
    val count: Int,
    val lastUsedMs: Long,
)
