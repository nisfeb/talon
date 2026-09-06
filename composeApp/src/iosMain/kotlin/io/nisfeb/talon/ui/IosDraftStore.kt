package io.nisfeb.talon.ui

import io.nisfeb.talon.util.IosFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Drafts on disk, next to the other per-app files.
 *
 * iOS used to hand App a fresh InMemoryDraftStore from inside the
 * ComposeUIViewController lambda — a new, empty store on every
 * recomposition, which is why leaving a chat lost what you'd typed.
 * One instance, created once, would have fixed that; this one also
 * keeps drafts across a relaunch, as the Android store does.
 */
class IosDraftStore : DraftStore() {
    private val drafts: MutableMap<String, String> = runCatching {
        IosFiles.read(FILE)?.let { Json.decodeFromString<Map<String, String>>(it) }
    }.getOrNull().orEmpty().toMutableMap()

    init { backing.value = drafts.toMap() }

    override fun load(whom: String): String = drafts[whom] ?: ""

    override fun save(whom: String, draft: String) {
        if (draft.isBlank()) drafts.remove(whom) else drafts[whom] = draft
        persist()
    }

    override fun clear(whom: String) {
        drafts.remove(whom)
        persist()
    }

    private fun persist() {
        backing.value = drafts.toMap()
        runCatching { IosFiles.write(FILE, Json.encodeToString(drafts.toMap())) }
    }

    private companion object {
        const val FILE = "drafts.json"
    }
}
