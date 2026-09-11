package io.nisfeb.talon.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * What sits on the home page, in what order, at what size.
 *
 * One flat record per widget rather than a type per kind. The
 * kind-specific fields (a calendar's range, a status panel's pinned
 * people) are carried by everything and ignored by most, which is the
 * cheaper trade at five kinds: a sealed hierarchy here would buy
 * nothing but a serialiser to write.
 */
@Serializable
enum class HomeWidgetKind {
    /** The clock and weather dial. */
    CLOCK,
    MESSAGES,
    MAIL,
    CALENDAR,
    STATUS,
}

/** How far ahead the calendar looks. */
@Serializable
enum class CalendarRange(val label: String, val minutes: Int?) {
    /** Just the next thing, whenever it is. */
    NEXT_ONLY("Next only", null),
    NEXT_3_HOURS("Next 3 hours", 3 * 60),
    NEXT_6_HOURS("Next 6 hours", 6 * 60),
    REST_OF_DAY("Rest of today", null),
    NEXT_DAY("Next 24 hours", 24 * 60),
}

/** The counts a list widget can be set to. */
val HOME_COUNTS = listOf(3, 5, 8, 10)

/** Columns the grid offers at its widest. Two, because three columns of
 *  five-row lists is a dashboard nobody reads. */
const val HOME_COLUMNS = 2

/** How tall a widget may be, in row units. */
val HOME_ROW_RANGE = 1..3

@Serializable
data class HomeWidget(
    val kind: HomeWidgetKind,
    val visible: Boolean = true,
    /** How many rows a list widget shows. Ignored by the clock. */
    val count: Int = 5,
    /** Grid columns it takes, 1 or [HOME_COLUMNS]. */
    val span: Int = 1,
    /** Grid rows it takes, within [HOME_ROW_RANGE]. */
    val rows: Int = 1,
    val calendarRange: CalendarRange = CalendarRange.REST_OF_DAY,
    /** Ships whose status is kept at the top of the status widget,
     *  in the order they were pinned. */
    val pinned: List<String> = emptyList(),
) {
    /** Clamped into what the grid can actually draw, whatever a stored
     *  line or an older version happened to say. */
    fun sane(): HomeWidget = copy(
        count = count.coerceIn(HOME_COUNTS.first(), HOME_COUNTS.last()),
        span = span.coerceIn(1, HOME_COLUMNS),
        rows = rows.coerceIn(HOME_ROW_RANGE.first, HOME_ROW_RANGE.last),
        pinned = pinned.distinct().take(HOME_PINNED_MAX),
    )
}

/** More pinned people than this and the widget is just a contact list. */
const val HOME_PINNED_MAX = 8

@Serializable
data class HomeLayout(val widgets: List<HomeWidget> = emptyList()) {

    /** In the order they should be drawn, skipping what is switched off. */
    val shown: List<HomeWidget> get() = widgets.filter { it.visible }

    operator fun get(kind: HomeWidgetKind): HomeWidget =
        widgets.firstOrNull { it.kind == kind } ?: HomeWidget(kind)

    fun with(updated: HomeWidget): HomeLayout =
        copy(widgets = widgets.map { if (it.kind == updated.kind) updated.sane() else it })

    /**
     * Moved one place towards the front or back.
     *
     * Over the whole list rather than only the visible part: a hidden
     * widget still holds a position, and stepping over it would make
     * one press do nothing for no reason anybody could see.
     */
    fun moved(kind: HomeWidgetKind, by: Int): HomeLayout {
        val i = widgets.indexOfFirst { it.kind == kind }
        if (i < 0) return this
        val j = (i + by).coerceIn(0, widgets.size - 1)
        if (i == j) return this
        val next = widgets.toMutableList()
        next.add(j, next.removeAt(i))
        return copy(widgets = next)
    }

    /**
     * [kind] taken out and put back where [target] currently sits.
     *
     * What a drag actually means: the thing in your hand goes where
     * the thing you are hovering over is, and that one shuffles along.
     * Expressed against the whole list rather than the visible part,
     * so a hidden widget keeps its place in the order.
     */
    fun movedTo(kind: HomeWidgetKind, target: HomeWidgetKind): HomeLayout {
        if (kind == target) return this
        val from = widgets.indexOfFirst { it.kind == kind }
        val to = widgets.indexOfFirst { it.kind == target }
        if (from < 0 || to < 0) return this
        val next = widgets.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(widgets = next)
    }

    /**
     * Every kind present exactly once, clamped, with anything the
     * stored copy never heard of appended switched off.
     *
     * A layout missing a widget is how a new kind reaches somebody who
     * saved a layout before it existed: it arrives hidden rather than
     * rearranging a page they had already set up.
     */
    fun complete(): HomeLayout {
        val seen = mutableSetOf<HomeWidgetKind>()
        val kept = widgets.filter { seen.add(it.kind) }.map { it.sane() }
        val missing = HomeWidgetKind.entries
            .filterNot { it in seen }
            .map { HomeWidget(kind = it, visible = false) }
        return HomeLayout(kept + missing)
    }

    companion object {
        /**
         * What somebody sees before they have touched any of it: the
         * dial across the top because it is the centrepiece, then the
         * two panels that carry live traffic, then the calendar.
         * Status starts off — it is the one people either want badly or
         * not at all.
         */
        val DEFAULT = HomeLayout(
            listOf(
                HomeWidget(HomeWidgetKind.CLOCK, span = 1, rows = 2),
                HomeWidget(HomeWidgetKind.MESSAGES, count = 5, span = 1, rows = 1),
                HomeWidget(HomeWidgetKind.MAIL, count = 5, span = 1, rows = 1),
                HomeWidget(HomeWidgetKind.CALENDAR, span = 1, rows = 1),
                HomeWidget(HomeWidgetKind.STATUS, visible = false, count = 5),
            ),
        )
    }
}

/**
 * The shown widgets packed into rows of at most [columns] wide.
 *
 * Greedy, left to right, in the order the user arranged them: a widget
 * that will not fit beside what is already on the row starts the next
 * one. No shuffling to fill gaps — somebody who put mail second
 * expects to find it second, not tucked into a hole further down.
 *
 * One column means one widget per row, whatever any of them asked for.
 */
fun packRows(shown: List<HomeWidget>, columns: Int): List<List<HomeWidget>> {
    if (columns <= 1) return shown.map { listOf(it) }
    val rows = mutableListOf<MutableList<HomeWidget>>()
    var used = columns // forces the first widget to open a row
    for (w in shown) {
        val span = w.span.coerceIn(1, columns)
        if (used + span > columns) {
            rows += mutableListOf(w)
            used = span
        } else {
            rows.last() += w
            used += span
        }
    }
    return rows
}

/**
 * Where a sideways drag of [dragPx] leaves a widget that started at
 * [startSpan], given a column [unitPx] wide.
 *
 * Snapped to whole columns: the grid has two of them and a widget that
 * followed the finger continuously would only ever be settling back
 * onto one of two positions anyway. Rounding means the handle flips at
 * the half-way mark, which is where it looks like it should.
 */
fun resizedSpan(startSpan: Int, dragPx: Float, unitPx: Float, columns: Int): Int {
    if (unitPx <= 0f) return startSpan.coerceIn(1, columns)
    val steps = (dragPx / unitPx).roundToInt()
    return (startSpan + steps).coerceIn(1, columns.coerceAtLeast(1))
}

/** The same, downwards, in row units. */
fun resizedRows(startRows: Int, dragPx: Float, unitPx: Float): Int {
    if (unitPx <= 0f) return startRows.coerceIn(HOME_ROW_RANGE.first, HOME_ROW_RANGE.last)
    val steps = (dragPx / unitPx).roundToInt()
    return (startRows + steps).coerceIn(HOME_ROW_RANGE.first, HOME_ROW_RANGE.last)
}

/**
 * The layout as one stored string.
 *
 * JSON rather than the comma-separated line the place uses, because
 * this has a list inside a list and a codec for that written by hand
 * is a bug waiting to happen. Unknown keys are ignored and every field
 * has a default, so a layout saved by a newer build still opens in an
 * older one rather than resetting somebody's page.
 */
object HomeLayoutCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(layout: HomeLayout): String = json.encodeToString(layout)

    /** Anything unreadable falls back to the default rather than to an
     *  empty page, which would look like the feature had been removed. */
    fun decode(stored: String): HomeLayout {
        if (stored.isBlank()) return HomeLayout.DEFAULT
        val parsed = runCatching { json.decodeFromString<HomeLayout>(stored) }.getOrNull()
            ?: return HomeLayout.DEFAULT
        if (parsed.widgets.isEmpty()) return HomeLayout.DEFAULT
        return parsed.complete()
    }
}
