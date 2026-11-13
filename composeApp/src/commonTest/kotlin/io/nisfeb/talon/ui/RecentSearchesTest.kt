package io.nisfeb.talon.ui

import io.nisfeb.talon.ui.screens.withRecentSearch
import kotlin.test.Test
import kotlin.test.assertEquals

/** Search keeps the last five searches run on this device, newest first. */
class RecentSearchesTest {
    @Test
    fun `a search goes first, trimmed`() {
        assertEquals(listOf("from:~nec lunch", "dinner"), withRecentSearch(listOf("dinner"), "  from:~nec lunch "))
    }

    @Test
    fun `a repeat moves up rather than showing twice`() {
        assertEquals(listOf("b", "a", "c").map { "$it$it" }, withRecentSearch(listOf("aa", "bb", "cc"), "bb"))
    }

    @Test
    fun `five at most, the oldest dropped`() {
        val five = listOf("e5", "e4", "e3", "e2", "e1")
        assertEquals(listOf("e6", "e5", "e4", "e3", "e2"), withRecentSearch(five, "e6"))
    }

    @Test
    fun `under two characters is no search`() {
        assertEquals(listOf("aa"), withRecentSearch(listOf("aa"), " b "))
        assertEquals(listOf("aa"), withRecentSearch(listOf("aa"), ""))
    }

    @Test
    fun `a pasted line break becomes a space`() {
        assertEquals(listOf("lunch plans"), withRecentSearch(emptyList(), "lunch\nplans"))
    }
}
