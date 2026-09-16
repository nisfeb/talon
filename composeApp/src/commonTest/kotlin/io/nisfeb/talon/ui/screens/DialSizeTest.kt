package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.HOME_ROW_RANGE
import io.nisfeb.talon.ui.HOME_ROW_UNIT_DP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dial is square, so its width sets the clock widget's height.
 * Pinned at one size it ignored every height below its own, and the
 * clock drew identically from two row units to six.
 */
class DialSizeTest {

    @Test
    fun `every row count changes the size`() {
        // Not most of them, all of them: a clamp at either end is a run
        // of heights that look identical while arranging, which is the
        // fault this replaced.
        var last = dialSizeFor(HOME_ROW_RANGE.first)
        for (rows in HOME_ROW_RANGE.first + 1..HOME_ROW_RANGE.last) {
            val now = dialSizeFor(rows)
            assertTrue(now > last, "$rows rows draws the same as ${rows - 1}")
            last = now
        }
    }

    @Test
    fun `no step is bigger than a row unit`() {
        for (rows in HOME_ROW_RANGE.first until HOME_ROW_RANGE.last) {
            val step = dialSizeFor(rows + 1).value - dialSizeFor(rows).value
            assertTrue(
                step <= HOME_ROW_UNIT_DP.toFloat() + 0.01f,
                "going from $rows to ${rows + 1} rows grows the dial by ${step}dp",
            )
        }
    }

    @Test
    fun `the smallest steps are no worse than the cells they come from`() {
        // The complaint that started this: one row unit of drag nearly
        // doubled the dial, because a flat chrome allowance ate most of
        // the smallest cell and the dial began near nothing. The dial's
        // proportions have to track the cell's own.
        for (rows in HOME_ROW_RANGE.first until HOME_ROW_RANGE.last) {
            val dialStep = dialSizeFor(rows + 1).value / dialSizeFor(rows).value
            val cellStep = (rows + 1).toFloat() / rows
            assertTrue(
                dialStep <= cellStep * 1.1f,
                "at $rows rows the dial grows by ${(dialStep - 1) * 100}% " +
                    "where the cell grows by ${(cellStep - 1) * 100}%",
            )
        }
    }

    @Test
    fun `the smallest dial is not a dot`() {
        val smallest = dialSizeFor(HOME_ROW_RANGE.first)
        val cell = HOME_ROW_RANGE.first * HOME_ROW_UNIT_DP
        assertTrue(
            smallest.value > cell * 0.7f,
            "the shortest clock gives the dial ${smallest.value}dp of its ${cell}dp",
        )
    }

    @Test
    fun `arranging offers the sizes a window resize can reach`() {
        // A window dragged narrow takes the dial continuously down to
        // nothing and back up, so a floor or a ceiling on the arranged
        // size is a limit the app visibly does not honour elsewhere.
        assertTrue(dialSizeFor(HOME_ROW_RANGE.first).value < 120f, "the smallest is not small")
        assertTrue(dialSizeFor(HOME_ROW_RANGE.last).value > 500f, "the largest is not large")
    }

    @Test
    fun `a dial is never asked to be a negative size`() {
        for (rows in listOf(-99, -1, 0, 1)) {
            assertTrue(dialSizeFor(rows).value >= 0f, "$rows rows gave ${dialSizeFor(rows)}")
        }
    }

    @Test
    fun `the readout thins out before the dial runs out of room`() {
        // Each threshold has to sit inside the range arranging offers,
        // or the tier below it is unreachable and the readout spills
        // over the edge at the sizes it was meant to cover.
        val smallest = dialSizeFor(HOME_ROW_RANGE.first).value
        val largest = dialSizeFor(HOME_ROW_RANGE.last).value
        for (at in listOf(DIAL_DATE_AT, DIAL_WEATHER_AT, DIAL_RANGE_AT)) {
            assertTrue(at.value in smallest..largest, "$at is outside $smallest..$largest")
        }
        assertTrue(DIAL_DATE_AT < DIAL_WEATHER_AT, "the tiers are out of order")
        assertTrue(DIAL_WEATHER_AT < DIAL_RANGE_AT, "the tiers are out of order")
    }
}
