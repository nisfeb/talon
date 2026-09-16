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
 * Android store's per-ship prefs file, mirrored). The composer saves
 * on dispose, which can fire a frame after a ship switch, so it works
 * through [bound]: a handle fixed to the ship that was active when the
 * composer was built. Pinning by conversation instead did not hold,
 * because the incoming ship's composer loads the same conversation
 * first and re-points the pin before the outgoing one saves.
 */
class IosDraftStore(private val activeShip: () -> String?) : DraftStore() {

    /** Every loaded ship's drafts, keyed so a save can only ever land
     *  in the file the draft was composed under. */
    private val byShip = mutableMapOf<String, MutableMap<String, String>>()

    private fun draftsFor(ship: String): MutableMap<String, String> =
        byShip.getOrPut(ship) {
            runCatching {
                IosFiles.read(fileFor(ship))?.let { Json.decodeFromString<Map<String, String>>(it) }
            }.getOrNull().orEmpty().toMutableMap()
        }

    override fun load(whom: String): String = activeShip()?.let { loadFor(it, whom) } ?: ""

    override fun save(whom: String, draft: String) {
        saveFor(activeShip() ?: return, whom, draft)
    }

    override fun clear(whom: String) {
        clearFor(activeShip() ?: return, whom)
    }

    override fun bound(): DraftStore = activeShip()?.let { Bound(it) } ?: this

    /** The store as one ship sees it, whatever the active ship becomes later. */
    private inner class Bound(private val ship: String) : DraftStore() {
        override fun load(whom: String): String = loadFor(ship, whom)
        override fun save(whom: String, draft: String) = saveFor(ship, whom, draft)
        override fun clear(whom: String) = clearFor(ship, whom)
    }

    private fun loadFor(ship: String, whom: String): String {
        if (ship == activeShip()) backing.value = draftsFor(ship).toMap()
        return draftsFor(ship)[whom] ?: ""
    }

    private fun saveFor(ship: String, whom: String, draft: String) {
        if (draft.isBlank()) draftsFor(ship).remove(whom) else draftsFor(ship)[whom] = draft
        persist(ship)
        // The list's "Draft:" previews follow the active ship only.
        if (ship == activeShip()) backing.value = draftsFor(ship).toMap()
    }

    private fun clearFor(ship: String, whom: String) {
        draftsFor(ship).remove(whom)
        persist(ship)
        if (ship == activeShip()) backing.value = draftsFor(ship).toMap()
    }

    private fun persist(ship: String) {
        runCatching { IosFiles.write(fileFor(ship), Json.encodeToString(draftsFor(ship).toMap())) }
    }

    companion object {
        /** The per-ship drafts file name — shared with the ship-data eraser. */
        fun fileFor(ship: String): String = "drafts-${sanitize(ship)}.json"

        /** A ship as a file name: no `~`, nothing a path minds. */
        private fun sanitize(ship: String): String =
            ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")
    }
}
