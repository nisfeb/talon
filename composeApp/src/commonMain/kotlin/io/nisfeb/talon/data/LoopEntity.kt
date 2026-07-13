package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-defined "loop": a saved prompt run through the assistant agent
 * on a fixed interval, headless, with the result stored + notified. The
 * scheduling/execution mirror Daily Digest; the agent is the same one
 * the assistant screen drives (read-only tools in Stage 1).
 *
 * [gid] is a stable global id so a definition can sync across the user's
 * devices (the %settings loops bucket, wired later); [id] is the local
 * rowid. [lastRunAt] is device-local — it does NOT travel with a synced
 * definition (each device tracks its own runs), mirroring how synced
 * assistant conversations preserve their local-derived fields.
 *
 * [writesAuthorized] is the per-loop consent gate for write tools,
 * honored by the headless [io.nisfeb.talon.ai.LoopRunner] by
 * construction. Like [lastRunAt] it is DEVICE-LOCAL — it does not sync
 * across devices. Granting a loop unattended write access is a decision
 * the user must make on each device; a synced definition always arrives
 * read-only (see SettingsSyncImpl.upsertLoop).
 */
@Immutable
@Entity(tableName = "loop")
data class LoopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val name: String,
    val prompt: String,
    val intervalMinutes: Int,
    val enabled: Boolean = true,
    val writesAuthorized: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long = 0,
    /**
     * Schedule shape. `"interval"` (default) fires every
     * [intervalMinutes]; `"weekly"` fires at [atMinuteOfDay] on the
     * weekdays in [daysMask]. New columns default to the interval
     * behavior so pre-existing loops are unchanged. See
     * [io.nisfeb.talon.ai.LoopSchedule].
     */
    val scheduleKind: String = "interval",
    /** For `"weekly"`: minutes since local midnight (e.g. 360 = 06:00). */
    val atMinuteOfDay: Int = 0,
    /** For `"weekly"`: bitmask of active weekdays, bit = DayOfWeek.ordinal
     *  (Mon=0 … Sun=6). 0 means every day at [atMinuteOfDay]. */
    val daysMask: Int = 0,
)
