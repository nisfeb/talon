package io.nisfeb.talon.ui

import kotlin.concurrent.Volatile
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

    /** [state] for the ship active now: called when that changes. */
    open fun showActiveShip() {}

    /** Drop what is held for [ship], once its files are erased. */
    open fun forget(ship: String) {}
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
    // Whether a file is there, readable or not. iOS keeps it locked
    // while the device is, and a read then is not an empty file.
    private val exists: (name: String) -> Boolean = { false },
) : DraftStore() {

    /** Every loaded ship's drafts, keyed so a save can only ever land
     *  in the file the draft was composed under. Replaced whole, never
     *  changed in place: [forget] comes from the thread that erased. */
    @Volatile
    private var byShip: Map<String, Map<String, String>> = emptyMap()

    private fun draftsFor(ship: String): Map<String, String> = loaded(ship).orEmpty()

    /** The ship's drafts, or null while its file is there but cannot be
     *  read: kept as empty, a save then wrote over every other draft. */
    private fun loaded(ship: String): Map<String, String>? {
        byShip[ship]?.let { return it }
        val name = draftsFileName(ship)
        val text = runCatching { read(name) }.getOrNull()
        if (text == null && runCatching { exists(name) }.getOrDefault(false)) return null
        return runCatching { text?.let { Json.decodeFromString<Map<String, String>>(it) } }
            .getOrNull().orEmpty().also { byShip = byShip + (ship to it) }
    }

    private fun change(ship: String, edit: (Map<String, String>) -> Map<String, String>): Boolean {
        val now = loaded(ship) ?: return false
        byShip = byShip + (ship to edit(now))
        return true
    }

    // The list's "Draft:" previews were empty after a launch and the last
    // ship's after a switch, until a composer happened to load one.
    override fun showActiveShip() {
        backing.value = activeShip()?.let { draftsFor(it) }.orEmpty()
    }

    // Erased, a ship's drafts came back from here when it was added again.
    override fun forget(ship: String) {
        byShip = byShip - ship
        if (ship == activeShip()) backing.value = emptyMap()
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
        if (ship == activeShip()) backing.value = draftsFor(ship)
        return draftsFor(ship)[whom] ?: ""
    }

    private fun saveFor(ship: String, whom: String, draft: String) {
        if (!change(ship) { if (draft.isBlank()) it - whom else it + (whom to draft) }) return
        persist(ship)
        // The list's "Draft:" previews follow the active ship only.
        if (ship == activeShip()) backing.value = draftsFor(ship)
    }

    private fun clearFor(ship: String, whom: String) {
        if (!change(ship) { it - whom }) return
        persist(ship)
        if (ship == activeShip()) backing.value = draftsFor(ship)
    }

    private fun persist(ship: String) {
        runCatching { write(draftsFileName(ship), Json.encodeToString(draftsFor(ship))) }
    }
}

/** The per-ship drafts file name, shared with the ship-data erasers. */
fun draftsFileName(ship: String): String =
    "drafts-${ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")}.json"
