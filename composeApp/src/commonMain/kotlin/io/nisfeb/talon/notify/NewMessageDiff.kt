package io.nisfeb.talon.notify

import io.nisfeb.talon.data.MessageEntity

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
 *   4. Its whom is not in the muted set.
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
    mutedWhoms: Set<String>,
    storyText: (id: String, contentJson: String) -> String,
): NewMessageDiff {
    val newLastSeen = lastSeen.toMutableMap()
    val notifications = mutableListOf<NotificationCandidate>()
    for (row in rows) {
        val prior = newLastSeen[row.whom]
        newLastSeen[row.whom] = row.id
        if (prior == row.id) continue
        if (row.author == ourPatp) continue
        if (row.whom == openChat) continue
        if (row.whom in mutedWhoms) continue

        val body = storyText(row.id, row.contentJson)
            .replace('\n', ' ')
            .take(200)
            .ifBlank { "(attachment)" }
        notifications += NotificationCandidate(
            whom = row.whom,
            title = row.author,
            body = body,
        )
    }
    return NewMessageDiff(
        newLastSeen = newLastSeen,
        notifications = notifications,
    )
}
