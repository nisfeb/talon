package io.nisfeb.talon.ui

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.UnreadEntity

/**
 * What is new, across everything.
 *
 * This replaces the daily digest, which was a recap: it summarised the
 * last twenty-four hours once, on an alarm, and by the time anybody
 * read it they had usually seen the messages. It also needed an API
 * key and an AlarmManager, so it said nothing at all to most people.
 *
 * Nothing here is generated or scheduled. Unread conversations,
 * mentions, unread mail and pending invitations are already in the
 * database, they are true the moment they are read, and they cost
 * nothing to work out.
 *
 * It says new rather than owed on purpose. Most of what lands in a
 * group chat is not addressed to anybody in particular, and a list
 * that called all of it a thing waiting on you would be wrong about
 * most of its own rows. The one row that genuinely is addressed to
 * you is the mention, and that is what the ordering is for.
 */
enum class NewKind {
    /** Somebody said your name. */
    MENTION,

    /** Unread, and the last word is not yours. */
    UNREAD,
    MAIL,
    INVITE,
}

data class NewItem(
    val kind: NewKind,
    /** What opening it should open: a whom, a mail thread id, a flag. */
    val target: String,
    val title: String,
    val line: String,
    val atMs: Long,
)

/** One unread mail thread, as much of it as this needs. */
data class NewMail(val id: String, val from: String, val subject: String, val atMs: Long)

/**
 * What is new, most notable first.
 *
 * Ordered by kind before recency, deliberately. A mention from this
 * morning is worth more of somebody's attention than an unread group
 * message from a minute ago, and a list that reshuffled itself by the
 * clock would bury the one the moment the other arrived.
 */
fun whatsNew(
    latest: List<MessageEntity>,
    unreadBy: Map<String, UnreadEntity>,
    mail: List<NewMail>,
    invites: List<String>,
    ourShip: String,
    limit: Int,
    label: (whom: String) -> String,
    preview: (MessageEntity) -> String,
): List<NewItem> {
    val mentions = mutableListOf<NewItem>()
    val unread = mutableListOf<NewItem>()

    for (m in latest) {
        val row = unreadBy[m.whom] ?: continue
        if (row.count <= 0) continue
        // Nothing is new about our own last word.
        if (m.author == ourShip) continue
        val item = NewItem(
            kind = if (row.notifyCount > 0) NewKind.MENTION else NewKind.UNREAD,
            target = m.whom,
            title = label(m.whom),
            line = preview(m),
            atMs = m.sentMs,
        )
        if (item.kind == NewKind.MENTION) mentions += item else unread += item
    }

    val mailItems = mail.map {
        NewItem(
            kind = NewKind.MAIL,
            target = it.id,
            title = label(it.from),
            line = it.subject.ifBlank { "(no subject)" },
            atMs = it.atMs,
        )
    }
    val inviteItems = invites.map {
        NewItem(
            kind = NewKind.INVITE,
            target = it,
            title = it,
            line = "Group invitation",
            atMs = 0L,
        )
    }

    fun newestFirst(rows: List<NewItem>) = rows.sortedByDescending { it.atMs }
    return (
        newestFirst(mentions) + newestFirst(unread) +
            newestFirst(mailItems) + inviteItems
        ).take(limit.coerceAtLeast(0))
}
