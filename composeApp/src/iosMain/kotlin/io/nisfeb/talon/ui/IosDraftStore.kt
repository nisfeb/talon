package io.nisfeb.talon.ui

import io.nisfeb.talon.util.IosFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Drafts on disk, one file per ship next to the other per-app files.
 *
 * iOS used to hand App a fresh InMemoryDraftStore from inside the
 * ComposeUIViewController lambda — a new, empty store on every
 * recomposition, which is why leaving a chat lost what you'd typed.
 * One instance, created once, fixed that; this one also keeps drafts
 * across a relaunch, as the Android store does.
 *
 * Per-ship files so ship-switch hides the other ship's drafts (the
 * Android store's per-ship prefs file, mirrored). The active ship is
 * re-checked on every access rather than cached at construction:
 * switching ships must not keep writing to the old ship's file.
 */
class IosDraftStore(private val activeShip: () -> String?) : DraftStore() {
    private var ship: String? = null
    private var drafts: MutableMap<String, String> = mutableMapOf()

    init { reload() }

    /** Swap the in-memory map when the active ship changed. */
    private fun reload() {
        val now = activeShip()
        if (now == ship) return
        ship = now
        drafts = now?.let { s ->
            runCatching {
                IosFiles.read(fileFor(s))?.let { Json.decodeFromString<Map<String, String>>(it) }
            }.getOrNull()
        }.orEmpty().toMutableMap()
        backing.value = drafts.toMap()
    }

    override fun load(whom: String): String {
        reload()
        return drafts[whom] ?: ""
    }

    override fun save(whom: String, draft: String) {
        reload()
        if (draft.isBlank()) drafts.remove(whom) else drafts[whom] = draft
        persist()
    }

    override fun clear(whom: String) {
        reload()
        drafts.remove(whom)
        persist()
    }

    private fun persist() {
        backing.value = drafts.toMap()
        val s = ship ?: return
        runCatching { IosFiles.write(fileFor(s), Json.encodeToString(drafts.toMap())) }
    }

    companion object {
        /** The per-ship drafts file name — shared with the ship-data eraser. */
        fun fileFor(ship: String): String = "drafts-${sanitize(ship)}.json"

        /** A ship as a file name: no `~`, nothing a path minds. */
        private fun sanitize(ship: String): String =
            ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")
    }
}
