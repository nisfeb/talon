package io.nisfeb.talon.notify

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.shipHandle

/**
 * What [diffNewMessageNotifications] decides should be fired as an
 * OS notification. Plain data so it can be assembled from any thread
 * and handed off to [Notifier.notify] without further synchronization.
 */
data class NotificationCandidate(
    val whom: String,
    val title: String,
    val body: String,
)

/**
 * Result of one diff pass. The caller threads [newLastSeen] back in
 * for the next emission so we don't re-fire notifications for the
 * same message id repeatedly.
 */
data class NewMessageDiff(
    val newLastSeen: Map<String, String>,
    val notifications: List<NotificationCandidate>,
)

/**
 * First emission from `conversationLatest()` after sign-in seeds the
 * "what we've already seen" baseline so the user doesn't get
 * notification spam for every existing chat. Subsequent emissions go
 * through [diffNewMessageNotifications].
 */
fun seedNewMessageBaseline(rows: List<MessageEntity>): Map<String, String> =
    rows.associate { it.whom to it.id }

/**
 * Pure decision function: given the latest message per conversation
 * and the prior baseline, return the list of notifications to fire
 * plus the updated baseline.
 *
 * A row triggers a notification when ALL of:
 *   1. Its id changed since the prior baseline (or it's a new whom).
 *   2. Its author is not the local user (no self-notify).
 *   3. Its whom is not the currently-open chat (the user is already
 *      looking at it).
 *   4. Its whom's notification level allows it — see [notifyAllowed].
 *
 * Even rows that are filtered out still update the baseline so the
 * next emission compares against the latest known id rather than
 * the stale one — otherwise a muted whom would fire as soon as it
 * was unmuted, since the prior id would still mismatch.
 */
fun diffNewMessageNotifications(
    rows: List<MessageEntity>,
    lastSeen: Map<String, String>,
    ourPatp: String?,
    openChat: String?,
    /** whom → stored level ("all" / "mentions" / "none"). A whom with
     *  no entry is at [NotifyLevel.DEFAULT], mentions only. */
    levels: Map<String, String>,
    storyText: (id: String, contentJson: String) -> String,
    /** Current wall-clock ms. Paired with [freshnessMaxAgeMs] for the
     *  staleness guard below. Defaults to 0 which, with the default
     *  max-age, disables the guard entirely (back-compat for callers
     *  that don't care about backlog suppression). */
    nowMs: Long = 0L,
    /** Suppress notifications for messages older than this. A re-synced
     *  backlog (fresh DB bootstrap, reconnect replay, deep-history
     *  fill) brings in messages with OLD `sentMs`; without this guard
     *  every one of them fires a notification the moment the
     *  `bootstrapping` flag flips false mid-ingest — the "deluge on
     *  first login". A genuinely new live message has a recent
     *  `sentMs` and passes. The baseline still advances for suppressed
     *  rows so they never fire later either. Default MAX_VALUE = off. */
    freshnessMaxAgeMs: Long = Long.MAX_VALUE,
    /** What to call the author. A comet's @p is fifty-six characters
     *  of fingerprint and says nothing to the person reading it, so
     *  the same name the rest of the app shows goes on the balloon.
     *  Identity by default, for callers that have no name to give. */
    nameFor: (String) -> String = { it },
): NewMessageDiff {
    val newLastSeen = lastSeen.toMutableMap()
    val notifications = mutableListOf<NotificationCandidate>()
    for (row in rows) {
        val prior = newLastSeen[row.whom]
        newLastSeen[row.whom] = row.id
        if (prior == row.id) continue
        if (row.author == ourPatp) continue
        if (row.whom == openChat) continue
        val level = levels[row.whom] ?: NotifyLevel.DEFAULT
        if (!notifyAllowed(row.whom, level, isMentioned(row.contentJson, ourPatp))) continue
        // Staleness guard: backfilled / re-synced messages have an old
        // sentMs and must not notify. Baseline already advanced above,
        // so a suppressed-as-stale row won't re-fire on a later pass.
        if (nowMs - row.sentMs > freshnessMaxAgeMs) continue

        val text = storyText(row.id, row.contentJson)
        val title = row.title?.trim().orEmpty()
        val body = (if (title.isEmpty()) text else if (text.isBlank()) title else "$title: $text")
            .replace('\n', ' ')
            .take(200)
            .ifBlank { "(attachment)" }
        notifications += NotificationCandidate(
            whom = row.whom,
            title = nameFor(row.author),
            body = body,
        )
    }
    return NewMessageDiff(
        newLastSeen = newLastSeen,
        notifications = notifications,
    )
}

/**
 * The balloon for a reply in a thread that counts (the repo has decided:
 * followed, a DM's, the owner's own). Following is asking for it, so only
 * a muted chat, that chat open in front, or a stale reply holds it back.
 * The top-level diff above never sees replies.
 */
fun replyNotification(
    reply: MessageEntity,
    level: String?,
    openChat: String?,
    nowMs: Long,
    freshnessMaxAgeMs: Long,
    storyText: (id: String, contentJson: String) -> String,
    nameFor: (String) -> String = { it },
): NotificationCandidate? {
    if (reply.parentId == null || reply.whom == openChat || level == NotifyLevel.NONE) return null
    if (nowMs - reply.sentMs > freshnessMaxAgeMs) return null
    val text = storyText(reply.id, reply.contentJson).replace('\n', ' ').take(200).ifBlank { "(attachment)" }
    return NotificationCandidate(whom = reply.whom, title = nameFor(reply.author), body = "In a thread: $text")
}

/**
 * The names a relay push is shown with. [names] is the signed-in ship's
 * map; a push for another of our ships gets none of its nicknames, which
 * are not that ship's, only the word names anyone would see.
 */
fun pushNames(forShip: String?, activeShip: String?, names: ContactMap): ContactMap =
    if (forShip == null || forShip == activeShip) names else ContactMap(alwaysPatp = names.alwaysPatp)

/**
 * A relay push for a message says where, not what: the conversation by
 * the name the app gives it, and which of our ships when it is not the
 * one signed in. It used to show our own @p over the raw conversation id.
 */
fun pushHintNotification(whom: String, forShip: String?, activeShip: String?, names: ContactMap): NotificationCandidate =
    NotificationCandidate(
        whom = whom,
        title = pushNames(forShip, activeShip, names).conversationLabel(whom),
        body = if (forShip == null || forShip == activeShip) "New activity" else "New activity on ${shipHandle(forShip)}",
    )

/** Does the story name our ship? Same test the Android filter and
 *  the relay use: a mention inline is `{"ship":"~us"}` in the JSON. */
fun isMentioned(contentJson: String, ourPatp: String?): Boolean {
    if (ourPatp.isNullOrBlank()) return false
    return contentJson.contains("\"ship\":\"$ourPatp\"")
}

/**
 * The per-chat level applied to one message. Mirrors the relay's
 * NotifyPolicy so desktop, Android and push agree: "none" is silent,
 * "mentions" needs a mention in a group channel but always passes a
 * DM or club (those are addressed to you), anything else notifies.
 */
fun notifyAllowed(whom: String, level: String?, mention: Boolean): Boolean = when (level) {
    NotifyLevel.NONE -> false
    NotifyLevel.MENTIONS -> mention || !whom.contains('/')
    else -> true
}
