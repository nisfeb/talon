package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One headless execution of a [LoopEntity]. Device-local — runs never
 * sync (only the definition does). Kept as a short rolling history per
 * loop for the Loops screen; older rows are pruned by [LoopRunDao].
 */
@Immutable
@Entity(
    tableName = "loop_run",
    indices = [Index(value = ["loopId", "ranAt"])],
)
data class LoopRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loopId: Long,
    val ranAt: Long,
    val ok: Boolean,
    val output: String,
)
