package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.AppDirs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed SessionStore for desktop. Stores sessions in the
 * platform-standard user-data dir (see [AppDirs]). Single-process —
 * no file locking, race-free for the common single-window use case.
 *
 * Threat model: cookieValue (the urbauth secret) is written in
 * cleartext. Desktop's threat model assumes a single-user device
 * with whole-disk encryption — same trade-off DesktopAiSettings
 * makes for the API key. A keychain-backed implementation
 * (macOS Keychain, libsecret, Windows DPAPI) is a future Stage F
 * follow-up if the threat model changes.
 */
class DesktopSessionStore(
    private val file: File = File(AppDirs.userData, "sessions.json"),
) : SessionStore {

    private fun load(): SessionsBlob = if (file.exists()) {
        runCatching { JSON.decodeFromString<SessionsBlob>(file.readText()) }
            .getOrElse { SessionsBlob(emptyList(), null) }
    } else {
        SessionsBlob(emptyList(), null)
    }

    /**
     * Write through a sibling .tmp file then ATOMIC_MOVE into place.
     * A JVM crash mid-write would otherwise leave a truncated JSON
     * blob and the user would silently appear logged out on next
     * launch. ATOMIC_MOVE is supported on every desktop FS we ship
     * to (ext4, APFS, NTFS).
     */
    private fun persist(blob: SessionsBlob) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(blob))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
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
        val remaining = cur.sessions.filter { it.ship != ship }
        // Match AndroidSessionStore: when the active ship is removed,
        // promote the first remaining ship rather than leaving the
        // user logged out entirely. See SessionStore interface KDoc.
        val newActive = when {
            cur.activeShip != ship -> cur.activeShip
            else -> remaining.firstOrNull()?.ship
        }
        persist(cur.copy(sessions = remaining, activeShip = newActive))
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
