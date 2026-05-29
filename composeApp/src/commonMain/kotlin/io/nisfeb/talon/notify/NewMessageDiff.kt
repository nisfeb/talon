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
        // Staleness guard: backfilled / re-synced messages have an old
        // sentMs and must not notify. Baseline already advanced above,
        // so a suppressed-as-stale row won't re-fire on a later pass.
        if (nowMs - row.sentMs > freshnessMaxAgeMs) continue

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
