package io.nisfeb.talon.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * In-progress composer text, keyed by conversation `whom`. Exposes
 * the full draft map as a StateFlow so the DM list can show
 * per-conversation "Draft: …" previews without re-reading storage on
 * every row render.
 *
 * Android persists drafts via SharedPreferences (AndroidDraftStore);
 * desktop and iOS in a file per ship ([FileDraftStore]).
 */
abstract class DraftStore {
    protected val backing: MutableStateFlow<Map<String, String>> =
        MutableStateFlow(emptyMap())

    val state: StateFlow<Map<String, String>> = backing.asStateFlow()

    abstract fun load(whom: String): String
    abstract fun save(whom: String, draft: String)
    abstract fun clear(whom: String)

    /**
     * This store, fixed to whichever ship is active right now.
     *
     * A composer saves on dispose, and that can fire a frame after a
     * ship switch: by then the incoming ship's composer has already
     * loaded the same conversation, so anything keyed by conversation
     * has been re-pointed at the new ship. The pin has to belong to the
     * composer instance, which is what this handle is. Stores built
     * per ship (Android) are their own handle. InMemoryDraftStore is
     * too, but only because it scopes nothing: it holds every ship's
     * drafts mixed, so there is no ship to pin to.
     */
    open fun bound(): DraftStore = this
}

/** In-memory DraftStore, for tests and previews. Loses drafts on relaunch. */
class InMemoryDraftStore : DraftStore() {
    private val drafts: MutableMap<String, String> = mutableMapOf()

    override fun load(whom: String): String = drafts[whom] ?: ""

    override fun save(whom: String, draft: String) {
        if (draft.isBlank()) {
            drafts.remove(whom)
        } else {
            drafts[whom] = draft
        }
        backing.value = drafts.toMap()
    }

    override fun clear(whom: String) {
        drafts.remove(whom)
        backing.value = drafts.toMap()
    }
}

/**
 * Drafts in a file per ship, [read] and [write] by file name: desktop's
 * and iOS's store, so they are kept across a relaunch or an update as
 * Android's are.
 *
 * Per-ship files so a ship switch hides the other ship's drafts (the
 * Android store's per-ship prefs file, mirrored). The composer saves
 * on dispose, which can fire a frame after a ship switch, so it works
 * through [bound]: a handle fixed to the ship that was active when the
 * composer was built. Pinning by conversation instead did not hold,
 * because the incoming ship's composer loads the same conversation
 * first and re-points the pin before the outgoing one saves.
 */
open class FileDraftStore(
    private val activeShip: () -> String?,
    private val read: (name: String) -> String?,
    private val write: (name: String, text: String) -> Unit,
) : DraftStore() {

    /** Every loaded ship's drafts, keyed so a save can only ever land
     *  in the file the draft was composed under. */
    private val byShip = mutableMapOf<String, MutableMap<String, String>>()

    private fun draftsFor(ship: String): MutableMap<String, String> =
        byShip.getOrPut(ship) {
            runCatching {
                read(draftsFileName(ship))?.let { Json.decodeFromString<Map<String, String>>(it) }
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
        runCatching { write(draftsFileName(ship), Json.encodeToString(draftsFor(ship).toMap())) }
    }
}

/** The per-ship drafts file name, shared with the ship-data erasers. */
fun draftsFileName(ship: String): String =
    "drafts-${ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")}.json"
