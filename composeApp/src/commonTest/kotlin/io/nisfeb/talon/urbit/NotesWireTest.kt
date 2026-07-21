package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the %notes wire contract against `desk/lib/notes/json.hoon`. These
 * are the shapes we can't verify against a live ship until one is running
 * webapp v12, so they're asserted literally here.
 */
class NotesWireTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String) = json.parseToJsonElement(s) as JsonObject
    private fun arr(s: String) = json.parseToJsonElement(s)

    // ---- flag parsing --------------------------------------------------

    @Test
    fun `parses a full notes nest`() {
        val f = NotesFlag.parse("notes/~ricsul-bilwyt/handbook")
        assertEquals(NotesFlag("~ricsul-bilwyt", "handbook"), f)
        assertEquals("notes/~ricsul-bilwyt/handbook", f!!.nest)
        // Poke form drops the `notes/` prefix.
        assertEquals("~ricsul-bilwyt/handbook", f.flagString)
    }

    @Test
    fun `parses a bare flag`() {
        assertEquals(
            NotesFlag("~ricsul-bilwyt", "handbook"),
            NotesFlag.parse("~ricsul-bilwyt/handbook"),
        )
    }

    @Test
    fun `rejects non-notes and malformed targets`() {
        // A chat nest must not be mistaken for a notes flag.
        assertNull(NotesFlag.parse("chat/~ricsul-bilwyt/general"))
        assertNull(NotesFlag.parse("notes/~ricsul-bilwyt"))
        assertNull(NotesFlag.parse("notes//handbook"))
        assertNull(NotesFlag.parse("handbook"))
        assertNull(NotesFlag.parse(""))
    }

    // ---- parsing -------------------------------------------------------

    @Test
    fun `note parses and converts unix seconds to millis`() {
        // %notes encodes @da through `unt:chrono` = Unix SECONDS.
        val n = NotesParser.note(
            obj(
                """
                {"id":7,"notebookId":1,"folderId":2,"title":"Setup",
                 "slug":"setup","bodyMd":"# Hello\n\nbody",
                 "createdBy":"~ricsul-bilwyt","createdAt":1750000000,
                 "updatedBy":"~ricsul-bilwyt","updatedAt":1750000060,
                 "revision":3}
                """.trimIndent(),
            ),
        )!!
        assertEquals(7L, n.id)
        assertEquals("Setup", n.title)
        assertEquals("# Hello\n\nbody", n.bodyMd)
        assertEquals(3L, n.revision)
        assertEquals(1_750_000_000_000L, n.createdAtMs)
        assertEquals(1_750_000_060_000L, n.updatedAtMs)
    }

    @Test
    fun `note tolerates null slug`() {
        val n = NotesParser.note(
            obj("""{"id":1,"notebookId":1,"folderId":1,"title":"t","slug":null,"bodyMd":"","revision":0}"""),
        )!!
        assertNull(n.slug)
    }

    @Test
    fun `root folder has null parent`() {
        val f = NotesParser.folder(
            obj("""{"id":1,"notebookId":1,"name":"root","parentFolderId":null,"createdAt":1750000000}"""),
        )!!
        assertNull(f.parentFolderId)
        assertEquals("root", f.name)

        val child = NotesParser.folder(
            obj("""{"id":2,"notebookId":1,"name":"sub","parentFolderId":1}"""),
        )!!
        assertEquals(1L, child.parentFolderId)
    }

    @Test
    fun `notebook summary parses host and flagName`() {
        val s = NotesParser.notebookSummary(
            obj(
                """
                {"host":"~ricsul-bilwyt","flagName":"handbook","visibility":"public",
                 "notebook":{"id":1,"title":"Handbook","createdBy":"~ricsul-bilwyt",
                             "createdAt":1750000000,"updatedBy":"~ricsul-bilwyt",
                             "updatedAt":1750000000}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(NotesFlag("~ricsul-bilwyt", "handbook"), s.flag)
        assertEquals(NotesVisibility.Public, s.visibility)
        assertEquals("Handbook", s.notebook.title)
    }

    @Test
    fun `list parsers skip malformed entries instead of throwing`() {
        val notes = NotesParser.notes(arr("""[{"id":1,"title":"ok"},{"nope":true},"junk"]"""))
        assertEquals(1, notes.size)
        assertEquals(1L, notes[0].id)
    }

    // ---- action shapes -------------------------------------------------

    @Test
    fun `notebook-scoped action nests under type notebook with bare flag`() {
        val flag = NotesFlag("~ricsul-bilwyt", "handbook")
        val a = NotesActions.createNote(flag, folderId = 2, title = "T", body = "B")
        assertEquals("notebook", a["type"]!!.jsonPrimitive.content)
        // hoon parses `flag` as "<ship>/<name>" — no `notes/` prefix.
        assertEquals("~ricsul-bilwyt/handbook", a["flag"]!!.jsonPrimitive.content)
        val inner = a["action"] as JsonObject
        assertEquals("create-note", inner["type"]!!.jsonPrimitive.content)
        assertEquals(2L, inner["folder"]!!.jsonPrimitive.content.toLong())
        assertEquals("T", inner["title"]!!.jsonPrimitive.content)
        assertEquals("B", inner["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `updateNote carries expectedRevision for conflict detection`() {
        val a = NotesActions.updateNote(
            NotesFlag("~ricsul-bilwyt", "handbook"),
            noteId = 7,
            body = "new",
            expectedRevision = 3,
        )
        val note = a["action"] as JsonObject
        assertEquals("note", note["type"]!!.jsonPrimitive.content)
        assertEquals(7L, note["id"]!!.jsonPrimitive.content.toLong())
        val inner = note["action"] as JsonObject
        assertEquals("update", inner["type"]!!.jsonPrimitive.content)
        assertEquals("new", inner["body"]!!.jsonPrimitive.content)
        assertEquals(3L, inner["expectedRevision"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `join and leave use ship and name, not flag`() {
        val flag = NotesFlag("~ricsul-bilwyt", "handbook")
        val j = NotesActions.join(flag)
        assertEquals("join", j["type"]!!.jsonPrimitive.content)
        assertEquals("~ricsul-bilwyt", j["ship"]!!.jsonPrimitive.content)
        assertEquals("handbook", j["name"]!!.jsonPrimitive.content)
        assertEquals("leave", NotesActions.leave(flag)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `visibility serializes as public private strings`() {
        val flag = NotesFlag("~ricsul-bilwyt", "handbook")
        val pub = NotesActions.setVisibility(flag, NotesVisibility.Public)["action"] as JsonObject
        assertEquals("public", pub["visibility"]!!.jsonPrimitive.content)
        val priv = NotesActions.setVisibility(flag, NotesVisibility.Private)["action"] as JsonObject
        assertEquals("private", priv["visibility"]!!.jsonPrimitive.content)
    }

    @Test
    fun `folder move uses new-parent key`() {
        val a = NotesActions.moveFolder(NotesFlag("~z", "n"), folderId = 5, newParentId = 9)
        val folder = a["action"] as JsonObject
        assertEquals("folder", folder["type"]!!.jsonPrimitive.content)
        val inner = folder["action"] as JsonObject
        assertEquals("move", inner["type"]!!.jsonPrimitive.content)
        // hoon reads 'new-parent', not 'newParent'.
        assertTrue(inner.containsKey("new-parent"))
        assertEquals(9L, inner["new-parent"]!!.jsonPrimitive.content.toLong())
    }

    // ---- paths ---------------------------------------------------------

    @Test
    fun `scry and stream paths match the agent's peek and watch poles`() {
        val flag = NotesFlag("~ricsul-bilwyt", "handbook")
        assertEquals("/v0/notebooks", NotesPaths.NOTEBOOKS)
        assertEquals("/v0/notebook/~ricsul-bilwyt/handbook", NotesPaths.notebook(flag))
        assertEquals("/v0/folders/~ricsul-bilwyt/handbook", NotesPaths.folders(flag))
        assertEquals("/v0/notes/~ricsul-bilwyt/handbook", NotesPaths.notes(flag))
        assertEquals("/v0/note/~ricsul-bilwyt/handbook/7", NotesPaths.note(flag, 7))
        assertEquals("/v0/note-history/~ricsul-bilwyt/handbook/7", NotesPaths.noteHistory(flag, 7))
        assertEquals("/v0/members/~ricsul-bilwyt/handbook", NotesPaths.members(flag))
        assertEquals("/v0/notes/~ricsul-bilwyt/handbook/stream", NotesPaths.stream(flag))
    }

    @Test
    fun `channel type maps notes nest to Notebook and diary to Bulletin`() {
        assertEquals(ChannelType.Notebook, ChannelType.fromWhom("notes/~z/handbook"))
        assertEquals(ChannelType.Bulletin, ChannelType.fromWhom("diary/~z/journal"))
        assertEquals("/notes", ChannelType.agentKind(ChannelType.Notebook))
    }
}
