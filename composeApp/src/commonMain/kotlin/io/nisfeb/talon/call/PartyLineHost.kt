package io.nisfeb.talon.call

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.Log

/**
 * Glue between a group channel and its party line.
 *
 * A channel's whom is `chat/~host/slug`; the line it maps to is the
 * room `slug` hosted by `~host` — so every member derives the same
 * room without any extra state, and the host ship is the one that
 * owns the SFU (design D5).
 */
object PartyLineHost {

    /** (host, room) for a group channel, or null for DMs and clubs. */
    fun roomFor(whom: String): Pair<String, String>? {
        val parts = whom.split("/")
        if (parts.size < 3) return null
        if (parts[0] != "chat") return null
        val host = parts[1]
        if (!host.startsWith("~")) return null
        return host to parts.drop(2).joinToString("-")
    }

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
        val (host, room) = roomFor(whom) ?: return false
        val flag = runCatching { db.groups().channelGroupFor(whom)?.groupFlag }
            .getOrNull()
        // One fetch gives both lists: the group's roster is the line's
        // guest list, and the group's admins are the ships allowed to
        // reconfigure the line afterwards. %trunk never learns what a
        // group is — it just gets the two sets of ships.
        val roster = if (flag != null) {
            runCatching { repo.fetchGroupAdmin(flag)?.members }.getOrNull()
        } else {
            null
        }
        val members = roster?.map { it.ship }.orEmpty()
        val admins = roster?.filter { it.isAdmin }?.map { it.ship }.orEmpty()
        if (members.isEmpty()) {
            Log.w(TAG, "no group roster for $whom — opening a host-only line")
        }
        // Ordered deliberately: the join must not reach the ship
        // before the room it names exists.
        controller.openRoom(room, title, members, admins)
        controller.joinRoom(host, room)
        return true
    }

    /** Step onto a line someone else is hosting. */
    suspend fun joinLine(controller: CallController, whom: String): Boolean {
        val (host, room) = roomFor(whom) ?: return false
        controller.joinRoom(host, room)
        return true
    }

    private const val TAG = "PartyLine"
}
