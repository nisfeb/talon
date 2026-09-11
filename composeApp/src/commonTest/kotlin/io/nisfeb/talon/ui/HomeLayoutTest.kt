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
        assertEquals(6, back[HomeWidgetKind.CLOCK].rows, "two old rows is six new ones")
        assertEquals(3, back[HomeWidgetKind.MAIL].rows)
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
    fun `moving a widget changes the order`() {
        val l = HomeLayout.DEFAULT
        val first = l.widgets.first().kind
        val moved = l.moved(first, 1)
        assertEquals(first, moved.widgets[1].kind)
        assertNotEquals(l.widgets.map { it.kind }, moved.widgets.map { it.kind })
    }

    @Test
    fun `moving off either end does nothing rather than throwing`() {
        val l = HomeLayout.DEFAULT
        assertEquals(l, l.moved(l.widgets.first().kind, -1))
        assertEquals(l, l.moved(l.widgets.last().kind, 1))
        assertEquals(l, l.moved(l.widgets.first().kind, -50))
    }

    @Test
    fun `moving steps over hidden widgets too`() {
        // A hidden widget still holds a place. Skipping it would make
        // one press do nothing for a reason nobody could see.
        val l = HomeLayout.DEFAULT
        val last = l.widgets.last()
        assertTrue(!last.visible, "this test wants the default's hidden widget at the end")
        val moved = l.moved(l.widgets[l.widgets.size - 2].kind, 1)
        assertEquals(last.kind, moved.widgets[moved.widgets.size - 2].kind)
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

class HomeGridTest {

    private fun w(kind: HomeWidgetKind, span: Int) = HomeWidget(kind, span = span)
    private val k = HomeWidgetKind.entries

    @Test
    fun `narrow layouts give every widget its own row`() {
        val shown = k.map { w(it, span = 2) }
        val rows = packRows(shown, columns = 1)
        assertEquals(shown.size, rows.size)
        assertTrue(rows.all { it.size == 1 })
    }

    @Test
    fun `two single widgets share a row`() {
        val rows = packRows(listOf(w(k[0], 1), w(k[1], 1)), columns = 2)
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].size)
    }

    @Test
    fun `a full width widget gets a row to itself`() {
        val rows = packRows(listOf(w(k[0], 2), w(k[1], 1), w(k[2], 1)), columns = 2)
        assertEquals(2, rows.size)
        assertEquals(listOf(k[0]), rows[0].map { it.kind })
        assertEquals(listOf(k[1], k[2]), rows[1].map { it.kind })
    }

    @Test
    fun `no row is ever overfull`() {
        for (spans in listOf(listOf(1, 2, 1, 1, 2), listOf(2, 2), listOf(1, 1, 1, 1, 1))) {
            val shown = spans.mapIndexed { i, sp -> w(k[i % k.size], sp) }
            for (row in packRows(shown, columns = HOME_COLUMNS)) {
                assertTrue(row.sumOf { it.span } <= HOME_COLUMNS, "row ${row.map { it.span }} overflows")
            }
        }
    }

    @Test
    fun `order is kept exactly`() {
        // Somebody who put mail second expects to find it second, not
        // shuffled into a gap further down the page.
        val shown = listOf(w(k[0], 2), w(k[1], 1), w(k[2], 2), w(k[3], 1))
        val flat = packRows(shown, columns = 2).flatten().map { it.kind }
        assertEquals(shown.map { it.kind }, flat)
    }

    @Test
    fun `a part full row leaves the rest of the columns empty`() {
        // Widths come from weights, so a lone widget takes the whole
        // row unless the remainder is filled. That turned one column of
        // widening into a jump from half the page to all of it.
        assertEquals(HOME_COLUMNS - 7, rowSpare(listOf(w(k[0], 7)), HOME_COLUMNS))
        assertEquals(0, rowSpare(listOf(w(k[0], 6), w(k[1], 6)), HOME_COLUMNS))
        assertEquals(0, rowSpare(listOf(w(k[0], HOME_COLUMNS)), HOME_COLUMNS))
    }

    @Test
    fun `every packed row accounts for all its columns`() {
        val shown = listOf(w(k[0], 7), w(k[1], 4), w(k[2], 12), w(k[3], 3), w(k[4], 3))
        for (row in packRows(shown, HOME_COLUMNS)) {
            val used = row.sumOf { it.span }
            assertEquals(HOME_COLUMNS, used + rowSpare(row, HOME_COLUMNS), "row ${row.map { it.span }}")
        }
    }

    @Test
    fun `a spare column count is never negative`() {
        // An overwide span is clamped by the grid, not carried through
        // into a Spacer with a negative weight, which throws.
        assertEquals(0, rowSpare(listOf(w(k[0], 99)), HOME_COLUMNS))
        assertEquals(0, rowSpare(listOf(w(k[0], 9), w(k[1], 9)), HOME_COLUMNS))
        assertEquals(0, rowSpare(listOf(w(k[0], 5)), 1))
    }

    @Test
    fun `nothing shown is no rows, not one empty row`() {
        assertTrue(packRows(emptyList(), columns = 2).isEmpty())
        assertTrue(packRows(emptyList(), columns = 1).isEmpty())
    }

    @Test
    fun `a span wider than the grid still fits on its own row`() {
        val rows = packRows(listOf(w(k[0], 9)), columns = 2)
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].size)
    }
}

class HomeDragTest {

    private val k = HomeWidgetKind.entries
    private val l = HomeLayout.DEFAULT

    private fun order(layout: HomeLayout) = layout.widgets.map { it.kind }

    @Test
    fun `dragging onto another widget takes its place`() {
        val moved = l.movedTo(k.last(), k.first())
        assertEquals(k.last(), moved.widgets.first().kind)
    }

    @Test
    fun `the one displaced shuffles along rather than vanishing`() {
        val moved = l.movedTo(k.last(), k.first())
        assertEquals(order(l).size, order(moved).size)
        assertEquals(order(l).toSet(), order(moved).toSet())
    }

    @Test
    fun `dragging onto itself changes nothing`() {
        assertEquals(l, l.movedTo(k[1], k[1]))
    }

    @Test
    fun `dragging downwards lands in the right place too`() {
        // Removing first then inserting shifts every later index by one,
        // which is the easy way to get this off by one.
        val first = order(l)[0]
        val third = order(l)[2]
        val moved = l.movedTo(first, third)
        assertEquals(third, order(moved)[1], "the third moved up")
        assertEquals(first, order(moved)[2], "and the first landed on its index")
    }

    @Test
    fun `a widget nobody has heard of is ignored, not an exception`() {
        val partial = HomeLayout(listOf(HomeWidget(k[0]), HomeWidget(k[1])))
        assertEquals(partial, partial.movedTo(k[0], k[4]))
        assertEquals(partial, partial.movedTo(k[4], k[0]))
    }

    @Test
    fun `every drag leaves a layout that still draws`() {
        var cur = l
        for (a in k) for (b in k) {
            cur = cur.movedTo(a, b)
            assertEquals(k.size, cur.widgets.size, "lost a widget dragging $a onto $b")
            assertEquals(k.toSet(), cur.widgets.map { it.kind }.toSet())
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
        assertEquals(2, resizedRows(4, -row * 1.7f, row))
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
