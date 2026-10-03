package io.nisfeb.talon.calendar

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Searching and bucketing a task list. */
class TaskListTest {
    // A Wednesday.
    private val today = LocalDate(2026, 9, 23)

    private fun task(
        name: String,
        due: LocalDate? = null,
        done: Boolean = false,
        note: String = "",
        tags: List<String> = emptyList(),
        place: String = "",
        priority: Int? = null,
    ) = CalendarTask(
        id = name,
        cat = "todo",
        done = done,
        priority = priority,
        meta = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("note", JsonPrimitive(note))
            put("location", JsonPrimitive(place))
            if (tags.isNotEmpty()) put("tags", kotlinx.serialization.json.JsonArray(tags.map { JsonPrimitive(it) }))
        },
        dueMs = due?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
    )

    @Test
    fun `the headings read down in the order the day is worked`() {
        val groups = groupTasks(
            listOf(
                task("later", LocalDate(2026, 10, 30)),
                task("nothing"),
                task("yesterday", LocalDate(2026, 9, 22)),
                task("friday", LocalDate(2026, 9, 25)),
                task("finished", done = true),
                task("today", today),
            ),
            today,
        )
        assertEquals(listOf("Overdue", "Today", "This week", "Later", "No date", "Done"), groups.map { it.label })
        assertEquals(listOf("yesterday"), groups[0].tasks.map { it.name })
        assertEquals(listOf("friday"), groups[2].tasks.map { it.name })
    }

    // "b4bp calendar is adding priority to tasks. talon should too": the
    // calendar's page groups by due and, under each heading, puts priority
    // 1 first and none last, then the name. The two lists agree.
    @Test
    fun `under each heading the most pressing comes first, then by name, as on the calendar's page`() {
        val tomorrow = LocalDate(2026, 9, 24)
        val groups = groupTasks(
            listOf(
                task("b none", today), task("a none", today), task("low", today, priority = 9),
                task("high", today, priority = 1), task("odd three", today, priority = 3), task("medium", today, priority = 5),
                task("tomorrow's", tomorrow),
            ),
            today,
        )
        assertEquals(listOf("Today", "Tomorrow"), groups.map { it.label })
        assertEquals(listOf("high", "odd three", "medium", "low", "a none", "b none"), groups[0].tasks.map { it.name })
        assertEquals(listOf("tomorrow's"), groups[1].tasks.map { it.name })
    }

    // Under a heading that spans days, two of one priority go by their
    // day, then by name, as on the calendar's page.
    @Test
    fun `under a heading of several days, a priority's tasks go soonest first`() {
        val sun = LocalDate(2026, 9, 27)
        val fri = LocalDate(2026, 9, 25)
        val week = groupTasks(
            listOf(task("a sunday", sun, priority = 5), task("z friday", fri, priority = 5), task("high sunday", sun, priority = 1)),
            today,
        ).single { it.label == "This week" }
        assertEquals(listOf("high sunday", "z friday", "a sunday"), week.tasks.map { it.name })
    }

    // The home page's list runs by day; priority orders a day's tasks.
    @Test
    fun `the home page's tasks run by day, then by priority`() {
        val order = taskOrder(listOf(task("later high", LocalDate(2026, 9, 25), priority = 1), task("today none", today), task("today low", today, priority = 9)))
        assertEquals(listOf("today low", "today none", "later high"), order.map { it.name })
    }

    @Test
    fun `an empty heading is not shown at all`() {
        val groups = groupTasks(listOf(task("nothing")), today)
        assertEquals(listOf("No date"), groups.map { it.label })
    }

    @Test
    fun `a search reads the description, the place and the tags too`() {
        val t = task("Call the shop", note = "about the subaru", tags = listOf("errand"), place = "Route 9")
        assertTrue(t.matches("call"))
        assertTrue(t.matches("subaru"), "the description")
        assertTrue(t.matches("errand"), "a tag, with or without its hash")
        assertTrue(t.matches("route"), "the place")
        assertTrue(t.matches("SHOP"), "case is not the point")
        assertTrue(t.matches("call subaru"), "every word has to land, in any order")
        assertFalse(t.matches("call ferry"), "so a second word narrows")
        assertTrue(t.matches("  "), "nothing typed is not a filter")
    }

    @Test
    fun `the filters say what they mean on the day`() {
        val late = task("late", LocalDate(2026, 9, 1))
        val now = task("now", today)
        val soon = task("soon", LocalDate(2026, 9, 25))
        val far = task("far", LocalDate(2026, 12, 1))
        val none = task("none")
        val did = task("did", done = true)

        assertTrue(late.inFilter(TaskFilter.OVERDUE, today))
        assertFalse(now.inFilter(TaskFilter.OVERDUE, today), "today is not late yet")
        // Today means what is on the plate today, which includes what
        // should already have been done.
        assertTrue(now.inFilter(TaskFilter.TODAY, today))
        assertTrue(late.inFilter(TaskFilter.TODAY, today))
        assertTrue(soon.inFilter(TaskFilter.WEEK, today))
        assertFalse(far.inFilter(TaskFilter.WEEK, today))
        assertTrue(none.inFilter(TaskFilter.UNDATED, today))
        assertFalse(none.inFilter(TaskFilter.TODAY, today), "no date is not due today")
        assertTrue(did.inFilter(TaskFilter.DONE, today))
        assertFalse(did.inFilter(TaskFilter.OPEN, today))
        assertTrue(listOf(late, now, did, none).all { it.inFilter(TaskFilter.ALL, today) })
    }

    @Test
    fun `a week ends on sunday, and on sunday it is today`() {
        assertEquals(LocalDate(2026, 9, 27), endOfWeek(today), "Wednesday looks to Sunday")
        val sunday = LocalDate(2026, 9, 27)
        assertEquals(sunday, endOfWeek(sunday), "on Sunday the week is out")
    }
}
