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
     * @param level the stored level, or null when the user never set
     *   one for this chat — then the ship's flag alone decides.
     * @param mention whether the event names this ship.
     */
    fun allows(whom: String, level: String?, mention: Boolean): Boolean = when (level) {
        NONE -> false
        // A DM or club message is addressed to you; "mentions" there
        // means "not for every group post", not silence.
        MENTIONS -> mention || !whom.startsWith("chat/") && !whom.contains('/')
        else -> true
    }
}
