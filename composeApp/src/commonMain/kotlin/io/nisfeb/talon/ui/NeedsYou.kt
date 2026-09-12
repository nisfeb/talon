package io.nisfeb.talon.ui

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.UnreadEntity

/**
 * The things actually waiting on you.
 *
 * This replaces the daily digest, which was a recap: it summarised the
 * last twenty-four hours once, on an alarm, and by the time anybody
 * read it they had usually seen the messages. It also needed an API
 * key and an AlarmManager, so it said nothing at all to most people.
 *
 * Nothing here is generated or scheduled. A conversation whose newest
 * message is not yours and which you have not read is a question
 * waiting on an answer; so is a mention, an unread mail thread, an
 * invitation. Those facts are already in the database, they are true
 * the moment they are read, and they cost nothing to work out.
 */
enum class NeedKind {
    /** Somebody said your name. */
    MENTION,

    /** The last word in a conversation is theirs, and unread. */
    REPLY,
    MAIL,
    INVITE,
}

data class NeedsYouItem(
    val kind: NeedKind,
    /** What opening it should open: a whom, a mail thread id, a flag. */
    val target: String,
    val title: String,
    val line: String,
    val atMs: Long,
)

/** One unread mail thread, as much of it as this needs. */
data class MailNeed(val id: String, val from: String, val subject: String, val atMs: Long)

/**
 * What is owed, most pressing first.
 *
 * Ordered by kind before recency, deliberately. A mention from this
 * morning is worth more of somebody's attention than an unread group
 * message from a minute ago, and a list that reshuffled itself by the
 * clock would bury the one the moment the other arrived.
 */
fun needsYou(
    latest: List<MessageEntity>,
    unreadBy: Map<String, UnreadEntity>,
    mail: List<MailNeed>,
    invites: List<String>,
    ourShip: String,
    limit: Int,
    label: (whom: String) -> String,
    preview: (MessageEntity) -> String,
): List<NeedsYouItem> {
    val mentions = mutableListOf<NeedsYouItem>()
    val replies = mutableListOf<NeedsYouItem>()

    for (m in latest) {
        val unread = unreadBy[m.whom] ?: continue
        if (unread.count <= 0) continue
        // Our own last word is not a question waiting on us. This is
        // the whole distinction between a to-do and an inbox.
        if (m.author == ourShip) continue
        val item = NeedsYouItem(
            kind = if (unread.notifyCount > 0) NeedKind.MENTION else NeedKind.REPLY,
            target = m.whom,
            title = label(m.whom),
            line = preview(m),
            atMs = m.sentMs,
        )
        if (item.kind == NeedKind.MENTION) mentions += item else replies += item
    }

    val mailItems = mail.map {
        NeedsYouItem(
            kind = NeedKind.MAIL,
            target = it.id,
            title = label(it.from),
            line = it.subject.ifBlank { "(no subject)" },
            atMs = it.atMs,
        )
    }
    val inviteItems = invites.map {
        NeedsYouItem(
            kind = NeedKind.INVITE,
            target = it,
            title = it,
            line = "Group invitation",
            atMs = 0L,
        )
    }

    fun newestFirst(rows: List<NeedsYouItem>) = rows.sortedByDescending { it.atMs }
    return (
        newestFirst(mentions) + newestFirst(replies) +
            newestFirst(mailItems) + inviteItems
        ).take(limit.coerceAtLeast(0))
}
