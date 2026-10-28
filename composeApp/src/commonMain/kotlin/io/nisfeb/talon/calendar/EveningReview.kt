package io.nisfeb.talon.calendar

import kotlinx.datetime.TimeZone

/**
 * The evening review (orrery 130): every open todo that is late, due
 * today, or never given a date, for the owner to organize at the end of
 * the day. Read by the rule orrery counts its push by ([overdue],
 * [dueToday]), so the screen and the push agree. Every todo on a calendar
 * the owner can write is here, orrery's and typed alike (sneagan,
 * 2026-10-28), so there can be more than the push counted: it counts
 * orrery's own tasks only.
 */
data class EveningReview(
    val overdue: List<CalendarTask>,
    val today: List<CalendarTask>,
    val undated: List<CalendarTask>,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && today.isEmpty() && undated.isEmpty()

    /** Orrery's own words for the counts (+evening-line): "2 overdue, 1 due today, 12 with no date". */
    val line: String get() = listOfNotNull(
        overdue.size.takeIf { it > 0 }?.let { "$it overdue" },
        today.size.takeIf { it > 0 }?.let { "$it due today" },
        undated.size.takeIf { it > 0 }?.let { "$it with no date" },
    ).joinToString(", ")
}

/** [tasks] as the review lays them out at [nowMs] in [zone], from the calendars in [writable]. */
fun eveningReview(tasks: List<CalendarTask>, writable: Set<String>, nowMs: Long, zone: TimeZone): EveningReview {
    val open = tasks.filter { !it.done && it.cal in writable }
    // The most pressing first, as the task list sorts under a heading.
    fun sorted(l: List<CalendarTask>) = l.sortedWith(compareBy({ it.priorityRank }, { it.dueMs ?: Long.MAX_VALUE }, { it.name.lowercase() }))
    return EveningReview(
        overdue = sorted(open.filter { it.overdue(nowMs, zone) }),
        today = sorted(open.filter { it.dueToday(nowMs, zone) }),
        undated = sorted(open.filter { it.dueMs == null }),
    )
}
