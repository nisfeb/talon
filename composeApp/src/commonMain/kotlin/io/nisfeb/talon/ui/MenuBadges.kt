package io.nisfeb.talon.ui

/**
 * Per-RailItem freshness flags. Surfaces a badge dot on the rail
 * icon (via [DesktopShell]) and inside the kebab dropdown's
 * trailing icon (via DmListScreen's local computation). DmListScreen
 * still computes its own kebab signals from the same flows because
 * it also reads the underlying state for seen-marker writes — App.kt
 * computes a parallel set so the rail can show badges without
 * threading every data source through DmListScreen's call site.
 *
 * Default = no badges. The empty case is what fresh installs render
 * before any flows have collected.
 */
data class MenuBadges(
    val statusesFresh: Boolean = false,
    val digestFresh: Boolean = false,
    val invitesPending: Boolean = false,
) {
    /**
     * Read-site helper: returns true if [item]'s rail icon should
     * show a badge dot. Items without a freshness concept (Chats /
     * Bookmarks / Activity / Profile / Watchwords / Administration /
     * Settings) always return false.
     */
    fun forItem(item: RailItem): Boolean = when (item) {
        RailItem.Statuses -> statusesFresh
        RailItem.TodaysBrief -> digestFresh
        RailItem.Invites -> invitesPending
        else -> false
    }
}
