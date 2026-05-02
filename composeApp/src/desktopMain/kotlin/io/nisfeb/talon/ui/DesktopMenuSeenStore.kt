package io.nisfeb.talon.ui

import io.nisfeb.talon.util.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed [MenuSeenStore] for desktop. Per-ship file in the
 * user-data dir so switching ships doesn't bleed seen-state across.
 * Atomic-move write so a JVM crash mid-write can't truncate the
 * file. Mirrors [DesktopUiSettings].
 */
class DesktopMenuSeenStore(
    ship: String,
    file: File = defaultFile(ship),
) : MenuSeenStore {

    @Serializable
    private data class Persisted(
        val lastSeenDigestDate: String? = null,
        val lastSeenStatusesMs: Long = 0L,
        val lastSeenInvitesSnapshot: String = "",
    )

    private val target: File = file
    private val _state = MutableStateFlow(loadInitial())
    override val state: StateFlow<MenuSeenState> = _state.asStateFlow()

    override fun markDigestSeen(dateLocal: String?) {
        val next = _state.value.copy(lastSeenDigestDate = dateLocal)
        _state.value = next
        persist(next)
    }

    override fun markStatusesSeenAt(ms: Long) {
        val next = _state.value.copy(lastSeenStatusesMs = ms)
        _state.value = next
        persist(next)
    }

    override fun markInvitesSeen(snapshot: String) {
        val next = _state.value.copy(lastSeenInvitesSnapshot = snapshot)
        _state.value = next
        persist(next)
    }

    private fun loadInitial(): MenuSeenState {
        if (!target.exists()) return MenuSeenState()
        return runCatching {
            val p = JSON.decodeFromString<Persisted>(target.readText())
            MenuSeenState(
                lastSeenDigestDate = p.lastSeenDigestDate,
                lastSeenStatusesMs = p.lastSeenStatusesMs,
                lastSeenInvitesSnapshot = p.lastSeenInvitesSnapshot,
            )
        }.getOrElse { MenuSeenState() }
    }

    private fun persist(value: MenuSeenState) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(
            JSON.encodeToString(
                Persisted(
                    lastSeenDigestDate = value.lastSeenDigestDate,
                    lastSeenStatusesMs = value.lastSeenStatusesMs,
                    lastSeenInvitesSnapshot = value.lastSeenInvitesSnapshot,
                ),
            ),
        )
        Files.move(
            tmp.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
        fun defaultFile(ship: String): File {
            val safe = ship.removePrefix("~")
                .replace(Regex("[^a-z0-9-]"), "_")
            return File(AppDirs.userData, "menuseen-$safe.json")
        }
    }
}
