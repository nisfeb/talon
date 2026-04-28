package io.nisfeb.talon.urbit

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON-file-backed SessionStore for desktop. Stores sessions at
 * ${user.home}/.config/talon/sessions.json. Single-process — no
 * file locking, race-free for the common single-window use case.
 */
class DesktopSessionStore : SessionStore {

    private val file: File by lazy {
        val home = System.getProperty("user.home")
            ?: error("user.home system property not set")
        File(home, ".config/talon/sessions.json").also {
            it.parentFile?.mkdirs()
        }
    }

    private fun load(): SessionsBlob = if (file.exists()) {
        runCatching { JSON.decodeFromString<SessionsBlob>(file.readText()) }
            .getOrElse { SessionsBlob(emptyList(), null) }
    } else {
        SessionsBlob(emptyList(), null)
    }

    private fun persist(blob: SessionsBlob) {
        file.writeText(JSON.encodeToString(blob))
    }

    override fun all(): List<SavedSession> = load().sessions.sortedBy { it.ship }

    override fun active(): SavedSession? {
        val b = load()
        return b.sessions.firstOrNull { it.ship == b.activeShip }
    }

    override fun activeShip(): String? = load().activeShip

    override fun save(entry: SavedSession, makeActive: Boolean) {
        val cur = load()
        val sessions = cur.sessions.filter { it.ship != entry.ship } + entry
        persist(cur.copy(
            sessions = sessions,
            activeShip = if (makeActive) entry.ship else cur.activeShip,
        ))
    }

    override fun setActive(ship: String) {
        val cur = load()
        if (cur.sessions.none { it.ship == ship }) return
        persist(cur.copy(activeShip = ship))
    }

    override fun remove(ship: String) {
        val cur = load()
        persist(cur.copy(
            sessions = cur.sessions.filter { it.ship != ship },
            activeShip = if (cur.activeShip == ship) null else cur.activeShip,
        ))
    }

    override fun clearAll() {
        persist(SessionsBlob(emptyList(), null))
    }

    @Serializable
    private data class SessionsBlob(
        val sessions: List<SavedSession>,
        val activeShip: String?,
    )

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
