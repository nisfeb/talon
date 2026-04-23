package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A conversation pinned to the top of the home list. `ordinal` is the
 * sort key — lower is higher up — and is rewritten in bulk by the
 * reorder handler rather than relying on fractional indexing.
 */
@Entity(tableName = "pins")
data class PinEntity(
    @PrimaryKey val whom: String,
    val ordinal: Int,
)
