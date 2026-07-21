package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.NotesFolderEntity

/**
 * Folder-tree arithmetic for %notes notebooks.
 *
 * Pure so it can be tested without a ship or a composition — the move
 * rules are where this gets dangerous. Re-parenting a folder under its
 * own descendant would detach that whole branch from the root: the rows
 * still exist, but nothing walking down from `/` can reach them, so the
 * notes inside simply vanish from the UI. Callers must offer only
 * [moveDestinations].
 */
object NotesTree {

    /** Guards against a cycle already present in the data. */
    private const val MAX_DEPTH = 64

    /** [folderId] plus everything beneath it. */
    fun descendants(folders: List<NotesFolderEntity>, folderId: Long): Set<Long> {
        val out = mutableSetOf(folderId)
        // Repeat until nothing new is reachable; folder lists are tiny,
        // and this tolerates arbitrary ordering.
        var changed = true
        var guard = 0
        while (changed && guard++ < MAX_DEPTH) {
            changed = false
            for (f in folders) {
                val parent = f.parentFolderId ?: continue
                if (parent in out && out.add(f.folderId)) changed = true
            }
        }
        return out
    }

    /**
     * Folders [folderId] may legally move into: everything except itself,
     * anything beneath it, and the parent it already has (a no-op that
     * only adds noise to the picker).
     */
    fun moveDestinations(
        folders: List<NotesFolderEntity>,
        folderId: Long,
    ): List<NotesFolderEntity> {
        val blocked = descendants(folders, folderId)
        val currentParent = folders.firstOrNull { it.folderId == folderId }?.parentFolderId
        return folders
            .filter { it.folderId !in blocked && it.folderId != currentParent }
            .sortedBy { path(folders, it.folderId).lowercase() }
    }

    /**
     * Display path, e.g. `/` for the root or `/notes/drafts`. Built by
     * walking parents, so it stays right regardless of list order.
     */
    fun path(folders: List<NotesFolderEntity>, folderId: Long): String {
        val byId = folders.associateBy { it.folderId }
        val parts = ArrayDeque<String>()
        var cur = byId[folderId]
        var guard = 0
        while (cur != null && guard++ < MAX_DEPTH) {
            // The root's own name is "/" on the wire; the leading slash
            // in the result already represents it.
            val parent = cur.parentFolderId ?: break
            parts.addFirst(cur.name)
            cur = byId[parent]
        }
        return if (parts.isEmpty()) "/" else parts.joinToString("/", prefix = "/")
    }
}
