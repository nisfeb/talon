package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.NotesFolderEntity
import io.nisfeb.talon.data.NotesNoteEntity
import io.nisfeb.talon.data.NotesNotebookEntity
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client for the %notes agent — Tlon v12's Markdown "Notebook" channels.
 *
 * %notes is a separate agent from %channels, so this owns its own
 * scry/subscribe/poke surface rather than riding the chat plumbing. It
 * attaches to the same [UrbitChannel] the session loop already holds
 * (mirroring [SettingsSync]): the session calls [attach] + [bootstrap]
 * on connect, and routes matching SSE facts here via [applyNotesEvent].
 *
 * Room is the UI's source of truth; every read below writes through it
 * and screens observe Flows, so the UI is unaffected by whether the
 * ship is reachable.
 */
class NotesRepo(
    private val db: AppDatabase,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private var channel: UrbitChannel? = null

    /** Notebooks we've opened a /stream subscription for this session. */
    private val subscribed = mutableSetOf<String>()
    private val subLock = Mutex()

    fun attach(ch: UrbitChannel) {
        channel = ch
        // Subscriptions are per-channel; a reconnect means a fresh
        // channel, so forget what we thought we were watching.
        subscribed.clear()
    }

    fun detach() {
        channel = null
        subscribed.clear()
    }

    // ---- reads ---------------------------------------------------------

    fun streamNotebooks(): Flow<List<NotesNotebookEntity>> = db.notes().streamNotebooks()
    fun streamNotebook(flag: NotesFlag) = db.notes().streamNotebook(flag.flagString)
    fun streamFolders(flag: NotesFlag) = db.notes().streamFolders(flag.flagString)
    fun streamNotes(flag: NotesFlag) = db.notes().streamNotes(flag.flagString)
    fun streamNote(flag: NotesFlag, noteId: Long) = db.notes().streamNote(flag.flagString, noteId)

    /**
     * Pull the notebook list. Safe to call on every reconnect: it
     * replaces the notebook rows and (re)subscribes to each stream.
     */
    suspend fun bootstrap() {
        val ch = channel ?: return
        val body = runCatching { ch.scry(NotesPaths.APP, NotesPaths.NOTEBOOKS) }
            .onFailure {
                // A ship on webapp <v12 has no %notes agent at all. That's
                // the expected state until the user updates, so don't
                // escalate — just leave the cache empty.
                Log.i(TAG, "notes bootstrap skipped (no %notes agent?): ${it.message}")
            }
            .getOrNull() ?: return

        val summaries = NotesParser.notebookSummaries(body)
        Log.i(TAG, "notes bootstrap: ${summaries.size} notebook(s)")
        db.notes().upsertNotebooks(
            summaries.map { s ->
                NotesNotebookEntity(
                    flag = s.flag.flagString,
                    notebookId = s.notebook.id,
                    title = s.notebook.title,
                    rootFolderId = s.notebook.rootFolderId,
                    visibility = if (s.visibility == NotesVisibility.Public) "public" else "private",
                    createdBy = s.notebook.createdBy,
                    createdAtMs = s.notebook.createdAtMs,
                    updatedBy = s.notebook.updatedBy,
                    updatedAtMs = s.notebook.updatedAtMs,
                )
            },
        )
        summaries.forEach { s ->
            ensureSubscribed(s.flag)
            refreshNotebook(s.flag)
        }
    }

    /**
     * Join [flag] unless we already have it. %notes only serves notebooks
     * in our own `books` map — a group channel someone else created isn't
     * there until we join, and scrying one we haven't joined doesn't
     * return empty, it fails the scry outright. So opening a notes
     * channel from a group has to join first; that's what tapping the
     * channel means, and it's idempotent once joined.
     */
    suspend fun ensureJoined(flag: NotesFlag) {
        if (db.notes().notebook(flag.flagString) != null) {
            ensureSubscribed(flag)
            return
        }
        Log.i(TAG, "joining notes channel ${flag.flagString}")
        joinNotebook(flag)
    }

    /** Re-read one notebook's folder tree + notes and swap it in. */
    suspend fun refreshNotebook(flag: NotesFlag) {
        val ch = channel ?: return
        val key = flag.flagString
        val foldersJson = runCatching { ch.scry(NotesPaths.APP, NotesPaths.folders(flag)) }
            .getOrElse {
                Log.w(TAG, "notes folders scry failed for $key", it)
                return
            }
        val notesJson = runCatching { ch.scry(NotesPaths.APP, NotesPaths.notes(flag)) }
            .getOrElse {
                Log.w(TAG, "notes scry failed for $key", it)
                return
            }

        val folders = NotesParser.folders(foldersJson).map { f ->
            NotesFolderEntity(
                flag = key,
                folderId = f.id,
                name = f.name,
                parentFolderId = f.parentFolderId,
                createdBy = f.createdBy,
                createdAtMs = f.createdAtMs,
                updatedBy = f.updatedBy,
                updatedAtMs = f.updatedAtMs,
            )
        }
        val notes = NotesParser.notes(notesJson).map { n ->
            NotesNoteEntity(
                flag = key,
                noteId = n.id,
                folderId = n.folderId,
                title = n.title,
                slug = n.slug,
                bodyMd = n.bodyMd,
                createdBy = n.createdBy,
                createdAtMs = n.createdAtMs,
                updatedBy = n.updatedBy,
                updatedAtMs = n.updatedAtMs,
                revision = n.revision,
            )
        }
        db.notes().replaceTree(key, folders, notes)
    }

    private suspend fun ensureSubscribed(flag: NotesFlag) {
        val ch = channel ?: return
        val key = flag.flagString
        subLock.withLock {
            if (!subscribed.add(key)) return
        }
        runCatching { ch.subscribe(NotesPaths.APP, NotesPaths.stream(flag)) }
            .onFailure {
                subLock.withLock { subscribed.remove(key) }
                Log.w(TAG, "notes subscribe failed for $key", it)
            }
    }

    // ---- events --------------------------------------------------------

    /**
     * Handle one %notes stream fact.
     *
     * ponytail: every event just re-scries the affected notebook rather
     * than applying the ~15 typed deltas (note-created, folder-update,
     * member-joined, …). The host stays the source of truth and one code
     * path covers every variant; the ceiling is one scry per update
     * burst, which is fine for documents but would be wrong for chat
     * traffic. If notebooks ever get chatty, parse the deltas in
     * NotesParser and apply them in place.
     */
    suspend fun applyNotesEvent(payload: JsonObject) {
        val host = payload["host"].asStr() ?: return
        val name = payload["flagName"].asStr() ?: return
        val flag = NotesFlag(host, name)
        when (payload["type"].asStr()) {
            "notebook-deleted" -> {
                val key = flag.flagString
                db.notes().clearNotes(key)
                db.notes().clearFolders(key)
                db.notes().deleteNotebook(key)
            }
            // Membership/invite changes can gate visibility, so re-read
            // the list as well as the tree.
            "notebook-created", "notebook-updated", "notebook-visibility-changed",
            "member-joined", "member-left", "invite-received", "invite-removed",
            -> {
                bootstrap()
            }
            else -> refreshNotebook(flag)
        }
    }

    // ---- writes --------------------------------------------------------

    private suspend fun poke(action: JsonObject): Boolean {
        val ch = channel ?: return false
        return runCatching { ch.poke(NotesPaths.APP, NotesPaths.POKE_MARK, action) }
            .onFailure { Log.w(TAG, "notes poke failed", it) }
            .isSuccess
    }

    suspend fun createNote(flag: NotesFlag, folderId: Long, title: String, body: String): Boolean =
        poke(NotesActions.createNote(flag, folderId, title, body))

    /**
     * Save an edited body. [expectedRevision] must be the revision the
     * editor opened — the host rejects the poke if it moved on, so a
     * concurrent edit surfaces as a failure instead of silently winning.
     * The row is marked pending and the host echo clears it.
     */
    suspend fun updateNote(
        flag: NotesFlag,
        noteId: Long,
        body: String,
        expectedRevision: Long,
    ): Boolean {
        val ch = channel ?: return false
        val key = flag.flagString
        db.notes().applyLocalEdit(key, noteId, body, nowMs())
        // Deliberately NOT a poke. A channel poke returns the moment eyre
        // accepts it, so a stale-revision rejection is indistinguishable
        // from a successful write and the user's edit would vanish
        // silently. The v1 endpoint answers synchronously with
        // {"body":{"type":"ok"|"error"}}, which is the whole point of
        // threading expectedRevision through in the first place.
        val ok = runCatching {
            val resp = ch.apiJson(
                method = "PUT",
                path = NotesPaths.v1Note(flag, noteId),
                body = buildJsonObject {
                    put("body", body)
                    put("expectedRevision", expectedRevision)
                },
            )
            if (!NotesParser.isWriteOk(resp)) {
                Log.w(TAG, "note update rejected for $key/$noteId: ${NotesParser.writeErrorType(resp)}")
            }
            NotesParser.isWriteOk(resp)
        }.getOrElse {
            Log.w(TAG, "note update failed for $key/$noteId", it)
            false
        }
        // Clear the in-flight mark either way — the write is settled, and
        // only an unsettled row should read as "saving…". This has to
        // happen before the refresh: replaceTree deliberately re-applies
        // pending to rows still in flight, so leaving it set here would
        // make every later scry restore it and pin the note as saving
        // forever.
        db.notes().setPending(key, noteId, false)
        // Re-read either way. On success it picks up the host's new
        // revision, which the next edit must send back as
        // expectedRevision; on failure it replaces the optimistic body
        // with the host's version rather than leaving an edit on screen
        // that never landed.
        refreshNotebook(flag)
        return ok
    }

    suspend fun renameNote(flag: NotesFlag, noteId: Long, title: String): Boolean =
        poke(NotesActions.renameNote(flag, noteId, title))

    suspend fun moveNote(flag: NotesFlag, noteId: Long, folderId: Long): Boolean =
        poke(NotesActions.moveNote(flag, noteId, folderId))

    suspend fun deleteNote(flag: NotesFlag, noteId: Long): Boolean {
        val ok = poke(NotesActions.deleteNote(flag, noteId))
        // Drop it locally right away; the stream echo re-syncs either way.
        if (ok) db.notes().deleteNote(flag.flagString, noteId)
        return ok
    }

    /**
     * Publish [noteId] to the clear web, rendering its Markdown to HTML
     * here because the host stores whatever we send and serves it
     * unauthenticated. Returns the public path on success.
     */
    suspend fun publishNote(flag: NotesFlag, noteId: Long): String? {
        val row = db.notes().note(flag.flagString, noteId) ?: return null
        val html = MarkdownHtml.render(row.bodyMd)
        if (!poke(NotesActions.publishNote(flag, noteId, html))) return null
        return NotesPaths.publicPath(flag, noteId)
    }

    suspend fun unpublishNote(flag: NotesFlag, noteId: Long): Boolean =
        poke(NotesActions.unpublishNote(flag, noteId))

    /** Note ids currently published, as `<flag>#<id>` keys. */
    suspend fun publishedKeys(): Set<String> {
        val ch = channel ?: return emptySet()
        val body = runCatching { ch.scry(NotesPaths.APP, NotesPaths.PUBLISHED) }
            .getOrElse { return emptySet() }
        return NotesParser.publishedKeys(body)
    }

    suspend fun restoreNote(flag: NotesFlag, noteId: Long, rev: Long): Boolean =
        poke(NotesActions.restoreNote(flag, noteId, rev))

    suspend fun createFolder(flag: NotesFlag, parentFolderId: Long, name: String): Boolean =
        poke(NotesActions.createFolder(flag, parentFolderId, name))

    suspend fun renameFolder(flag: NotesFlag, folderId: Long, name: String): Boolean =
        poke(NotesActions.renameFolder(flag, folderId, name))

    suspend fun moveFolder(flag: NotesFlag, folderId: Long, newParentId: Long): Boolean =
        poke(NotesActions.moveFolder(flag, folderId, newParentId))

    /**
     * Delete a folder. [recursive] is required by the host to remove one
     * that still has anything in it.
     */
    suspend fun deleteFolder(
        flag: NotesFlag,
        folderId: Long,
        recursive: Boolean = true,
    ): Boolean {
        val ok = poke(NotesActions.deleteFolder(flag, folderId, recursive))
        if (ok) {
            // Drop it locally now; the stream echo re-syncs regardless.
            db.notes().deleteFolder(flag.flagString, folderId)
            refreshNotebook(flag)
        }
        return ok
    }

    suspend fun createNotebook(title: String): Boolean =
        poke(NotesActions.createNotebook(title))

    /**
     * Create a notebook as a channel of [groupFlag], returning its nest.
     *
     * Goes over v1 rather than poking: the host slugifies the title into
     * the flag (a "Team notes" becomes `team-notes-3`), so a
     * fire-and-forget poke would leave us unable to name what we just
     * made. The response carries the full summary.
     *
     * %notes registers the channel with %groups itself, so unlike the
     * other channel kinds there's no separate %channels create to do.
     */
    suspend fun createGroupNotebook(
        groupFlag: String,
        title: String,
        readers: Set<String> = emptySet(),
    ): String? {
        val ch = channel ?: return null
        val group = NotesFlag.parse(groupFlag) ?: run {
            Log.w(TAG, "createGroupNotebook: bad group flag $groupFlag")
            return null
        }
        val resp = runCatching {
            ch.apiJson(
                method = "POST",
                path = NotesPaths.V1_NOTEBOOKS,
                body = buildJsonObject {
                    put("title", title)
                    put("group", buildJsonObject {
                        put("host", group.host)
                        put("flagName", group.name)
                    })
                    put("readers", buildJsonArray { readers.forEach { add(JsonPrimitive(it)) } })
                },
            )
        }.getOrElse {
            Log.w(TAG, "create notebook failed in $groupFlag", it)
            return null
        }
        val created = NotesParser.createdNotebook(resp) ?: run {
            Log.w(TAG, "create notebook rejected: ${NotesParser.writeErrorType(resp)}")
            return null
        }
        // Pull it into the cache + subscribe so the new channel opens
        // populated instead of empty.
        bootstrap()
        return created.flag.nest
    }

    suspend fun renameNotebook(flag: NotesFlag, title: String): Boolean =
        poke(NotesActions.renameNotebook(flag, title))

    /**
     * Join/leave must go through %notes even for a group channel —
     * %channels rejects the unknown `notes/...` nest outright, so the
     * usual group-channel join path can't be reused here.
     */
    suspend fun joinNotebook(flag: NotesFlag): Boolean {
        val ok = poke(NotesActions.join(flag))
        if (ok) {
            ensureSubscribed(flag)
            refreshNotebook(flag)
        }
        return ok
    }

    suspend fun leaveNotebook(flag: NotesFlag): Boolean {
        val ok = poke(NotesActions.leave(flag))
        if (ok) {
            val key = flag.flagString
            db.notes().clearNotes(key)
            db.notes().clearFolders(key)
            db.notes().deleteNotebook(key)
            subLock.withLock { subscribed.remove(key) }
        }
        return ok
    }

    companion object {
        private const val TAG = "NotesRepo"

        /**
         * Does this SSE payload belong to %notes? Facts don't name their
         * agent, so the session loop routes by shape — %notes updates are
         * the only ones carrying `type` + `host` + `flagName` together.
         */
        fun isNotesEvent(payload: JsonObject): Boolean =
            payload.containsKey("type") &&
                payload.containsKey("host") &&
                payload.containsKey("flagName")
    }
}
