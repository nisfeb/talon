package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ---- live-ship fixtures --------------------------------------------
    // Captured verbatim from ~ricsul-bilwyt (Tlon v12.0.0) scrying the
    // %notes channel notes/~minder-folden/codex-7. These are the real
    // payloads, so they pin field names and the seconds-vs-millis
    // question that the hand-written cases above only assumed.

    @Test
    fun `parses the live notebook summary payload`() {
        val s = NotesParser.notebookSummaries(
            arr(
                """
                [{"flagName":"codex-7","host":"~minder-folden","notebook":{"title":"Codex",
                  "id":7,"rootFolderId":8,"createdBy":"~minder-folden","createdAt":1784592399,
                  "updatedAt":1784592399,"updatedBy":"~minder-folden"},"visibility":"private"}]
                """.trimIndent(),
            ),
        )
        assertEquals(1, s.size)
        assertEquals(NotesFlag("~minder-folden", "codex-7"), s[0].flag)
        assertEquals("Codex", s[0].notebook.title)
        assertEquals(NotesVisibility.Private, s[0].visibility)
        // Host hands us the root folder directly.
        assertEquals(8L, s[0].notebook.rootFolderId)
        // 1784592399s = 2026-07-21. As millis it would be Jan 1970, which
        // is exactly the bug this conversion exists to avoid.
        assertEquals(1_784_592_399_000L, s[0].notebook.createdAtMs)
    }

    @Test
    fun `parses the live root folder and note payloads`() {
        val f = NotesParser.folders(
            arr(
                """
                [{"name":"/","notebookId":7,"id":8,"createdBy":"~minder-folden",
                  "createdAt":1784592399,"parentFolderId":null,"updatedAt":1784592399,
                  "updatedBy":"~minder-folden"}]
                """.trimIndent(),
            ),
        )
        assertEquals(1, f.size)
        assertEquals(8L, f[0].id)
        assertEquals("/", f[0].name)
        assertNull(f[0].parentFolderId, "root folder must parse a null parent")

        val n = NotesParser.notes(
            arr(
                """
                [{"folderId":8,"notebookId":7,"title":"🏆 FIRST","revision":3,"id":9,
                  "createdBy":"~minder-folden","createdAt":1784592455,
                  "bodyMd":"I'm excited to see how this new channel type (that's **markdown friendly**) will find its legs.",
                  "updatedAt":1784592505,"updatedBy":"~minder-folden","slug":null}]
                """.trimIndent(),
            ),
        )
        assertEquals(1, n.size)
        assertEquals(9L, n[0].id)
        assertEquals(8L, n[0].folderId)
        assertEquals(3L, n[0].revision)
        assertNull(n[0].slug)
        assertTrue(n[0].title.contains("FIRST"))
        assertTrue(n[0].bodyMd.contains("**markdown friendly**"))
        assertEquals(1_784_592_505_000L, n[0].updatedAtMs)
    }

    @Test
    fun `parses the live members payload`() {
        val m = NotesParser.members(
            arr("""[{"role":"owner","ship":"~minder-folden"},{"role":"editor","ship":"~ricsul-bilwyt"}]"""),
        )
        assertEquals(2, m.size)
        assertEquals(NotesRole.Owner, m[0].role)
        assertEquals("~minder-folden", m[0].ship)
        assertEquals(NotesRole.Editor, m[1].role)
    }

    // ---- write verdicts -------------------------------------------------
    // The bug these exist for: a channel poke 204s whether the host
    // applied the write or rejected it, so a stale edit looked saved and
    // the editor threw the user's text away. Saves go over v1 now, which
    // answers 200 either way with the verdict in body.type — so reading
    // that correctly is the thing that must not regress. Both payloads
    // below are verbatim from the live ship.

    @Test
    fun `rejected write is not read as success`() {
        val rejected = obj(
            """{"body":{"errorType":"unknown","message":[],"type":"error"},"requestId":"0vuc.k6rtf"}""",
        )
        assertFalse(NotesParser.isWriteOk(rejected), "a stale-revision rejection must not read as ok")
        assertEquals("unknown", NotesParser.writeErrorType(rejected))
    }

    @Test
    fun `accepted write is read as success`() {
        val accepted = obj(
            """
            {"body":{"type":"ok","response":{"update":{"noteUpdate":{"note":{"folderId":2,
              "notebookId":1,"title":"Wire test","revision":2,"id":4,"bodyMd":"# Hello",
              "slug":null},"id":4,"type":"note-updated"},"flagName":"scratch",
              "host":"~ricsul-bilwyt","type":"note-updated"}}},"requestId":"0v1"}
            """.trimIndent(),
        )
        assertTrue(NotesParser.isWriteOk(accepted))
        assertNull(NotesParser.writeErrorType(accepted), "a success has no error tag")
    }

    @Test
    fun `create returns the notebook tag, not ok, and still counts as success`() {
        // response-body has several success arms. A create answers
        // %notebook with the new summary — reading only %ok would report
        // a notebook that was actually created as a failure.
        val created = obj(
            """
            {"body":{"type":"notebook","notebook":{"flagName":"wire-probe-5",
              "host":"~ricsul-bilwyt","visibility":"private","notebook":{"title":"Wire probe",
              "id":5,"rootFolderId":6,"createdBy":"~ricsul-bilwyt","createdAt":1784600147,
              "updatedAt":1784600147,"updatedBy":"~ricsul-bilwyt"}}},"requestId":"0vn5"}
            """.trimIndent(),
        )
        assertTrue(NotesParser.isWriteOk(created))
        val summary = NotesParser.createdNotebook(created)!!
        // The host slugifies the title, so this is the only way to learn
        // the flag of what we just made.
        assertEquals(NotesFlag("~ricsul-bilwyt", "wire-probe-5"), summary.flag)
        assertEquals("notes/~ricsul-bilwyt/wire-probe-5", summary.flag.nest)
        assertEquals(6L, summary.notebook.rootFolderId)
        // A plain %ok mutation carries no notebook to unwrap.
        assertNull(NotesParser.createdNotebook(obj("""{"body":{"type":"ok"}}""")))
    }

    @Test
    fun `pending is not treated as a completed write`() {
        // %pending means a cross-ship request is still in flight; calling
        // that "saved" would be the same class of bug as the poke 204.
        assertFalse(NotesParser.isWriteOk(obj("""{"body":{"type":"pending","status":"sending"}}""")))
    }

    @Test
    fun `unreadable write response counts as failure not success`() {
        // Defaulting to "saved" on a reply we can't parse would drop the
        // user's edit exactly like the original bug did.
        assertFalse(NotesParser.isWriteOk(null))
        assertFalse(NotesParser.isWriteOk(obj("""{}""")))
        assertFalse(NotesParser.isWriteOk(obj("""{"body":"nonsense"}""")))
        assertFalse(NotesParser.isWriteOk(obj("""{"body":{"type":"pending"}}""")))
        assertFalse(NotesParser.isWriteOk(arr("""[]""")))
    }

    // ---- event routing ---------------------------------------------------
    // isNotesEvent matches on payload shape because SSE facts don't name
    // their agent. A false positive would swallow another agent's event
    // entirely, so the negative cases matter more than the positive one.

    @Test
    fun `isNotesEvent matches notes facts`() {
        assertTrue(
            NotesRepo.isNotesEvent(
                obj("""{"type":"note-updated","host":"~minder-folden","flagName":"codex-7","id":9}"""),
            ),
        )
        assertTrue(
            NotesRepo.isNotesEvent(
                obj("""{"type":"notebook-deleted","host":"~ricsul-bilwyt","flagName":"scratch"}"""),
            ),
        )
    }

    @Test
    fun `isNotesEvent ignores other agents' facts`() {
        // %settings
        assertFalse(NotesRepo.isNotesEvent(obj("""{"put-entry":{"bucket-key":"ui","entry-key":"x"}}""")))
        // %groups /v1/groups
        assertFalse(NotesRepo.isNotesEvent(obj("""{"flag":"~ship/group","r-group":{"channel":{}}}""")))
        // %presence
        assertFalse(NotesRepo.isNotesEvent(obj("""{"here":{"path":"/chat/x"}}""")))
        // a channels delta that happens to carry a `type`
        assertFalse(NotesRepo.isNotesEvent(obj("""{"type":"post","nest":"chat/~ship/general"}""")))
        // host without flagName, and vice versa
        assertFalse(NotesRepo.isNotesEvent(obj("""{"type":"x","host":"~ship"}""")))
        assertFalse(NotesRepo.isNotesEvent(obj("""{"type":"x","flagName":"n"}""")))
    }

    @Test
    fun `channel type maps notes nest to Notebook and diary to Bulletin`() {
        assertEquals(ChannelType.Notebook, ChannelType.fromWhom("notes/~z/handbook"))
        assertEquals(ChannelType.Bulletin, ChannelType.fromWhom("diary/~z/journal"))
        assertEquals("/notes", ChannelType.agentKind(ChannelType.Notebook))
    }
}
