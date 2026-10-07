package io.nisfeb.talon.relay

/**
 * Talon's per-chat notification level, as the app stores it in the
 * ship's %settings (desk `talon`, bucket `notify-prefs`, one entry per
 * whom with `{"level": ...}`). The ship's own %activity `notified` flag
 * already gates what reaches the relay; this applies the user's
 * explicit choice on top, the way the Android app does in-process.
 */
object NotifyPolicy {
    const val ALL = "all"
    const val MENTIONS = "mentions"
    const val NONE = "none"

    /**
     * @param level the level that applies ([resolve]), or null when none
     *   is set for the chat or its group: then "mentions", as the app
     *   shows it. Null used to let the ship's flag alone decide, and
     *   %activity flags every channel post, so a relay pushed every post
     *   in every room the user had not set (a user's report, 2026-10-07;
     *   trunk had the same until gwbtc/trunk#7).
     * @param mention whether the event names this ship.
     * @param reply whether it is a reply %activity flagged: it flags one
     *   only in a thread we wrote, replied in or were mentioned in, so it
     *   passes "mentions", as the app's replyNotification and trunk do.
     */
    fun allows(whom: String, level: String?, mention: Boolean, reply: Boolean = false): Boolean = when (level ?: MENTIONS) {
        NONE -> false
        // A DM or club message is addressed to you; "mentions" there
        // means "not for every group post", not silence.
        MENTIONS -> mention || reply || !whom.startsWith("chat/") && !whom.contains('/')
        else -> true
    }

    /**
     * The level that applies to [whom] and where it came from: its own,
     * else its group's (kept under "group/<flag>" since Talon 1.8.3),
     * else none. The same rule as the app's and trunk's (wire 15).
     */
    fun resolve(levels: Map<String, String>, whom: String, group: String?): Pair<String?, String> =
        levels[whom]?.let { it to "own" }
            ?: group?.let { g -> levels["group/$g"]?.let { it to "group" } }
            ?: (null to "default")
}
