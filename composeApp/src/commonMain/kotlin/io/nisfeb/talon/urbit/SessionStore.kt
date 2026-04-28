package io.nisfeb.talon.urbit

/** A saved session for one ship. */
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

    /** Persist or update a ship's session. */
    fun save(entry: SavedSession)

    /** Switch the active pointer. Silently no-ops if [ship] isn't stored. */
    fun setActive(ship: String)

    /** Remove a ship's session. */
    fun remove(ship: String)

    /** Wipe all saved sessions. */
    fun clearAll()
}
