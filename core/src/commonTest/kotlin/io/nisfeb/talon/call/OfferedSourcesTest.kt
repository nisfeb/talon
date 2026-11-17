package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the share menu offers: two unnamed screens both read "Whole screen" (a Mac user, 2026-11-17). */
class OfferedSourcesTest {
    private fun screen(id: Long, title: String = "") = ScreenSource(id, title, isWindow = false)
    private fun window(id: Long, title: String) = ScreenSource(id, title, isWindow = true)

    @Test
    fun `one screen keeps its name, and unnamed windows go`() {
        val all = listOf(screen(1), window(9, ""), window(8, "Notes"), window(7, "browser"))
        assertEquals(listOf(screen(1), window(7, "browser"), window(8, "Notes")), offeredSources(all, pointerId = 1))
    }

    @Test
    fun `with two screens the pointer's is This screen, first, and the other is Other screen`() {
        assertEquals(
            listOf(screen(2, "This screen"), screen(1, "Other screen")),
            offeredSources(listOf(screen(1), screen(2)), pointerId = 2),
        )
    }

    @Test
    fun `several others are numbered, and a screen's own name is kept beside it`() {
        assertEquals(
            listOf(screen(3, "This screen (Studio)"), screen(1, "Other screen 1"), screen(2, "Other screen 2 (TV)")),
            offeredSources(listOf(screen(1), screen(2, "TV"), screen(3, "Studio")), pointerId = 3),
        )
    }

    @Test
    fun `where the pointer's screen cannot be told, they are still told apart`() {
        assertEquals(
            listOf(screen(1, "Screen 1"), screen(2, "Screen 2")),
            offeredSources(listOf(screen(1), screen(2)), pointerId = null),
        )
        assertEquals(listOf("Screen 1", "Screen 2"), offeredSources(listOf(screen(1), screen(2)), pointerId = 77).map { it.title })
    }
}
