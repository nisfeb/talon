package io.nisfeb.talon.call

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.Log

/**
 * Glue between a group and its party line.
 *
 * One line per *group*, not per channel: a group's voice room is a
 * property of the group, so every channel in it joins the same line
 * and a group's admins turn it on once. The room is derived from the
 * group flag `~host/slug`, so every member computes the same name
 * without extra state, and the host ship is the one that owns the SFU
 * (design D5).
 */
object PartyLineHost {

    /** (host, room) for a group flag `~host/slug`. */
    fun roomForGroup(flag: String): Pair<String, String>? {
        val parts = flag.split("/")
        if (parts.size < 2) return null
        val host = parts[0]
        if (!host.startsWith("~")) return null
        val slug = parts.drop(1).joinToString("-")
        if (slug.isEmpty()) return null
        return host to slug
    }

    /** The group flag behind a channel whom, via the local db. */
    suspend fun groupFlagFor(db: AppDatabase, whom: String): String? =
        runCatching { db.groups().channelGroupFor(whom)?.groupFlag }.getOrNull()

    /** (host, room) for the group a channel belongs to. */
    suspend fun roomFor(db: AppDatabase, whom: String): Pair<String, String>? =
        groupFlagFor(db, whom)?.let { roomForGroup(it) }

    /**
     * Open the line for [whom] and step onto it. Only the channel's
     * host ship can do this; members join an already-open line.
     * Membership comes from the group's own roster, so the line's
     * guest list is exactly the group's.
     */
    suspend fun startLine(
        controller: CallController,
        repo: TlonChatRepo,
        db: AppDatabase,
        whom: String,
        title: String,
    ): Boolean {
        val flag = groupFlagFor(db, whom) ?: return false
        val (host, room) = roomForGroup(flag) ?: return false
        // One fetch gives both lists: the group's roster is the line's
        // guest list, and the group's admins are the ships allowed to
        // reconfigure the line afterwards. %trunk never learns what a
        // group is — it just gets the two sets of ships.
        val roster = runCatching { repo.fetchGroupAdmin(flag)?.members }.getOrNull()
        val members = roster?.map { it.ship }.orEmpty()
        val admins = roster?.filter { it.isAdmin }?.map { it.ship }.orEmpty()
        if (members.isEmpty()) {
            Log.w(TAG, "no group roster for $whom — opening a host-only line")
        }
        // Ordered deliberately: the join must not reach the ship
        // before the room it names exists.
        controller.openRoom(room, title, members, admins)
        // Wire 4 ships mirror the roster from the group itself, so
        // someone added to the group after this moment can still
        // join — the snapshot above is just the opening state. Older
        // ships would nack the poke, so gate on the wire; they keep
        // the wire-3 behaviour (frozen roster) they already had.
        if (controller.wire.value >= 4) {
            controller.bindRoom(room, flag)
        }
        controller.joinRoom(host, room)
        return true
    }

    /** Step onto a line someone else is hosting. */
    suspend fun joinLine(
        controller: CallController,
        db: AppDatabase,
        whom: String,
    ): Boolean {
        val (host, room) = roomFor(db, whom) ?: return false
        controller.joinRoom(host, room)
        return true
    }

    private const val TAG = "PartyLine"
}
