package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusRowsTest {

    private fun c(ship: String, status: String?, at: Long = 0L) =
        ContactEntity(ship = ship, nickname = null, bio = null, avatarUrl = null,
            status = status, statusUpdatedMs = at)

    private val us = "~wex"

    // Newest first, which is the order the feed arrives in.
    private val feed = listOf(
        c("~dalsyd", "shipping", 500),
        c("~ricsul-bilwyt", "at the desk", 400),
        c("~palfun-foslup", "away", 300),
        c("~sorreg-namtyv", null, 200),
        c(us, "mine", 600),
    )

    @Test
    fun `our own status is not in the list`() {
        // The feed includes us; the widget is about everyone else.
        val rows = statusRows(feed, emptyList(), 5, us)
        assertTrue(rows.none { it.first.ship == us })
    }

    @Test
    fun `without pins it is just the newest with something to say`() {
        val rows = statusRows(feed, emptyList(), 5, us)
        assertEquals(listOf("~dalsyd", "~ricsul-bilwyt", "~palfun-foslup"), rows.map { it.first.ship })
        assertTrue(rows.none { it.second }, "nothing is pinned")
    }

    @Test
    fun `a pinned person goes to the top`() {
        val rows = statusRows(feed, listOf("~palfun-foslup"), 5, us)
        assertEquals("~palfun-foslup", rows.first().first.ship)
        assertTrue(rows.first().second, "and is marked as pinned")
        assertEquals(1, rows.count { it.first.ship == "~palfun-foslup" }, "not listed twice")
    }

    @Test
    fun `pins keep the order they were pinned in`() {
        val rows = statusRows(feed, listOf("~palfun-foslup", "~dalsyd"), 5, us)
        assertEquals(listOf("~palfun-foslup", "~dalsyd"), rows.take(2).map { it.first.ship })
    }

    @Test
    fun `a pinned person with nothing to say still shows`() {
        // The silence is part of what somebody pinned them for.
        val rows = statusRows(feed, listOf("~sorreg-namtyv"), 5, us)
        assertEquals("~sorreg-namtyv", rows.first().first.ship)
    }

    @Test
    fun `somebody unpinned with nothing to say does not`() {
        val rows = statusRows(feed, emptyList(), 5, us)
        assertTrue(rows.none { it.first.ship == "~sorreg-namtyv" })
    }

    @Test
    fun `pins are never crowded out by the count`() {
        // Three pins and room for one still shows all three: a pin the
        // widget silently dropped would be worse than a longer list.
        val pins = listOf("~palfun-foslup", "~sorreg-namtyv", "~ricsul-bilwyt")
        val rows = statusRows(feed, pins, 1, us)
        assertEquals(pins, rows.map { it.first.ship })
        assertTrue(rows.all { it.second })
    }

    @Test
    fun `the count bounds everyone else`() {
        val rows = statusRows(feed, listOf("~palfun-foslup"), 2, us)
        assertEquals(2, rows.size)
        assertEquals("~palfun-foslup", rows[0].first.ship)
    }

    @Test
    fun `a pin for somebody not in the feed is skipped, not a blank row`() {
        val rows = statusRows(feed, listOf("~nobody-atall", "~dalsyd"), 5, us)
        assertTrue(rows.none { it.first.ship == "~nobody-atall" })
        assertEquals("~dalsyd", rows.first().first.ship)
    }

    @Test
    fun `an empty feed is an empty list, not a crash`() {
        assertTrue(statusRows(emptyList(), listOf("~dalsyd"), 5, us).isEmpty())
        assertTrue(statusRows(emptyList(), emptyList(), 5, us).isEmpty())
    }

    @Test
    fun `a blank status does not count as having said something`() {
        val blank = listOf(c("~dalsyd", "   "), c("~ricsul-bilwyt", "here"))
        assertEquals(listOf("~ricsul-bilwyt"), statusRows(blank, emptyList(), 5, us).map { it.first.ship })
    }
}
