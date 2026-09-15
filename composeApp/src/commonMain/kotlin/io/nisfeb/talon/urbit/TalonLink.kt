package io.nisfeb.talon.urbit

import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter

/**
 * An address for a thing inside Talon, so a message or a mail thread
 * can be copied and pasted anywhere text goes: a note on an event, a
 * task, another chat. Tapped in Talon, it lands on the thing.
 *
 *   talon://chat/<whom>?id=<post>[&parent=<parent>]
 *   talon://mail/<thread>
 *   talon://group/~host/name   a group, which a scan joins
 *   talon://invite/~ship       a ship asking to be invited
 */
sealed interface TalonLink {
    data class Message(val whom: String, val id: String, val parentId: String?) : TalonLink
    data class Mail(val threadId: String) : TalonLink
    /** A group by its flag, `~host/name`. */
    data class Group(val flag: String) : TalonLink
    /** A ship asking to be invited to a group. */
    data class InviteMe(val ship: String) : TalonLink

    companion object {
        const val SCHEME = "talon://"
        private val SHIP = Regex("~[a-z]+(-[a-z]+)*")
        private val FLAG = Regex("~[a-z]+(-[a-z]+)*/[a-z0-9][a-z0-9-]*")

        fun forMessage(whom: String, id: String, parentId: String? = null): String =
            "${SCHEME}chat/${whom.encodeURLParameter()}?id=${id.encodeURLParameter()}" +
                (parentId?.let { "&parent=${it.encodeURLParameter()}" } ?: "")

        fun forMail(threadId: String): String = "${SCHEME}mail/${threadId.encodeURLParameter()}"

        fun forGroup(flag: String): String = "${SCHEME}group/$flag"

        fun forInviteMe(ship: String): String = "${SCHEME}invite/$ship"

        fun isTalonUrl(s: String): Boolean = s.trim().startsWith(SCHEME)

        /** The thing an address names, or null for one that is not ours. */
        fun parse(uri: String): TalonLink? {
            val t = uri.trim()
            if (!t.startsWith(SCHEME)) return null
            val rest = t.removePrefix(SCHEME)
            val path = rest.substringBefore('?')
            val query = rest.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=').decodeURLPart() }
            return when {
                path.startsWith("chat/") -> {
                    val whom = path.removePrefix("chat/").decodeURLPart()
                    val id = query["id"] ?: return null
                    if (whom.isBlank() || id.isBlank()) null else Message(whom, id, query["parent"]?.takeIf { it.isNotBlank() })
                }
                path.startsWith("mail/") -> path.removePrefix("mail/").decodeURLPart().takeIf { it.isNotBlank() }?.let { Mail(it) }
                path.startsWith("group/") -> path.removePrefix("group/").decodeURLPart().takeIf { FLAG.matches(it) }?.let { Group(it) }
                path.startsWith("invite/") -> path.removePrefix("invite/").decodeURLPart().takeIf { SHIP.matches(it) }?.let { InviteMe(it) }
                else -> null
            }
        }
    }
}
