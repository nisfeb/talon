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
        // The ship body is Patp's: moons and comets carry `--`, and a
        // regex without it parses every comet link Talon emits to null.
        private const val SHIP_BODY = "(?:[a-z]{6}|[a-z]{3})(?:--?(?:[a-z]{6}|[a-z]{3}))*"
        private val SHIP = Regex("~$SHIP_BODY")
        private val FLAG = Regex("~$SHIP_BODY/[a-z0-9][a-z0-9-]*")
        private val NEST = Regex("(chat|heap|diary)/~$SHIP_BODY/[a-z0-9][a-z0-9-]*")
        private val CLUB = Regex("0v[0-9a-v]+(\\.[0-9a-v]+)*")

        fun forMessage(whom: String, id: String, parentId: String? = null): String =
            "${SCHEME}chat/${whom.encodeURLParameter()}?id=${id.encodeURLParameter()}" +
                (parentId?.let { "&parent=${it.encodeURLParameter()}" } ?: "")

        fun forMail(threadId: String): String = "${SCHEME}mail/${threadId.encodeURLParameter()}"

        fun forGroup(flag: String): String = "${SCHEME}group/${flag.encodeURLParameter()}"

        fun forInviteMe(ship: String): String = "${SCHEME}invite/${ship.encodeURLParameter()}"

        /**
         * Tlon's reference to a group, `/1/group/~host/name` (its
         * citeToPath). Tlon's join box takes it, and pasted in a chat in
         * Tlon or Talon it goes out as the group's cite.
         */
        fun groupReference(flag: String): String = "/1/group/$flag"

        /** A group reference standing alone in a message's text; group 1 is the flag. */
        val GROUP_REFERENCE = Regex("(?<!\\S)/1/group/(~$SHIP_BODY/[a-z0-9][a-z0-9-]*)(?!\\S)")

        /** The group a typed or pasted code names: `~host/name`, Tlon's reference to it, or Talon's group link. */
        fun groupFlag(code: String): String? {
            val t = code.trim()
            (parse(t) as? Group)?.let { return it.flag }
            return t.removePrefix("/1/group/").takeIf { FLAG.matches(it) }
        }

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
                    // whom names a conversation: a ship, a channel nest,
                    // or a club — the same shapes the rest of the app
                    // insists on, so a garbage link can't open a
                    // conversation that doesn't exist.
                    if (id.isBlank() || !(SHIP.matches(whom) || NEST.matches(whom) || CLUB.matches(whom))) null
                    else Message(whom, id, query["parent"]?.takeIf { it.isNotBlank() })
                }
                path.startsWith("mail/") -> path.removePrefix("mail/").decodeURLPart().takeIf { it.isNotBlank() }?.let { Mail(it) }
                path.startsWith("group/") -> path.removePrefix("group/").decodeURLPart().takeIf { FLAG.matches(it) }?.let { Group(it) }
                path.startsWith("invite/") -> path.removePrefix("invite/").decodeURLPart().takeIf { SHIP.matches(it) }?.let { InviteMe(it) }
                else -> null
            }
        }
    }
}
