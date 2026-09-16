package io.nisfeb.talon.mail

/** One notification's worth of new mail. */
data class MailNotification(val threadId: String, val title: String, val body: String)

/**
 * What to raise after a read, and what to remember for the next one.
 *
 * Pure, because the decision is the part worth testing and the two
 * hosts deliver notifications by unrelated mechanisms. They share this.
 *
 * The rule is deliberately about threads, not messages: a thread that
 * was already unread when we last looked has already been announced,
 * and announcing it again on every tick is how a poll becomes a
 * nuisance. Reading mail cannot notify anybody either, because a read
 * mark is local and the next read simply finds the row no longer
 * unread.
 */
fun diffMailNotifications(
    rows: List<InboxEntry>,
    lastSeen: Set<String>,
    nameFor: (String) -> String,
    cap: Int = DEFAULT_CAP,
): Pair<List<MailNotification>, Set<String>> {
    val unreadNow = rows.filter { it.unread }.map { it.id }.toSet()
    val fresh = rows.filter { it.unread && it.id !in lastSeen }
    if (fresh.isEmpty()) return emptyList<MailNotification>() to unreadNow

    val shown = fresh.take(cap).map { row ->
        MailNotification(
            threadId = row.id,
            // The loudest verdict stays loud where the user is not
            // looking at the app. A thread carrying any forged copy says
            // so here, whether or not the summary came from one.
            title = buildString {
                if (row.forged || row.verdict == Verdict.FORGED) append("FORGED · ")
                append(nameFor(row.from))
                val subject = row.subject.ifBlank { "(no subject)" }
                append(" · ").append(subject)
            },
            body = row.snippet.ifBlank { "(no preview)" },
        )
    }
    val extra = fresh.size - shown.size
    val all = if (extra <= 0) {
        shown
    } else {
        shown + MailNotification(
            threadId = "",
            title = if (extra == 1) "and 1 more message" else "and $extra more messages",
            body = "",
        )
    }
    return all to unreadNow
}

/**
 * The baseline at startup: everything unread right now counts as
 * already seen, so launching never replays a backlog of mail the user
 * has had for days.
 */
fun seedMailBaseline(rows: List<InboxEntry>): Set<String> =
    rows.filter { it.unread }.map { it.id }.toSet()

private const val DEFAULT_CAP = 3
