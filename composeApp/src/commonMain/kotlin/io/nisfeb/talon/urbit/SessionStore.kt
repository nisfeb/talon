package io.nisfeb.talon.urbit

import kotlinx.serialization.Serializable

/** A saved session for one ship. */
@Serializable
data class SavedSession(
    val shipUrl: String,
    val ship: String,       // "~patp"
    val cookieName: String, // e.g. "urbauth-~patp"
    val cookieValue: String,
    val cookieDomain: String,
)

/**
 * Platform-agnostic interface for persisting Urbit login state.
 *
 * The Android implementation (backed by SharedPreferences) lives in
 * app/src/main/java/io/nisfeb/talon/urbit/SessionStore.kt and is used
 * directly in the app module. Desktop and other targets will provide
 * their own implementations in Stage C.
 *
 * This interface lives in commonMain so that UrbitSession (also
 * commonMain) can reference it without pulling in Android APIs.
 */
interface SessionStore {
    /** Every saved ship, ordered by patp. */
    fun all(): List<SavedSession>

    /** The currently-active ship's session, or null if none. */
    fun active(): SavedSession?

    /** The active ship's @p, or null if no session is selected. */
    fun activeShip(): String?

    /**
     * Persist or update a ship's session. When [makeActive] is true
     * (the default), the saved entry also becomes the active ship.
     */
    fun save(entry: SavedSession, makeActive: Boolean = true)

    /** Switch the active pointer. Silently no-ops if [ship] isn't stored. */
    fun setActive(ship: String)

    /** Remove a ship's session. */
    fun remove(ship: String)

    /** Wipe all saved sessions. */
    fun clearAll()
}
