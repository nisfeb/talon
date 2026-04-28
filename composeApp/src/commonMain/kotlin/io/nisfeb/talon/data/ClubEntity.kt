// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/ClubEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group DM (club). `id` is the `0v...` uv atom; `title` is the
 * user-set display label (stored in the club's meta map on the ship).
 */
@Entity(tableName = "clubs")
data class ClubEntity(
    @PrimaryKey val id: String,
    val title: String?,
)
