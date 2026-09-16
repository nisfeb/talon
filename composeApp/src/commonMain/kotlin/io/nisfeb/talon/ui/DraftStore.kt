package io.nisfeb.talon.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-progress composer text, keyed by conversation `whom`. Exposes
 * the full draft map as a StateFlow so the DM list can show
 * per-conversation "Draft: …" previews without re-reading storage on
 * every row render.
 *
 * The Android-side production implementation persists drafts via
 * SharedPreferences. The desktop default is an in-memory store; a
 * persistent desktop backing comes in Stage F.
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
     * composer instance, which is what this handle is. Stores with one
     * ship's drafts, or none, are their own handle.
     */
    open fun bound(): DraftStore = this
}

/**
 * In-memory DraftStore — desktop default. Loses drafts on relaunch;
 * Stage F replaces this with a file-backed implementation.
 */
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
