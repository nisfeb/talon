package io.nisfeb.talon.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit

/**
 * Reading a task list the way a task list is read: what is late, what
 * is today, what is coming, what has no date at all, and whatever the
 * owner is looking for.
 *
 * Pure, and here rather than in the screen, because the sorting and
 * the buckets are the part worth being sure of: a task that is late by
 * a minute and one that is late by a month belong in the same place,
 * and a search that misses a tag is a search nobody trusts twice.
 */
enum class TaskFilter(val label: String) {
    OPEN("Open"),
    OVERDUE("Overdue"),
    TODAY("Today"),
    WEEK("This week"),
    UNDATED("No date"),
    DONE("Done"),
    ALL("All"),
}

/** Whether one task is what [filter] asks for, on [today]. */
fun CalendarTask.inFilter(filter: TaskFilter, today: LocalDate): Boolean {
    val due = dueDate()
    return when (filter) {
        TaskFilter.ALL -> true
        TaskFilter.DONE -> done
        TaskFilter.OPEN -> !done
        TaskFilter.OVERDUE -> !done && due != null && due < today
        TaskFilter.TODAY -> !done && due != null && due <= today
        TaskFilter.WEEK -> !done && due != null && due <= endOfWeek(today)
        TaskFilter.UNDATED -> !done && due == null
    }
}

/**
 * Whether [words] appear in the task: its name, its description, its
 * place or its tags, each word anywhere, case ignored. Every word has
 * to land somewhere, so a second word narrows rather than widens.
 */
fun CalendarTask.matches(words: String): Boolean {
    val q = words.trim().lowercase()
    if (q.isEmpty()) return true
    // location off the meta: a task carries one when it was written in
    // the editor, and CalendarTask names only what every row has.
    val hay = (name + " " + note + " " + meta.metaStr("location") + " " + tags.joinToString(" ")).lowercase()
    return q.split(' ').filter { it.isNotBlank() }.all { it in hay }
}

/** One heading in the list, and what sits under it. */
data class TaskGroup(val label: String, val tasks: List<CalendarTask>)

/**
 * The list under headings, in the order a person works down it: what
 * was due before today first, then today, tomorrow, the rest of the
 * week, everything later, what was never given a date, and what is done
 * at the end. Under each heading the most pressing first (priority 1,
 * none last), then by name: the calendar's own page sorts them so, and
 * the two should agree.
 */
fun groupTasks(tasks: List<CalendarTask>, today: LocalDate): List<TaskGroup> {
    val (finished, open) = tasks.partition { it.done }
    val tomorrow = today.plus(1, DateTimeUnit.DAY)
    fun of(pick: (LocalDate?) -> Boolean) =
        open.filter { pick(it.dueDate()) }.sortedWith(compareBy({ it.priorityRank }, { it.name.lowercase() }))
    val overdue = of { it != null && it < today }
    val now = of { it == today }
    val next = of { it == tomorrow }
    val week = of { it != null && it > tomorrow && it <= endOfWeek(today) }
    val later = of { it != null && it > maxOf(tomorrow, endOfWeek(today)) }
    val undated = of { it == null }
    return buildList {
        if (overdue.isNotEmpty()) add(TaskGroup("Overdue", overdue))
        if (now.isNotEmpty()) add(TaskGroup("Today", now))
        if (next.isNotEmpty()) add(TaskGroup("Tomorrow", next))
        if (week.isNotEmpty()) add(TaskGroup("This week", week))
        if (later.isNotEmpty()) add(TaskGroup("Later", later))
        if (undated.isNotEmpty()) add(TaskGroup("No date", undated))
        if (finished.isNotEmpty()) add(TaskGroup("Done", taskOrder(finished).reversed()))
    }
}

/** Sunday, the way a week is spoken of here: today when today is one. */
internal fun endOfWeek(today: LocalDate): LocalDate =
    today.plus(7 - today.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
        .let { if (today.dayOfWeek == DayOfWeek.SUNDAY) today else it }
