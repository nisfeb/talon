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
        val members = if (flag != null) {
            runCatching { repo.fetchGroupAdmin(flag)?.members?.map { it.ship } }
                .getOrNull().orEmpty()
        } else {
            emptyList()
        }
        if (members.isEmpty()) {
            Log.w(TAG, "no group roster for $whom — opening a host-only line")
        }
        controller.openRoom(room, title, members)
        controller.joinRoom(host, room)
        return true
    }

    /** Step onto a line someone else is hosting. */
    fun joinLine(controller: CallController, whom: String): Boolean {
        val (host, room) = roomFor(whom) ?: return false
        controller.joinRoom(host, room)
        return true
    }

    private const val TAG = "PartyLine"
}
