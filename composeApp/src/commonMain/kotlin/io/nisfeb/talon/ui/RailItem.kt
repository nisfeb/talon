package io.nisfeb.talon.ui

/**
 * Every clickable entry on the desktop rail. Superset of [RailTab]:
 * pane-tab items (Chats / Statuses / Bookmarks / Activity) still swap
 * the left pane via the existing activeRailTab plumbing; modal items
 * fire the same kebab handlers they always have.
 *
 * Order is canonical (declaration order). Reorder UI is deferred —
 * see the spec's "Out of scope" section.
 *
 * Sign out is intentionally not a RailItem — it's destructive and
 * stays exclusively in the kebab dropdown.
 */
enum class RailItem(val isPaneTab: Boolean) {
    Chats(true),
    Statuses(true),
    Bookmarks(true),
    Activity(true),
    Profile(false),
    Watchwords(false),
    TodaysBrief(false),
    Administration(false),
    Invites(false),
    Settings(false),
    ;

    /**
     * Maps a pane-tab item to its [RailTab] counterpart for the
     * existing activeRailTab plumbing. Returns null for modal items
     * (which don't have a "selected" state).
     */
    fun toRailTab(): RailTab? = if (isPaneTab) RailTab.valueOf(name) else null
}

/**
 * Persistence helper. [RailItem.valueOf] throws on unknown names; this
 * version returns null on any parse failure so a future enum rename
 * doesn't blow up rows / wire entries that pre-date the change.
 */
fun railItemOrNull(name: String?): RailItem? {
    if (name.isNullOrBlank()) return null
    return runCatching { RailItem.valueOf(name) }.getOrNull()
}

/**
 * Read-site helper: default-visible if not in the map. The Map is
 * sparse — only contains rows for items the user has explicitly
 * hidden.
 *
 * [RailItem.Chats] is enforced always-visible regardless of the
 * map's state. The Settings sub-page already gates the toggle for
 * Chats (renders an "On" badge instead of a Switch), but the
 * underlying state isn't otherwise prevented from holding
 * `Chats=false` — direct DB tampering, a forward-compat scenario
 * where another client renamed Chats and pushed `Chats=false`,
 * or a bug elsewhere could otherwise strand the user with no rail
 * icon for the chat list and no kebab "Chats" entry to fall back
 * to. Forcing the invariant here means both the rail filter and
 * the Settings UI can never show Chats as hidden.
 */
fun Map<RailItem, Boolean>.isVisible(item: RailItem): Boolean {
    if (item == RailItem.Chats) return true
    return this[item] ?: true
}

/**
 * Sanitize a (possibly stale) saved rail order against the current
 * [RailItem.entries]. Future enum additions get appended at their
 * declaration position so users on older configs see new items the
 * first time they upgrade — no manual reset. Removed values are
 * dropped silently. Duplicates collapse. Result is always exactly
 * the current [RailItem] universe in some order.
 */
fun sanitizeRailItemOrder(saved: List<RailItem>): List<RailItem> {
    val seen = LinkedHashSet<RailItem>(saved.size)
    for (item in saved) seen.add(item)  // de-dup, preserve order
    val universe = RailItem.entries
    val result = ArrayList<RailItem>(universe.size)
    for (item in seen) if (item in universe) result.add(item)
    for (item in universe) if (item !in result) result.add(item)
    return result
}
