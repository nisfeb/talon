package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wire types for the %notes agent — Tlon webapp v12's "Notebook" channel
 * type (Markdown documents in a folder tree). Distinct from the old
 * "Notebook", now [ChannelType.Bulletin], which lives on %channels.
 *
 * %notes is its OWN agent, not another `%channels` nest kind, so none of
 * the chat/bulletin/gallery plumbing applies:
 *
 *   reads      scry %notes /v0/notebooks
 *                          /v0/<kind>/<host>/<name>[/<id>]
 *                          where kind = notebook|folders|notes|note
 *                                     |note-history|folder|members
 *   writes     poke %notes mark `notes-action`, payload [action]
 *   live       subscribe %notes /v0/notes/<host>/<name>/stream
 *
 * Join/leave for a notes-backed *group* channel must also go through
 * %notes ([joinAction]/[leaveAction]) — %channels rejects the unknown
 * `notes/...` nest outright.
 *
 * Timestamps: %notes encodes @da via `unt:chrono`, which is Unix
 * **seconds** (`unm` is the millisecond one). Everything else in Talon
 * is millis, so the parsers here multiply on the way in and this is the
 * only place that conversion should live.
 */

/** Notebook identity: host ship + slug. Wire forms differ by context. */
data class NotesFlag(val host: String, val name: String) {
    /** Channel-nest form, as it appears in a group's channel list. */
    val nest: String get() = "notes/$host/$name"

    /** Poke form for `{"type":"notebook","flag":…}` — bare `host/name`. */
    val flagString: String get() = "$host/$name"

    /** Scry path segment pair. */
    val pathSegment: String get() = "$host/$name"

    companion object {
        /**
         * Accepts either a full nest (`notes/~host/name`) or a bare flag
         * (`~host/name`). Returns null for anything else so callers can
         * skip non-notes channels without throwing.
         */
        fun parse(target: String): NotesFlag? {
            val parts = target.split('/')
            val (host, name) = when {
                parts.size == 3 && parts[0] == "notes" -> parts[1] to parts[2]
                parts.size == 2 -> parts[0] to parts[1]
                else -> return null
            }
            // Host must be a patp. Without this, a truncated nest like
            // "notes/~ship" parses as the bare flag host="notes".
            if (!host.startsWith("~") || host.length < 2) return null
            if (name.isBlank()) return null
            return NotesFlag(host, name)
        }
    }
}

enum class NotesVisibility { Public, Private }

enum class NotesRole { Owner, Editor, Viewer }

data class NotesNotebook(
    val id: Long,
    val title: String,
    val createdBy: String,
    val createdAtMs: Long,
    val updatedBy: String,
    val updatedAtMs: Long,
)

data class NotesNotebookSummary(
    val flag: NotesFlag,
    val notebook: NotesNotebook,
    val visibility: NotesVisibility,
)

data class NotesFolder(
    val id: Long,
    val notebookId: Long,
    val name: String,
    /** null for the notebook's root folder. */
    val parentFolderId: Long?,
    val createdBy: String,
    val createdAtMs: Long,
    val updatedBy: String,
    val updatedAtMs: Long,
)

data class NotesNote(
    val id: Long,
    val notebookId: Long,
    val folderId: Long,
    val title: String,
    val slug: String?,
    val bodyMd: String,
    val createdBy: String,
    val createdAtMs: Long,
    val updatedBy: String,
    val updatedAtMs: Long,
    /** Bumped per edit; [NotesActions.updateNote] must echo it back. */
    val revision: Long,
)

data class NotesNoteRevision(
    val rev: Long,
    val atMs: Long,
    val author: String,
    val title: String,
    val bodyMd: String,
)

data class NotesMember(val ship: String, val role: NotesRole)

/** Unix seconds (what %notes emits) → millis (what Talon uses). */
private fun JsonElement?.asEpochMs(): Long = (asLong() ?: 0L) * 1000L

private fun String?.toVisibility(): NotesVisibility =
    if (this == "public") NotesVisibility.Public else NotesVisibility.Private

private fun String?.toRole(): NotesRole = when (this) {
    "owner" -> NotesRole.Owner
    "editor" -> NotesRole.Editor
    else -> NotesRole.Viewer
}

/** Parsers for %notes scry payloads. All tolerate missing/odd fields. */
object NotesParser {

    fun notebook(el: JsonElement?): NotesNotebook? {
        val o = el as? JsonObject ?: return null
        val id = o["id"].asLong() ?: return null
        return NotesNotebook(
            id = id,
            title = o["title"].asStr() ?: "",
            createdBy = o["createdBy"].asStr() ?: "",
            createdAtMs = o["createdAt"].asEpochMs(),
            updatedBy = o["updatedBy"].asStr() ?: "",
            updatedAtMs = o["updatedAt"].asEpochMs(),
        )
    }

    /** One entry of `/v0/notebooks`. */
    fun notebookSummary(el: JsonElement?): NotesNotebookSummary? {
        val o = el as? JsonObject ?: return null
        val host = o["host"].asStr() ?: return null
        val name = o["flagName"].asStr() ?: return null
        val nb = notebook(o["notebook"]) ?: return null
        return NotesNotebookSummary(
            flag = NotesFlag(host, name),
            notebook = nb,
            visibility = o["visibility"].asStr().toVisibility(),
        )
    }

    fun notebookSummaries(el: JsonElement?): List<NotesNotebookSummary> =
        (el as? JsonArray).orEmptyList().mapNotNull { notebookSummary(it) }

    fun folder(el: JsonElement?): NotesFolder? {
        val o = el as? JsonObject ?: return null
        val id = o["id"].asLong() ?: return null
        return NotesFolder(
            id = id,
            notebookId = o["notebookId"].asLong() ?: 0L,
            name = o["name"].asStr() ?: "",
            // Root folder encodes parentFolderId as null.
            parentFolderId = o["parentFolderId"]?.takeIf { it !is JsonNull }.asLong(),
            createdBy = o["createdBy"].asStr() ?: "",
            createdAtMs = o["createdAt"].asEpochMs(),
            updatedBy = o["updatedBy"].asStr() ?: "",
            updatedAtMs = o["updatedAt"].asEpochMs(),
        )
    }

    fun folders(el: JsonElement?): List<NotesFolder> =
        (el as? JsonArray).orEmptyList().mapNotNull { folder(it) }

    fun note(el: JsonElement?): NotesNote? {
        val o = el as? JsonObject ?: return null
        val id = o["id"].asLong() ?: return null
        return NotesNote(
            id = id,
            notebookId = o["notebookId"].asLong() ?: 0L,
            folderId = o["folderId"].asLong() ?: 0L,
            title = o["title"].asStr() ?: "",
            slug = o["slug"]?.takeIf { it !is JsonNull }.asStr(),
            bodyMd = o["bodyMd"].asStr() ?: "",
            createdBy = o["createdBy"].asStr() ?: "",
            createdAtMs = o["createdAt"].asEpochMs(),
            updatedBy = o["updatedBy"].asStr() ?: "",
            updatedAtMs = o["updatedAt"].asEpochMs(),
            revision = o["revision"].asLong() ?: 0L,
        )
    }

    fun notes(el: JsonElement?): List<NotesNote> =
        (el as? JsonArray).orEmptyList().mapNotNull { note(it) }

    fun revision(el: JsonElement?): NotesNoteRevision? {
        val o = el as? JsonObject ?: return null
        val rev = o["rev"].asLong() ?: return null
        return NotesNoteRevision(
            rev = rev,
            atMs = o["at"].asEpochMs(),
            author = o["author"].asStr() ?: "",
            title = o["title"].asStr() ?: "",
            bodyMd = o["bodyMd"].asStr() ?: "",
        )
    }

    fun revisions(el: JsonElement?): List<NotesNoteRevision> =
        (el as? JsonArray).orEmptyList().mapNotNull { revision(it) }

    fun member(el: JsonElement?): NotesMember? {
        val o = el as? JsonObject ?: return null
        val ship = o["ship"].asStr() ?: o["who"].asStr() ?: return null
        return NotesMember(ship, o["role"].asStr().toRole())
    }

    fun members(el: JsonElement?): List<NotesMember> =
        (el as? JsonArray).orEmptyList().mapNotNull { member(it) }

    private fun JsonArray?.orEmptyList(): List<JsonElement> = this ?: emptyList()
}

/**
 * Builders for `notes-action` poke payloads. Shapes mirror
 * `+dejs:action` / `+dejs:a-notebook` / `+dejs:a-note` in
 * `desk/lib/notes/json.hoon` — every action is a `{"type": …}` object,
 * and notebook-scoped ones nest under `{"type":"notebook","flag":…}`.
 */
object NotesActions {

    /** `{"type":"notebook","flag":"~host/name","action":{…}}` */
    private fun scoped(flag: NotesFlag, action: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "notebook")
            put("flag", flag.flagString)
            put("action", action)
        }

    // ---- top-level ----------------------------------------------------

    fun createNotebook(title: String): JsonObject = buildJsonObject {
        put("type", "create-notebook")
        put("title", title)
    }

    /** Notebook inside a group; [readers] are group role-ids (empty = open). */
    fun createGroupNotebook(
        title: String,
        groupHost: String,
        groupName: String,
        readers: Set<String> = emptySet(),
    ): JsonObject = buildJsonObject {
        put("type", "create-group-notebook")
        put("title", title)
        put("group", buildJsonObject {
            put("host", groupHost)
            put("flagName", groupName)
        })
        put("readers", buildJsonArray { readers.forEach { add(JsonPrimitive(it)) } })
    }

    fun join(flag: NotesFlag): JsonObject = buildJsonObject {
        put("type", "join")
        put("ship", flag.host)
        put("name", flag.name)
    }

    fun leave(flag: NotesFlag): JsonObject = buildJsonObject {
        put("type", "leave")
        put("ship", flag.host)
        put("name", flag.name)
    }

    fun acceptInvite(flag: NotesFlag): JsonObject = buildJsonObject {
        put("type", "accept-invite")
        put("ship", flag.host)
        put("name", flag.name)
    }

    fun declineInvite(flag: NotesFlag): JsonObject = buildJsonObject {
        put("type", "decline-invite")
        put("ship", flag.host)
        put("name", flag.name)
    }

    // ---- notebook-scoped ----------------------------------------------

    fun renameNotebook(flag: NotesFlag, title: String): JsonObject =
        scoped(flag, buildJsonObject {
            put("type", "rename")
            put("title", title)
        })

    fun deleteNotebook(flag: NotesFlag): JsonObject =
        scoped(flag, buildJsonObject { put("type", "delete") })

    fun setVisibility(flag: NotesFlag, visibility: NotesVisibility): JsonObject =
        scoped(flag, buildJsonObject {
            put("type", "visibility")
            put("visibility", if (visibility == NotesVisibility.Public) "public" else "private")
        })

    fun invite(flag: NotesFlag, who: String): JsonObject =
        scoped(flag, buildJsonObject {
            put("type", "invite")
            put("who", who)
        })

    fun createFolder(flag: NotesFlag, parentFolderId: Long, name: String): JsonObject =
        scoped(flag, buildJsonObject {
            put("type", "create-folder")
            put("parent", parentFolderId)
            put("name", name)
        })

    fun renameFolder(flag: NotesFlag, folderId: Long, name: String): JsonObject =
        scoped(flag, folderScoped(folderId, buildJsonObject {
            put("type", "rename")
            put("name", name)
        }))

    fun moveFolder(flag: NotesFlag, folderId: Long, newParentId: Long): JsonObject =
        scoped(flag, folderScoped(folderId, buildJsonObject {
            put("type", "move")
            put("new-parent", newParentId)
        }))

    fun createNote(flag: NotesFlag, folderId: Long, title: String, body: String): JsonObject =
        scoped(flag, buildJsonObject {
            put("type", "create-note")
            put("folder", folderId)
            put("title", title)
            put("body", body)
        })

    /**
     * Body edit. [expectedRevision] is the revision the editor started
     * from — the host nacks with %conflict if it moved on, which is how
     * concurrent edits are detected rather than silently clobbered.
     */
    fun updateNote(
        flag: NotesFlag,
        noteId: Long,
        body: String,
        expectedRevision: Long,
    ): JsonObject = scoped(flag, noteScoped(noteId, buildJsonObject {
        put("type", "update")
        put("body", body)
        put("expectedRevision", expectedRevision)
    }))

    fun renameNote(flag: NotesFlag, noteId: Long, title: String): JsonObject =
        scoped(flag, noteScoped(noteId, buildJsonObject {
            put("type", "rename")
            put("title", title)
        }))

    fun moveNote(flag: NotesFlag, noteId: Long, folderId: Long): JsonObject =
        scoped(flag, noteScoped(noteId, buildJsonObject {
            put("type", "move")
            put("folder", folderId)
        }))

    fun deleteNote(flag: NotesFlag, noteId: Long): JsonObject =
        scoped(flag, noteScoped(noteId, buildJsonObject { put("type", "delete") }))

    fun restoreNote(flag: NotesFlag, noteId: Long, rev: Long): JsonObject =
        scoped(flag, noteScoped(noteId, buildJsonObject {
            put("type", "restore")
            put("rev", rev)
        }))

    private fun noteScoped(noteId: Long, action: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "note")
            put("id", noteId)
            put("action", action)
        }

    private fun folderScoped(folderId: Long, action: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "folder")
            put("id", folderId)
            put("action", action)
        }
}

/** Scry paths + the subscription path for one notebook. */
object NotesPaths {
    const val APP = "notes"
    const val POKE_MARK = "notes-action"

    /** All notebooks we can see. */
    const val NOTEBOOKS = "/v0/notebooks"
    const val INVITES = "/v0/invites"
    const val PUBLISHED = "/v0/published"

    fun notebook(flag: NotesFlag) = "/v0/notebook/${flag.pathSegment}"
    fun folders(flag: NotesFlag) = "/v0/folders/${flag.pathSegment}"
    fun notes(flag: NotesFlag) = "/v0/notes/${flag.pathSegment}"
    fun note(flag: NotesFlag, id: Long) = "/v0/note/${flag.pathSegment}/$id"
    fun noteHistory(flag: NotesFlag, id: Long) = "/v0/note-history/${flag.pathSegment}/$id"
    fun folder(flag: NotesFlag, id: Long) = "/v0/folder/${flag.pathSegment}/$id"
    fun members(flag: NotesFlag) = "/v0/members/${flag.pathSegment}"

    /** Live update stream for one notebook (host or subscriber side). */
    fun stream(flag: NotesFlag) = "/v0/notes/${flag.pathSegment}/stream"
}
