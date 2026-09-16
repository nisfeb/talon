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
 * Android store's per-ship prefs file, mirrored). A draft is pinned to
 * the ship it was LOADED under: the composer saves on dispose, which
 * can fire a frame after a ship switch, and resolving the active ship
 * at save time writes the outgoing ship's text into the incoming
 * ship's file.
 */
class IosDraftStore(private val activeShip: () -> String?) : DraftStore() {

    /** Every loaded ship's drafts, keyed so a save can only ever land
     *  in the file the draft was composed under. */
    private val byShip = mutableMapOf<String, MutableMap<String, String>>()

    /** The ship a whom's draft was last loaded under. */
    private val loadedUnder = mutableMapOf<String, String>()

    private fun draftsFor(ship: String): MutableMap<String, String> =
        byShip.getOrPut(ship) {
            runCatching {
                IosFiles.read(fileFor(ship))?.let { Json.decodeFromString<Map<String, String>>(it) }
            }.getOrNull().orEmpty().toMutableMap()
        }

    override fun load(whom: String): String {
        val s = activeShip() ?: return ""
        loadedUnder[whom] = s
        backing.value = draftsFor(s).toMap()
        return draftsFor(s)[whom] ?: ""
    }

    override fun save(whom: String, draft: String) {
        // Pin, not re-resolve: a dispose-time save after a ship switch
        // belongs to the ship the draft was composed under.
        val s = loadedUnder[whom] ?: activeShip() ?: return
        if (draft.isBlank()) draftsFor(s).remove(whom) else draftsFor(s)[whom] = draft
        persist(s)
        if (s == activeShip()) backing.value = draftsFor(s).toMap()
    }

    override fun clear(whom: String) {
        val s = loadedUnder[whom] ?: activeShip() ?: return
        draftsFor(s).remove(whom)
        persist(s)
        if (s == activeShip()) backing.value = draftsFor(s).toMap()
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
