package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarTask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
}
