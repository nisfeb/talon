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

/**
 * Columns the grid offers at its widest.
 *
 * Twelve because two was the size of the step, not the size of the
 * grid: a widget could be half the page or all of it and nothing in
 * between. Twelve divides by two, three, four and six, so the useful
 * fractions all land on whole columns.
 */
const val HOME_COLUMNS = 12

/**
 * How wide a widget may be, in columns.
 *
 * Never narrower than a quarter of the page. A two-column widget is
 * arithmetically possible and is a sliver nothing reads in.
 */
val HOME_SPAN_RANGE = 3..HOME_COLUMNS

/**
 * How tall a widget may be, in row units of [HOME_ROW_UNIT_DP].
 *
 * Eighteen of them rather than twelve of a taller unit: the unit is
 * the size of the step, and the clock spends a fixed amount of its
 * height on chrome before the dial gets any, so a coarse unit left
 * only a handful of heights that changed anything at all.
 */
val HOME_ROW_RANGE = 3..18

/** How tall one row unit is. Here rather than in the drawing because
 *  migrations have to know what a stored row count was worth. */
const val HOME_ROW_UNIT_DP = 40

/**
 * What the layout's numbers currently mean.
 *
 * Bumped when a stored span or row count would be read as the wrong
 * size. Version 1 counted a two-column grid in 168dp rows; version 2
 * counts twelve columns in 56dp ones, and a version 1 layout read
 * without scaling would come back as a row of slivers.
 */
const val HOME_LAYOUT_VERSION = 4

/** How much finer version 2 is than version 1, per axis. */
private const val V2_COLUMN_SCALE = 6
private const val V2_ROW_SCALE = 3

/** Version 3 keeps version 2's columns and halves its row unit from
 *  56dp to 40dp, so a stored height is worth 56/40 of what it was. */
private const val V3_ROW_SCALE = 56.0 / HOME_ROW_UNIT_DP

@Serializable
data class HomeWidget(
    val kind: HomeWidgetKind,
    val visible: Boolean = true,
    /** How many rows a list widget shows. Ignored by the clock. */
    val count: Int = 5,
    /**
     * Where its top left corner sits, in columns from the left.
     *
     * A coordinate rather than a place in a queue. Packed from an
     * ordered list, a widget's row was worked out rather than chosen,
     * so there was no way to say "the calendar goes on the second row"
     * — and the arrangement somebody saw while dragging was not the
     * one they got when they stopped.
     */
    val col: Int = 0,
    /** And in row units from the top. */
    val row: Int = 0,
    /** Grid columns it takes, within [HOME_SPAN_RANGE]. */
    val span: Int = HOME_COLUMNS / 2,
    /** Grid rows it takes, within [HOME_ROW_RANGE]. */
    val rows: Int = 4,
    val calendarRange: CalendarRange = CalendarRange.REST_OF_DAY,
    /** Ships whose status is kept at the top of the status widget,
     *  in the order they were pinned. */
    val pinned: List<String> = emptyList(),
) {
    /** Clamped into what the grid can actually draw, whatever a stored
     *  line or an older version happened to say. */
    fun sane(): HomeWidget {
        val w = span.coerceIn(HOME_SPAN_RANGE.first, HOME_SPAN_RANGE.last)
        return copy(
            count = count.coerceIn(HOME_COUNTS.first(), HOME_COUNTS.last()),
            span = w,
            rows = rows.coerceIn(HOME_ROW_RANGE.first, HOME_ROW_RANGE.last),
            // Never hanging off the right-hand edge, whatever a stored
            // line or a half-finished drag happened to say.
            col = col.coerceIn(0, HOME_COLUMNS - w),
            row = row.coerceAtLeast(0),
            pinned = pinned.distinct().take(HOME_PINNED_MAX),
        )
    }

    /** One past its right-hand column. */
    val right: Int get() = col + span

    /** One past its bottom row. */
    val bottom: Int get() = row + rows
}

/** Whether two widgets are trying to occupy the same squares. */
fun overlaps(a: HomeWidget, b: HomeWidget): Boolean =
    a.col < b.right && b.col < a.right && a.row < b.bottom && b.row < a.bottom

/** More pinned people than this and the widget is just a contact list. */
const val HOME_PINNED_MAX = 8

@Serializable
data class HomeLayout(
    val widgets: List<HomeWidget> = emptyList(),
    /** Defaults to 1 because a layout written before versioning has no
     *  such key, and that is exactly what it is. */
    val version: Int = 1,
) {

    /** In the order they should be drawn, skipping what is switched off. */
    val shown: List<HomeWidget> get() = widgets.filter { it.visible }

    operator fun get(kind: HomeWidgetKind): HomeWidget =
        widgets.firstOrNull { it.kind == kind } ?: HomeWidget(kind)

    fun with(updated: HomeWidget): HomeLayout =
        copy(widgets = widgets.map { if (it.kind == updated.kind) updated.sane() else it })

    /**
     * [kind] put down with its top left corner at [col], [row].
     *
     * Whatever it lands on is pushed down out of the way, the way every
     * dashboard grid does it: the thing in your hand goes exactly where
     * you let go of it, and the rest gets on with accommodating that.
     */
    fun placed(kind: HomeWidgetKind, col: Int, row: Int): HomeLayout {
        val moving = widgets.firstOrNull { it.kind == kind } ?: return this
        val put = moving.copy(col = col, row = row).sane()
        return copy(widgets = widgets.map { if (it.kind == kind) put else it })
            .resolved(kind)
    }

    /**
     * Nothing on top of anything else, with [anchor] left exactly where
     * it is and everything else shuffled downwards until it fits.
     *
     * Downwards only. Sideways would move a widget out from under the
     * pointer that is placing it, and upwards would close the gaps
     * somebody deliberately left.
     */
    fun resolved(anchor: HomeWidgetKind?): HomeLayout {
        val settled = mutableListOf<HomeWidget>()
        // The anchor first so it keeps its place, then the rest from
        // the top down so the page is rebuilt in reading order.
        val order = widgets.filter { it.visible }
            .sortedWith(compareBy({ it.kind != anchor }, { it.row }, { it.col }))
        for (w in order) {
            var cur = w.sane()
            if (w.kind != anchor) {
                while (settled.any { overlaps(it, cur) }) cur = cur.copy(row = cur.row + 1)
            }
            settled += cur
        }
        val byKind = settled.associateBy { it.kind }
        return copy(widgets = widgets.map { byKind[it.kind] ?: it })
    }

    /** How many row units tall the whole arrangement is. */
    fun heightInRows(): Int = shown.maxOfOrNull { it.bottom } ?: 0

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
        // Resolved on the way in as well as on every move. A stored
        // line can overlap — hand-edited, or written by a migration
        // from a model that had no coordinates — and two widgets drawn
        // on top of each other is the one state the page cannot
        // recover from on its own.
        return HomeLayout(kept + missing, version = HOME_LAYOUT_VERSION).resolved(null)
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
                HomeWidget(HomeWidgetKind.CLOCK, col = 0, row = 0, span = 5, rows = 9),
                HomeWidget(HomeWidgetKind.MESSAGES, count = 5, col = 5, row = 0, span = 7, rows = 5),
                HomeWidget(HomeWidgetKind.MAIL, count = 5, col = 5, row = 5, span = 7, rows = 4),
                HomeWidget(HomeWidgetKind.CALENDAR, col = 0, row = 9, span = 5, rows = 4),
                HomeWidget(
                    HomeWidgetKind.STATUS, visible = false, count = 5,
                    col = 5, row = 9, span = 7, rows = 4,
                ),
            ),
            version = HOME_LAYOUT_VERSION,
        )
    }
}

/**
 * Laid out one under another, for a window too narrow to have columns.
 *
 * A phone has no second column to put anything in, so the coordinates
 * collapse to their reading order and every widget takes the full
 * width. Its height is kept, because that is a choice about the widget
 * rather than about the grid.
 */
fun stacked(shown: List<HomeWidget>): List<HomeWidget> {
    var row = 0
    return shown.sortedWith(compareBy({ it.row }, { it.col })).map { w ->
        // Rows are reassigned, not kept. Two widgets that sat side by
        // side shared a row perfectly well while they were half the
        // page each; made full width and left where they were, they
        // land on top of one another.
        val placed = w.copy(col = 0, row = row, span = HOME_COLUMNS)
        row += w.rows
        placed
    }
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
    val widest = columns.coerceAtLeast(1)
    val narrowest = HOME_SPAN_RANGE.first.coerceAtMost(widest)
    if (unitPx <= 0f) return startSpan.coerceIn(narrowest, widest)
    val steps = (dragPx / unitPx).roundToInt()
    return (startSpan + steps).coerceIn(narrowest, widest)
}

/** The same, downwards, in row units. */
fun resizedRows(startRows: Int, dragPx: Float, unitPx: Float): Int {
    if (unitPx <= 0f) return startRows.coerceIn(HOME_ROW_RANGE.first, HOME_ROW_RANGE.last)
    val steps = (dragPx / unitPx).roundToInt()
    return (startRows + steps).coerceIn(HOME_ROW_RANGE.first, HOME_ROW_RANGE.last)
}

/**
 * Which column and row a widget's top left corner lands in, given how
 * far it has been dragged from where it started.
 *
 * Rounded, so it settles on the nearest square rather than the one it
 * has fully entered — the same reason the resize handles round.
 */
fun droppedAt(
    startCol: Int,
    startRow: Int,
    dragXPx: Float,
    dragYPx: Float,
    colPitchPx: Float,
    rowPitchPx: Float,
): Pair<Int, Int> {
    val c = if (colPitchPx <= 0f) startCol else startCol + (dragXPx / colPitchPx).roundToInt()
    val r = if (rowPitchPx <= 0f) startRow else startRow + (dragYPx / rowPitchPx).roundToInt()
    return c to r.coerceAtLeast(0)
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

    fun encode(layout: HomeLayout): String =
        json.encodeToString(layout.copy(version = HOME_LAYOUT_VERSION))

    /**
     * Old numbers read as the sizes they were meant to be.
     *
     * A version 1 span of 1 meant half the page and a row meant 168dp.
     * Read against the finer grid without scaling, somebody who had
     * arranged their page would open it to find every widget shrunk to
     * a sliver — which is a worse first impression than the feature
     * never having existed.
     */
    private fun migrate(layout: HomeLayout): HomeLayout {
        if (layout.version >= HOME_LAYOUT_VERSION) return layout
        var m = layout
        // Step by step rather than one combined factor, so each change
        // to the grid can be read against the one before it.
        if (m.version < 2) {
            m = m.copy(
                widgets = m.widgets.map {
                    it.copy(span = it.span * V2_COLUMN_SCALE, rows = it.rows * V2_ROW_SCALE)
                },
                version = 2,
            )
        }
        if (m.version < 3) {
            m = m.copy(
                widgets = m.widgets.map {
                    it.copy(rows = (it.rows * V3_ROW_SCALE).roundToInt())
                },
                version = 3,
            )
        }
        if (m.version < 4) m = m.copy(widgets = coordinatesFor(m.widgets), version = 4)
        return m.copy(version = HOME_LAYOUT_VERSION)
    }

    /**
     * Coordinates for a layout that only had an order.
     *
     * Versions up to three packed an ordered list into rows greedily,
     * so this reads that packing once and writes down where each widget
     * actually ended up. The page somebody had opens looking the same;
     * the difference is that from now on they can move things off it.
     */
    private fun coordinatesFor(widgets: List<HomeWidget>): List<HomeWidget> {
        var col = 0
        var row = 0
        var tallest = 0
        return widgets.map { w ->
            if (!w.visible) return@map w
            val span = w.span.coerceIn(1, HOME_COLUMNS)
            if (col + span > HOME_COLUMNS) {
                col = 0
                row += tallest
                tallest = 0
            }
            val placed = w.copy(col = col, row = row)
            col += span
            if (w.rows > tallest) tallest = w.rows
            placed
        }
    }

    /** Anything unreadable falls back to the default rather than to an
     *  empty page, which would look like the feature had been removed. */
    fun decode(stored: String): HomeLayout {
        if (stored.isBlank()) return HomeLayout.DEFAULT
        val parsed = runCatching { json.decodeFromString<HomeLayout>(stored) }.getOrNull()
            ?: return HomeLayout.DEFAULT
        if (parsed.widgets.isEmpty()) return HomeLayout.DEFAULT
        return migrate(parsed).complete()
    }
}
