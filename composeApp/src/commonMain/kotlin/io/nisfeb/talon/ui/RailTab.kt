package io.nisfeb.talon.ui

/**
 * Surfaces that the desktop / tablet-landscape rail can switch the
 * left pane between. Order matches the rail's icon stacking order
 * (top to bottom). Adding a fifth value here is the single point of
 * change for landing a new rail surface — `DesktopShell`'s list
 * resolver and the rail icon list both fan out from this enum.
 */
enum class RailTab { Chats, Statuses, Bookmarks, Activity }

/**
 * Persistence helper. `RailTab.valueOf(name)` throws on unknown
 * names; this version falls back to [Chats] on any parse failure
 * (corrupt JSON / SharedPrefs entry, removed enum value, etc.) so
 * a bad value on disk never blows up the app.
 */
fun railTabOrDefault(name: String?): RailTab {
    if (name.isNullOrBlank()) return RailTab.Chats
    return runCatching { RailTab.valueOf(name) }.getOrDefault(RailTab.Chats)
}
