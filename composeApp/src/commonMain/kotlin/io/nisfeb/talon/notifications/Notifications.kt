package io.nisfeb.talon.notifications

/**
 * Platform-agnostic notification surface. Concrete implementations
 * live in androidMain (NotificationManager + NotificationCompat) and
 * desktopMain (java.awt.SystemTray, with graceful no-op when the
 * tray isn't supported on the host).
 *
 * Deep-link support varies: Android wraps each notification with a
 * PendingIntent that opens the right chat / thread. Desktop's
 * SystemTray notifications are display-only — taps don't route
 * anywhere meaningful for v1. Stage E or later may add desktop
 * deep-link support via TrayIcon ActionListener.
 */
interface Notifications {
    /**
     * Notification for a new chat message.
     *
     * @param whom chat ID (DM patp, club id, or `kind/host/name`)
     * @param postId the message id, used as the deep-link anchor on tap
     * @param parentId non-null when [postId] is a reply — the parent's id
     * @param title notification title (typically the sender's display name)
     * @param body notification body (the message text, summarized)
     * @param sentMs message send time, used for the notification's `when` field
     */
    fun showMessage(
        whom: String,
        postId: String?,
        parentId: String? = null,
        title: String,
        body: String,
        sentMs: Long,
    )

    /**
     * Notification for a watchword hit.
     */
    fun showWatchwordHit(
        whom: String,
        term: String,
        postId: String,
        title: String,
        body: String,
        sentMs: Long,
    )

    /**
     * Notification for the daily digest. Tap opens the digest screen.
     */
    fun showDailyDigest(
        title: String,
        body: String,
    )

    /**
     * Cancel all notifications associated with a chat. Called when the
     * chat is opened so the user doesn't see stale alerts. On desktop
     * tray notifications can't be cancelled after display — this is a
     * no-op.
     */
    fun cancelAllForChat(whom: String)
}
