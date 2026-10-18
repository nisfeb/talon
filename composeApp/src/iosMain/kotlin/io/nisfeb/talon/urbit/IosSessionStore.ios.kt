package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.IosFiles
import io.nisfeb.talon.util.LockableFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** iOS session persistence — one JSON blob under Application Support. Mirrors
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
    // Built while the device is locked (a call's push), the file reads as
    // nothing; a save after unlock then wrote over every saved ship.
    private val file = LockableFile(
        SESSIONS_FILE,
        readText = { IosFiles.read(SESSIONS_FILE) },
        exists = { IosFiles.exists(SESSIONS_FILE) },
        writeText = { IosFiles.write(SESSIONS_FILE, it) },
    )
    private var loaded: SessionsBlob = load() ?: SessionsBlob()

    private fun load(): SessionsBlob? =
        file.text()?.let {
            runCatching { json.decodeFromString<SessionsBlob>(it) }.getOrNull()
        }

    /** The sessions, read again while the file has not been readable, so a
     *  store built while locked finds the ships once the device unlocks. */
    private var blob: SessionsBlob
        get() { if (!file.known) load()?.let { loaded = it }; return loaded }
        set(value) { loaded = value }

    private fun persist(overUnread: Boolean = false) {
        file.write(json.encodeToString(SessionsBlob.serializer(), loaded), overUnread)
    }

    override fun all(): List<SavedSession> = blob.sessions

    override fun active(): SavedSession? =
        blob.let { b -> b.activeShip?.let { a -> b.sessions.firstOrNull { it.ship == a } } ?: b.sessions.firstOrNull() }

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
        persist(overUnread = true)
    }
}
