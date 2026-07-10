package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.IosFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** iOS session persistence — one JSON blob under Documents. Mirrors
 *  DesktopSessionStore: active-ship pointer plus the saved sessions,
 *  promoting the first remaining ship when the active one is removed. */
fun createSessionStore(): SessionStore = IosSessionStore()

private const val SESSIONS_FILE = "sessions.json"

@Serializable
private data class SessionsBlob(
    val sessions: List<SavedSession> = emptyList(),
    val activeShip: String? = null,
)

class IosSessionStore : SessionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private var blob: SessionsBlob = load()

    private fun load(): SessionsBlob =
        IosFiles.read(SESSIONS_FILE)?.let {
            runCatching { json.decodeFromString<SessionsBlob>(it) }.getOrNull()
        } ?: SessionsBlob()

    private fun persist() {
        IosFiles.write(SESSIONS_FILE, json.encodeToString(SessionsBlob.serializer(), blob))
    }

    override fun all(): List<SavedSession> = blob.sessions

    override fun active(): SavedSession? =
        blob.activeShip?.let { a -> blob.sessions.firstOrNull { it.ship == a } }
            ?: blob.sessions.firstOrNull()

    override fun activeShip(): String? = active()?.ship

    override fun save(entry: SavedSession, makeActive: Boolean) {
        val others = blob.sessions.filterNot { it.ship == entry.ship }
        blob = blob.copy(
            sessions = others + entry,
            activeShip = if (makeActive) entry.ship else blob.activeShip,
        )
        persist()
    }

    override fun setActive(ship: String) {
        if (blob.sessions.any { it.ship == ship }) {
            blob = blob.copy(activeShip = ship)
            persist()
        }
    }

    override fun remove(ship: String) {
        val remaining = blob.sessions.filterNot { it.ship == ship }
        val newActive =
            if (blob.activeShip == ship) remaining.firstOrNull()?.ship else blob.activeShip
        blob = SessionsBlob(remaining, newActive)
        persist()
    }

    override fun clearAll() {
        blob = SessionsBlob()
        persist()
    }
}
