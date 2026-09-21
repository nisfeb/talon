package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarTask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The task mirror: one todo per action, linked by the action's id,
 * and the two sides kept in step without either one reopening what
 * the other closed.
 */
class OrreryTasksTest {
    private fun action(id: String, status: String, kind: String = "task") =
        OrreryAction(id, kind, "Book the ferry", JsonObject(emptyMap()), emptyList(), null, status, "analyst")

    private fun todo(id: String, forAction: String?, done: Boolean = false) = CalendarTask(
        id = id,
        cat = "todo",
        done = done,
        meta = buildJsonObject {
            put("name", JsonPrimitive("Book the ferry"))
            if (forAction != null) put("orrery", JsonPrimitive(forAction))
        },
    )

    @Test
    fun `an approved or claimed task becomes a todo, once`() {
        assertEquals(
            listOf("a1", "a2"),
            taskMoves(listOf(action("a1", "approved"), action("a2", "claimed")), emptyList())
                .filterIsInstance<TaskMove.Make>().map { it.action.id },
        )
        // The link lives in the todo, so a second pass sees it and stops.
        assertTrue(taskMoves(listOf(action("a1", "approved")), listOf(todo("t1", "a1"))).isEmpty())
    }

    @Test
    fun `two installs making the same todo leaves one, the ticked one if either is`() {
        val moves = taskMoves(
            listOf(action("a1", "approved")),
            listOf(todo("t1", "a1"), todo("t2", "a1", done = true), todo("t3", "a1")),
        )
        // t2 stays, and being ticked it reports the action done.
        assertEquals(
            listOf(TaskMove.Drop("t1"), TaskMove.Drop("t3"), TaskMove.Report("a1")),
            moves,
        )
    }

    @Test
    fun `a proposal is a question, not a task`() {
        assertTrue(taskMoves(listOf(action("a1", "proposed")), emptyList()).isEmpty())
    }

    @Test
    fun `only tasks are mirrored`() {
        assertTrue(taskMoves(listOf(action("a1", "approved", kind = "message")), emptyList()).isEmpty())
    }

    @Test
    fun `the ship deciding moves the todo`() {
        assertEquals(
            listOf(TaskMove.Tick("t1")),
            taskMoves(listOf(action("a1", "done")), listOf(todo("t1", "a1"))),
        )
        assertEquals(
            listOf(TaskMove.Drop("t1")),
            taskMoves(listOf(action("a1", "dismissed")), listOf(todo("t1", "a1"))),
        )
        // Already ticked, already agreed: nothing to do.
        assertTrue(taskMoves(listOf(action("a1", "done")), listOf(todo("t1", "a1", done = true))).isEmpty())
    }

    @Test
    fun `the owner ticking it where they saw it tells the ship`() {
        assertEquals(
            listOf(TaskMove.Report("a1")),
            taskMoves(listOf(action("a1", "approved")), listOf(todo("t1", "a1", done = true))),
        )
    }

    @Test
    fun `a decision the ship made is never reopened`() {
        // Unticked in the calendar after the action was done: the ship's
        // word stands, and the todo is simply ticked again.
        assertEquals(
            listOf(TaskMove.Tick("t1")),
            taskMoves(listOf(action("a1", "done")), listOf(todo("t1", "a1", done = false))),
        )
        // Dismissed, and its todo already gone: nothing at all.
        assertTrue(taskMoves(listOf(action("a1", "dismissed")), emptyList()).isEmpty())
    }

    @Test
    fun `a todo of orrery's own is known by any of the three marks`() {
        assertTrue(todo("t1", "a1").isOrrerys())
        assertTrue(todo("orrery-a1", null).isOrrerys())
        assertTrue(
            CalendarTask(id = "t2", cat = "todo", meta = buildJsonObject {
                put("tags", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("orrery"))))
            }).isOrrerys(),
        )
        assertTrue(!todo("t3", null).isOrrerys(), "one the owner typed is theirs")
    }

    private fun typed(name: String, due: Long? = null, done: Boolean = false, note: String = "") = CalendarTask(
        id = "hand-" + name.lowercase().replace(' ', '-'),
        cal = "home",
        cat = "todo",
        done = done,
        dueMs = due,
        meta = buildJsonObject {
            put("name", JsonPrimitive(name))
            if (note.isNotEmpty()) put("note", JsonPrimitive(note))
            put("color", JsonPrimitive("#88aa00"))
        },
    )

    private val friday = 1_790_294_400_000L // 2026-09-25T00:00:00Z, the calendar's due for that day

    private fun owned(id: String, title: String, status: String, due: String? = null, typedHere: Boolean = true) = OrreryAction(
        id, "task", title,
        if (typedHere) buildJsonObject { put("why", JsonPrimitive(TYPED_IN_CALENDAR)) } else JsonObject(emptyMap()),
        emptyList(), due, status, "talon/desktop",
    )

    @Test
    fun `a todo the owner typed is filed as a task, and a ticked one is left alone`() {
        val gift = typed("Buy Magnus's gift", due = friday, note = "the blue one")
        assertEquals(listOf(TaskMove.Adopt(gift)), taskMoves(emptyList(), listOf(gift, typed("Paid the gas bill", done = true))))
        // Orrery's own are never filed again, however they are marked.
        val tagged = CalendarTask(id = "t9", cat = "todo", meta = buildJsonObject { put("name", JsonPrimitive("x")); put("tags", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("orrery")) }) })
        assertTrue(taskMoves(emptyList(), listOf(todo("t1", "a1"), tagged)).none { it is TaskMove.Adopt })
    }

    @Test
    fun `one filed by a pass that died before linking is linked, not filed twice`() {
        val gift = typed("Buy Magnus's gift", due = friday)
        val filed = owned("a7", "Buy Magnus's gift", "approved", due = "2026-09-25T00:00:00Z")
        assertEquals(listOf(TaskMove.Link(gift, "a7", approve = false)), taskMoves(listOf(filed), listOf(gift)))
        // Still only proposed: the owner wrote it, so it is approved on the way.
        assertEquals(listOf(TaskMove.Link(gift, "a7", approve = true)), taskMoves(listOf(filed.copy(status = "proposed")), listOf(gift)))
        // Another day is another task.
        assertEquals(listOf(TaskMove.Adopt(gift), TaskMove.Withdraw("a7")), taskMoves(listOf(filed.copy(due = "2026-10-02T00:00:00Z")), listOf(gift)))
    }

    @Test
    fun `a typed task whose todo was deleted is withdrawn, while orrery's own gets its todo back`() {
        val moves = taskMoves(
            listOf(owned("a7", "Buy Magnus's gift", "approved"), owned("a8", "Book the ferry", "approved", typedHere = false)),
            emptyList(),
        )
        assertEquals(listOf(TaskMove.Withdraw("a7")), moves.filterIsInstance<TaskMove.Withdraw>())
        assertEquals(listOf("a8"), moves.filterIsInstance<TaskMove.Make>().map { it.action.id })
    }

    @Test
    fun `filing takes the todo's name, due and note, and linking keeps the whole todo`() {
        val gift = typed("Buy Magnus's gift", due = friday, note = "the blue one")
        val act = adoptBody(gift)
        assertEquals("task", act["kind"]!!.jsonPrimitive.content)
        assertEquals("Buy Magnus's gift", act["title"]!!.jsonPrimitive.content)
        assertEquals("2026-09-25T00:00:00Z", act["due"]!!.jsonPrimitive.content)
        assertEquals("the blue one", act["payload"]!!.jsonObject["notes"]!!.jsonPrimitive.content)
        assertEquals(TYPED_IN_CALENDAR, act["payload"]!!.jsonObject["why"]!!.jsonPrimitive.content)
        val link = linkBody(gift, "a7")
        assertEquals("edit-event", link["action"]!!.jsonPrimitive.content)
        assertEquals(gift.id, link["id"]!!.jsonPrimitive.content)
        assertEquals("home", link["cal"]!!.jsonPrimitive.content)
        assertEquals(friday, link["due_ms"]!!.jsonPrimitive.content.toLong())
        val meta = link["meta"]!!.jsonObject
        assertEquals("a7", meta["orrery"]!!.jsonPrimitive.content)
        assertEquals("#88aa00", meta["color"]!!.jsonPrimitive.content, "what the todo carried rides through")
        assertEquals("the blue one", meta["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an answer takes effect on the list at once`() {
        val open = listOf(action("a1", "proposed"), action("a2", "proposed"))
        assertEquals(listOf("approved", "proposed"), settledActions(open, "a1", "approved").map { it.status }, "approved stays, to be done")
        assertEquals(listOf("a2"), settledActions(open, "a1", "dismissed").map { it.id }, "anything else has left the open list")
    }

    /** A task the owner typed, adopted and linked: why says so. */
    private fun typed(id: String, status: String) = OrreryAction(
        id, "task", "Build a fan with Magnus",
        buildJsonObject { put("why", JsonPrimitive(TYPED_IN_CALENDAR)) },
        emptyList(), null, status, "talon",
    )

    @Test
    fun `refusing a task the owner typed never deletes their entry`() {
        // Orrery's own todo goes with the action that made it.
        assertEquals(
            listOf("t1"),
            taskMoves(listOf(action("a1", "dismissed")), listOf(todo("t1", "a1")))
                .filterIsInstance<TaskMove.Drop>().map { it.todoId },
        )
        // The owner's does not. Saying no to tracking a thing they
        // wrote in their own calendar is not saying delete it, and
        // that deletion took two tasks off a shared calendar for good.
        assertTrue(
            taskMoves(listOf(typed("a2", "dismissed")), listOf(todo("t2", "a2")))
                .filterIsInstance<TaskMove.Drop>().isEmpty(),
        )
    }

    @Test
    fun `a listing that cannot be believed withdraws nothing`() {
        val gone = listOf(typed("a1", "approved"))
        // Believed: the owner did take it off the calendar.
        assertEquals(
            listOf("a1"),
            taskMoves(gone, listOf(todo("t9", null)))
                .filterIsInstance<TaskMove.Withdraw>().map { it.actionId },
        )
        // Not believed: a calendar that syncs from elsewhere is empty
        // while it re-syncs, and a task moved from one day to another
        // is missing from the pass that catches the move.
        assertTrue(
            taskMoves(gone, emptyList(), listingComplete = false)
                .filterIsInstance<TaskMove.Withdraw>().isEmpty(),
        )
        // And orrery's own is not filed again either: a twin made
        // against an empty listing is a twin the dedupe then deletes,
        // and which of the two it keeps is not worth finding out.
        assertTrue(
            taskMoves(listOf(action("a3", "approved")), emptyList(), listingComplete = false)
                .filterIsInstance<TaskMove.Make>().isEmpty(),
        )
        assertEquals(
            listOf("a3"),
            taskMoves(listOf(action("a3", "approved")), emptyList())
                .filterIsInstance<TaskMove.Make>().map { it.action.id },
            "a calendar that answers with nothing, on an install that has never seen a todo, is empty",
        )
    }
}
