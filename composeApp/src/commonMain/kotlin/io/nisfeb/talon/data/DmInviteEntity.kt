package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A pending DM request — a ship that opened a DM with us that we haven't
 * accepted or declined yet. In Tlon, a DM from a non-contact lands as an
 * "invite" rather than a conversation: its writs don't flow over the
 * normal subscription until accepted, so without this Talon never
 * surfaced it (no list row, no notification).
 *
 * Sourced from %chat's `/dm/invited` scry at bootstrap + the live
 * ship-array facts the chat `/v4` subscription pushes. Accept/decline
 * via the `chat-dm-rsvp` poke; the row is then removed (accept turns it
 * into a real DM whose messages flow normally).
 */
@Immutable
@Entity(tableName = "dm_invites")
data class DmInviteEntity(
    @PrimaryKey val ship: String,
    /** Local ms when we first observed this invite — drives ordering. */
    val receivedMs: Long,
)
