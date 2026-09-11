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
        val future = """{"widgets":[{"kind":"CLOCK","span":2,"somethingNew":7}],"alsoNew":"x"}"""
        val back = HomeLayoutCodec.decode(future)
        assertEquals(2, back[HomeWidgetKind.CLOCK].span)
    }

    @Test
    fun `nonsense sizes are clamped to what the grid can draw`() {
        val silly = HomeLayout(
            listOf(HomeWidget(HomeWidgetKind.MAIL, count = 900, span = 7, rows = 99)),
        ).complete()
        val w = silly[HomeWidgetKind.MAIL]
        assertEquals(HOME_COUNTS.last(), w.count)
        assertEquals(HOME_COLUMNS, w.span)
        assertEquals(HOME_ROW_RANGE.last, w.rows)
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
