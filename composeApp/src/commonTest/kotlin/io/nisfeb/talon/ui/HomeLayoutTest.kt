package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HomeLayoutTest {

    @Test
    fun `a layout survives being stored and read back`() {
        val arranged = HomeLayout.DEFAULT
            .with(HomeLayout.DEFAULT[HomeWidgetKind.MAIL].copy(count = 8, span = 2, rows = 3))
            .with(
                HomeLayout.DEFAULT[HomeWidgetKind.STATUS]
                    .copy(visible = true, pinned = listOf("~ricsul-bilwyt", "~wex")),
            )
            .with(
                HomeLayout.DEFAULT[HomeWidgetKind.CALENDAR]
                    .copy(calendarRange = CalendarRange.NEXT_3_HOURS),
            )
        val back = HomeLayoutCodec.decode(HomeLayoutCodec.encode(arranged))
        assertEquals(arranged.widgets, back.widgets)
    }

    @Test
    fun `nothing stored is the default, not an empty page`() {
        // An empty home page would read as the feature having been
        // removed rather than as never having been set up.
        assertEquals(HomeLayout.DEFAULT, HomeLayoutCodec.decode(""))
        assertEquals(HomeLayout.DEFAULT, HomeLayoutCodec.decode("   "))
    }

    @Test
    fun `a corrupt line falls back rather than blanking the page`() {
        assertEquals(HomeLayout.DEFAULT, HomeLayoutCodec.decode("{not json"))
        assertEquals(HomeLayout.DEFAULT, HomeLayoutCodec.decode("""{"widgets":[]}"""))
    }

    @Test
    fun `a layout saved before a widget existed gets it switched off`() {
        // The new kind must not rearrange a page somebody already set
        // up, so it arrives hidden and they can turn it on.
        val old = """{"widgets":[{"kind":"CLOCK"},{"kind":"MESSAGES"}]}"""
        val back = HomeLayoutCodec.decode(old)
        assertEquals(HomeWidgetKind.entries.size, back.widgets.size)
        assertEquals(listOf(HomeWidgetKind.CLOCK, HomeWidgetKind.MESSAGES), back.widgets.take(2).map { it.kind })
        for (k in listOf(HomeWidgetKind.MAIL, HomeWidgetKind.CALENDAR, HomeWidgetKind.STATUS)) {
            assertTrue(!back[k].visible, "$k should arrive switched off")
        }
    }

    @Test
    fun `a key a newer build wrote does not reset the page`() {
        val future = """{"version":$HOME_LAYOUT_VERSION,
            "widgets":[{"kind":"CLOCK","span":8,"somethingNew":7}],"alsoNew":"x"}"""
        val back = HomeLayoutCodec.decode(future)
        assertEquals(8, back[HomeWidgetKind.CLOCK].span, "the keys it does know still read")
    }

    @Test
    fun `nonsense sizes are clamped to what the grid can draw`() {
        val silly = HomeLayout(
            listOf(HomeWidget(HomeWidgetKind.MAIL, count = 900, span = 70, rows = 99)),
            version = HOME_LAYOUT_VERSION,
        ).complete()
        val w = silly[HomeWidgetKind.MAIL]
        assertEquals(HOME_COUNTS.last(), w.count)
        assertEquals(HOME_COLUMNS, w.span)
        assertEquals(HOME_ROW_RANGE.last, w.rows)
    }

    @Test
    fun `a widget can never be a sliver`() {
        val thin = HomeLayout(
            listOf(HomeWidget(HomeWidgetKind.MAIL, span = 1, rows = 0)),
            version = HOME_LAYOUT_VERSION,
        ).complete()
        assertEquals(HOME_SPAN_RANGE.first, thin[HomeWidgetKind.MAIL].span)
        assertEquals(HOME_ROW_RANGE.first, thin[HomeWidgetKind.MAIL].rows)
    }

    @Test
    fun `a page arranged on the old coarse grid keeps its proportions`() {
        // Version 1 counted two columns and 168dp rows. Read against
        // the finer grid without scaling, a half-width widget would
        // come back a twelfth of the page.
        val v1 = """{"widgets":[
            {"kind":"CLOCK","span":1,"rows":2},
            {"kind":"MAIL","span":2,"rows":1}
        ]}"""
        val back = HomeLayoutCodec.decode(v1)
        assertEquals(HOME_COLUMNS / 2, back[HomeWidgetKind.CLOCK].span, "half stays half")
        assertEquals(HOME_COLUMNS, back[HomeWidgetKind.MAIL].span, "full stays full")
        // Asserted as height rather than as a row count, because the
        // count only means anything against the unit of the day. Two
        // old rows of 168dp is 336dp, and the nearest the 40dp grid
        // gets is 320.
        assertEquals(336, 2 * 168, "the old unit, for the arithmetic below")
        assertTrue(
            kotlin.math.abs(back[HomeWidgetKind.CLOCK].rows * HOME_ROW_UNIT_DP - 336) <= HOME_ROW_UNIT_DP,
            "the clock came back ${back[HomeWidgetKind.CLOCK].rows * HOME_ROW_UNIT_DP}dp, not about 336",
        )
        assertTrue(
            kotlin.math.abs(back[HomeWidgetKind.MAIL].rows * HOME_ROW_UNIT_DP - 168) <= HOME_ROW_UNIT_DP,
            "the mail came back ${back[HomeWidgetKind.MAIL].rows * HOME_ROW_UNIT_DP}dp, not about 168",
        )
        assertEquals(HOME_LAYOUT_VERSION, back.version, "and it is written back as current")
    }

    @Test
    fun `a current layout is not scaled a second time`() {
        val once = HomeLayoutCodec.decode(HomeLayoutCodec.encode(HomeLayout.DEFAULT))
        val twice = HomeLayoutCodec.decode(HomeLayoutCodec.encode(once))
        assertEquals(once.widgets, twice.widgets)
        assertEquals(HomeLayout.DEFAULT.widgets, once.widgets)
    }

    @Test
    fun `a kind stored twice only appears once`() {
        val doubled = """{"widgets":[{"kind":"MAIL","count":3},{"kind":"MAIL","count":10}]}"""
        val back = HomeLayoutCodec.decode(doubled)
        assertEquals(1, back.widgets.count { it.kind == HomeWidgetKind.MAIL })
        assertEquals(3, back[HomeWidgetKind.MAIL].count, "the first wins")
    }

    @Test
    fun `switching everything off is allowed and stays off`() {
        // Somebody who wants a blank home page may have one; it is only
        // an *unreadable* stored line that falls back to the default.
        val blank = HomeLayout(HomeWidgetKind.entries.map { HomeWidget(it, visible = false) })
        val back = HomeLayoutCodec.decode(HomeLayoutCodec.encode(blank))
        assertTrue(back.shown.isEmpty())
    }

    @Test
    fun `pinned people are kept in order, deduplicated and capped`() {
        val many = (1..20).map { "~ship$it" }
        val w = HomeWidget(HomeWidgetKind.STATUS, pinned = many + many).sane()
        assertEquals(HOME_PINNED_MAX, w.pinned.size)
        assertEquals(many.take(HOME_PINNED_MAX), w.pinned, "order is the order they were pinned")
    }

    @Test
    fun `the default is a page worth looking at`() {
        val d = HomeLayout.DEFAULT.complete()
        assertEquals(HomeWidgetKind.entries.size, d.widgets.size, "every kind is accounted for")
        assertTrue(d[HomeWidgetKind.CLOCK].visible, "the dial is the centrepiece")
        assertTrue(d.shown.size >= 3, "a first run should not look empty")
        assertEquals(HomeWidgetKind.CLOCK, d.shown.first().kind)
    }

    @Test
    fun `every calendar range says how far it looks`() {
        for (r in CalendarRange.entries) {
            assertTrue(r.label.isNotBlank(), "$r has no label")
            r.minutes?.let { assertTrue(it > 0, "$r looks $it minutes ahead") }
        }
    }
}

class HomeResizeTest {

    private val col = 300f // a column's width in pixels
    private val row = 168f // a row unit's height

    @Test
    fun `no drag is no change`() {
        assertEquals(6, resizedSpan(6, 0f, col, HOME_COLUMNS))
        assertEquals(4, resizedRows(4, 0f, row))
    }

    @Test
    fun `the handle flips at the half way mark`() {
        // Rounding, not truncation: a handle that only widened after a
        // whole column felt like it was ignoring you.
        assertEquals(6, resizedSpan(6, col * 0.49f, col, HOME_COLUMNS))
        assertEquals(7, resizedSpan(6, col * 0.51f, col, HOME_COLUMNS))
        assertEquals(3, resizedRows(2, row * 0.6f, row))
    }

    @Test
    fun `dragging back the other way shrinks`() {
        assertEquals(5, resizedSpan(6, -col * 0.8f, col, HOME_COLUMNS))
        assertEquals(6, resizedRows(8, -row * 1.7f, row))
    }

    @Test
    fun `a handle dragged off the screen stops at the edge of the grid`() {
        assertEquals(HOME_COLUMNS, resizedSpan(6, col * 50f, col, HOME_COLUMNS))
        assertEquals(HOME_SPAN_RANGE.first, resizedSpan(6, -col * 50f, col, HOME_COLUMNS))
        assertEquals(HOME_ROW_RANGE.last, resizedRows(3, row * 50f, row))
        assertEquals(HOME_ROW_RANGE.first, resizedRows(3, -row * 50f, row))
    }

    @Test
    fun `one column means a widget can only ever be one wide`() {
        assertEquals(1, resizedSpan(1, col * 10f, col, 1))
        assertEquals(1, resizedSpan(6, col * 10f, col, 1))
    }

    @Test
    fun `the steps are fine enough to be worth dragging`() {
        // The complaint that prompted the finer grid: two columns and
        // three row heights meant every drag jumped half the page.
        assertTrue(HOME_COLUMNS >= 8, "$HOME_COLUMNS columns is a step, not a grid")
        assertTrue(HOME_SPAN_RANGE.count() >= 6, "only ${HOME_SPAN_RANGE.count()} widths on offer")
        assertTrue(HOME_ROW_RANGE.count() >= 6, "only ${HOME_ROW_RANGE.count()} heights on offer")
    }

    /** One grip drag, frame by frame, the way the gesture delivers it. */
    private fun drag(
        from: Int,
        travel: Float,
        frames: Int = 24,
        liveBase: Boolean = false,
    ): Int {
        var span = from
        var total = 0f
        repeat(frames) {
            total += travel / frames
            span = resizedSpan(if (liveBase) span else from, total, col, HOME_COLUMNS)
        }
        return span
    }

    @Test
    fun `dragging one column wide widens by exactly one column`() {
        assertEquals(7, drag(from = 6, travel = col))
        assertEquals(8, drag(from = 6, travel = col * 2))
        assertEquals(5, drag(from = 6, travel = -col))
    }

    @Test
    fun `the handle keeps up with the pointer rather than running ahead`() {
        // The fault this guards: the running total is measured from
        // where the pointer went down, so it has to be added to the
        // size the widget had then. Added to the size it has *now*, the
        // first snap becomes the new base and the same total reads as
        // another column, and another — a mouse moved one column wide
        // sent the widget clear across the grid.
        val honest = drag(from = 6, travel = col, liveBase = false)
        val compounding = drag(from = 6, travel = col, liveBase = true)
        assertEquals(7, honest)
        assertTrue(
            compounding > honest,
            "the live-base form should overshoot, or this test is not watching anything",
        )
    }

    @Test
    fun `a slow drag lands in the same place as a fast one`() {
        // Same travel, different numbers of frames: the result must
        // come from where the pointer is, not from how it got there.
        for (frames in listOf(1, 3, 24, 200)) {
            assertEquals(8, drag(from = 6, travel = col * 2, frames = frames), "at $frames frames")
        }
    }

    @Test
    fun `a narrow drag moves one column, not half the page`() {
        assertEquals(7, resizedSpan(6, col, col, HOME_COLUMNS))
        assertEquals(5, resizedSpan(6, -col, col, HOME_COLUMNS))
    }

    @Test
    fun `a zero sized unit does not divide by zero`() {
        // Measured from layout, so it is zero for the frame before the
        // widget has been placed.
        assertEquals(6, resizedSpan(6, 500f, 0f, HOME_COLUMNS))
        assertEquals(4, resizedRows(4, 500f, 0f))
    }

    @Test
    fun `every drag lands on something the grid can draw`() {
        for (start in HOME_SPAN_RANGE) {
            for (px in -2000..2000 step 37) {
                val sp = resizedSpan(start, px.toFloat(), col, HOME_COLUMNS)
                assertTrue(sp in HOME_SPAN_RANGE, "span $sp from $px")
            }
        }
        for (start in HOME_ROW_RANGE) {
            for (px in -2000..2000 step 37) {
                val r = resizedRows(start, px.toFloat(), row)
                assertTrue(r in HOME_ROW_RANGE, "rows $r from $px")
            }
        }
    }
}


/**
 * Widgets have coordinates now rather than a place in a queue.
 *
 * Packed from an order, a widget's row was worked out instead of
 * chosen: there was no way to say "the calendar goes on the second
 * row", and the arrangement somebody saw while dragging was not the
 * one they got when they let go.
 */
class HomePlacementTest {

    private val k = HomeWidgetKind.entries
    private fun w(kind: HomeWidgetKind, col: Int, row: Int, span: Int = 6, rows: Int = 4) =
        HomeWidget(kind, col = col, row = row, span = span, rows = rows)

    private fun layout(vararg ws: HomeWidget) = HomeLayout(ws.toList(), HOME_LAYOUT_VERSION)

    @Test
    fun `a widget goes exactly where it is put`() {
        val l = layout(w(k[0], 0, 0), w(k[1], 6, 0))
        val moved = l.placed(k[1], col = 3, row = 7)
        assertEquals(3, moved[k[1]].col)
        assertEquals(7, moved[k[1]].row)
    }

    @Test
    fun `the second row is reachable`() {
        // The complaint outright: a widget could not be put on the row
        // below, because rows were a consequence of the order.
        val l = layout(w(k[0], 0, 0, rows = 4), w(k[1], 6, 0, rows = 4))
        val moved = l.placed(k[1], col = 0, row = 4)
        assertEquals(4, moved[k[1]].row)
        assertEquals(0, moved[k[0]].row, "and the one above it did not move")
    }

    @Test
    fun `a gap somebody left is left alone`() {
        // Nothing is pulled upwards to close it. An empty row is a
        // choice as much as a full one.
        val l = layout(w(k[0], 0, 0, rows = 4), w(k[1], 0, 9, rows = 4))
        assertEquals(9, l.resolved(null)[k[1]].row)
    }

    @Test
    fun `dropping onto something pushes it down, not sideways`() {
        // Sideways would shove a widget out from under the pointer
        // that is placing it.
        val l = layout(w(k[0], 0, 0, rows = 4), w(k[1], 0, 4, rows = 4))
        val moved = l.placed(k[0], col = 0, row = 4)
        assertEquals(4, moved[k[0]].row, "the one in hand stays where it was dropped")
        assertTrue(moved[k[1]].row >= 8, "and the one underneath moved down, not across")
        assertEquals(0, moved[k[1]].col)
    }

    @Test
    fun `nothing ever ends up on top of anything else`() {
        val l = layout(
            w(k[0], 0, 0, rows = 4), w(k[1], 0, 0, rows = 4),
            w(k[2], 0, 0, rows = 4), w(k[3], 0, 0, rows = 4),
        )
        val settled = l.resolved(k[0]).shown
        for (i in settled.indices) {
            for (j in i + 1 until settled.size) {
                assertTrue(
                    !overlaps(settled[i], settled[j]),
                    "${settled[i].kind} and ${settled[j].kind} overlap",
                )
            }
        }
    }

    @Test
    fun `a hidden widget neither blocks nor is blocked`() {
        val l = layout(w(k[0], 0, 0), HomeWidget(k[1], visible = false, col = 0, row = 0))
        assertEquals(0, l.resolved(null)[k[0]].row)
    }

    @Test
    fun `nothing may hang off the right hand edge`() {
        val l = layout(w(k[0], 0, 0, span = 7))
        assertEquals(HOME_COLUMNS - 7, l.placed(k[0], col = 11, row = 0)[k[0]].col)
        assertEquals(0, l.placed(k[0], col = -4, row = 0)[k[0]].col)
    }

    @Test
    fun `a widget cannot be dropped above the top`() {
        val l = layout(w(k[0], 0, 4))
        assertEquals(0, l.placed(k[0], col = 0, row = -3)[k[0]].row)
    }

    @Test
    fun `the height is the lowest edge, not the widget count`() {
        val l = layout(w(k[0], 0, 0, rows = 4), w(k[1], 6, 2, rows = 9))
        assertEquals(11, l.heightInRows())
        assertEquals(0, HomeLayout(emptyList()).heightInRows())
    }

    @Test
    fun `a narrow window stacks them in reading order`() {
        val l = layout(w(k[0], 6, 0), w(k[1], 0, 0), w(k[2], 0, 4))
        val stack = stacked(l.shown)
        assertEquals(listOf(k[1], k[0], k[2]), stack.map { it.kind })
        assertTrue(stack.all { it.col == 0 && it.span == HOME_COLUMNS })
        assertEquals(l.shown.map { it.rows }.toSet(), stack.map { it.rows }.toSet(), "heights survive")
    }

    @Test
    fun `a page arranged before coordinates opens looking the same`() {
        // Version 3 packed an ordered list greedily. The migration reads
        // that packing once and writes down where each widget was.
        val v3 = """{"version":3,"widgets":[
            {"kind":"CLOCK","span":6,"rows":9},
            {"kind":"MESSAGES","span":6,"rows":5},
            {"kind":"MAIL","span":6,"rows":5},
            {"kind":"CALENDAR","span":6,"rows":4}
        ]}"""
        val back = HomeLayoutCodec.decode(v3)
        assertEquals(0 to 0, back[HomeWidgetKind.CLOCK].col to back[HomeWidgetKind.CLOCK].row)
        assertEquals(6 to 0, back[HomeWidgetKind.MESSAGES].col to back[HomeWidgetKind.MESSAGES].row)
        assertEquals(0 to 9, back[HomeWidgetKind.MAIL].col to back[HomeWidgetKind.MAIL].row, "second row")
        assertEquals(6 to 9, back[HomeWidgetKind.CALENDAR].col to back[HomeWidgetKind.CALENDAR].row)
        assertEquals(HOME_LAYOUT_VERSION, back.version)
    }

    @Test
    fun `the default arrangement does not overlap itself`() {
        val d = HomeLayout.DEFAULT.complete().shown
        for (i in d.indices) {
            for (j in i + 1 until d.size) {
                assertTrue(!overlaps(d[i], d[j]), "${d[i].kind} sits on ${d[j].kind}")
            }
        }
        assertTrue(d.all { it.right <= HOME_COLUMNS }, "something hangs off the edge")
    }
}

class HomeDropTest {

    @Test
    fun `no drag leaves it where it was`() {
        assertEquals(3 to 5, droppedAt(3, 5, 0f, 0f, 100f, 40f))
    }

    @Test
    fun `it settles on the nearest square, not the one fully entered`() {
        assertEquals(3 to 5, droppedAt(3, 5, 49f, 19f, 100f, 40f))
        assertEquals(4 to 6, droppedAt(3, 5, 51f, 21f, 100f, 40f))
        assertEquals(1 to 3, droppedAt(3, 5, -180f, -90f, 100f, 40f))
    }

    @Test
    fun `it cannot be dropped above the top`() {
        assertEquals(0, droppedAt(1, 1, 0f, -900f, 100f, 40f).second)
    }

    @Test
    fun `an unmeasured grid does not divide by zero`() {
        // Zero for the frame before the grid has been laid out.
        assertEquals(3 to 5, droppedAt(3, 5, 400f, 400f, 0f, 0f))
    }
}

/**
 * A window too narrow for columns stacks the widgets instead.
 *
 * Collapsing them to full width without moving them piled every
 * widget that had shared a row on top of the others.
 */
class HomeStackTest {

    private val k = HomeWidgetKind.entries
    private fun w(kind: HomeWidgetKind, col: Int, row: Int, span: Int = 6, rows: Int = 4) =
        HomeWidget(kind, col = col, row = row, span = span, rows = rows)

    @Test
    fun `widgets that shared a row do not land on each other`() {
        val stack = stacked(listOf(w(k[0], 0, 0), w(k[1], 6, 0), w(k[2], 0, 4)))
        for (i in stack.indices) {
            for (j in i + 1 until stack.size) {
                assertTrue(!overlaps(stack[i], stack[j]), "${stack[i].kind} sits on ${stack[j].kind}")
            }
        }
    }

    @Test
    fun `each one starts where the one above it ends`() {
        val stack = stacked(listOf(w(k[0], 0, 0, rows = 9), w(k[1], 6, 0, rows = 5)))
        assertEquals(0, stack[0].row)
        assertEquals(9, stack[1].row, "the second begins where the first finishes")
    }

    @Test
    fun `reading order decides the order`() {
        val stack = stacked(listOf(w(k[2], 0, 8), w(k[0], 6, 0), w(k[1], 0, 0)))
        assertEquals(listOf(k[1], k[0], k[2]), stack.map { it.kind })
    }

    @Test
    fun `heights survive and widths do not`() {
        // Height is a choice about the widget; width is a fact about
        // the grid, and a narrow window has only the one column.
        val shown = listOf(w(k[0], 0, 0, span = 5, rows = 9), w(k[1], 5, 0, span = 7, rows = 4))
        val stack = stacked(shown)
        assertEquals(listOf(9, 4), stack.map { it.rows })
        assertTrue(stack.all { it.span == HOME_COLUMNS && it.col == 0 })
    }

    @Test
    fun `nothing stacked is nothing, not a crash`() {
        assertTrue(stacked(emptyList()).isEmpty())
    }

    @Test
    fun `a decoded layout never overlaps itself`() {
        // A stored line can overlap: hand-edited, or written by a
        // migration from a model that had no coordinates at all.
        val piled = """{"version":$HOME_LAYOUT_VERSION,"widgets":[
            {"kind":"CLOCK","col":0,"row":0,"span":6,"rows":4},
            {"kind":"MAIL","col":0,"row":0,"span":6,"rows":4},
            {"kind":"MESSAGES","col":3,"row":1,"span":6,"rows":4}
        ]}"""
        val shown = HomeLayoutCodec.decode(piled).shown
        for (i in shown.indices) {
            for (j in i + 1 until shown.size) {
                assertTrue(!overlaps(shown[i], shown[j]), "${shown[i].kind} sits on ${shown[j].kind}")
            }
        }
    }

    @Test
    fun `the default still opens exactly as it was written`() {
        // Resolving on decode must not quietly rearrange a layout that
        // was already fine.
        val back = HomeLayoutCodec.decode(HomeLayoutCodec.encode(HomeLayout.DEFAULT))
        for (kind in HomeWidgetKind.entries) {
            val a = HomeLayout.DEFAULT[kind]
            val b = back[kind]
            assertEquals(a.col to a.row, b.col to b.row, "$kind moved")
        }
    }
}
