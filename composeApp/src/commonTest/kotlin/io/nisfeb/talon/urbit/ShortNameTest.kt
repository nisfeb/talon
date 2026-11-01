package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A short name for a new group or channel, the end of its id: what
 * %groups and %channels take as a term (Hoon's sym: a lowercase letter,
 * then letters, digits and hyphens). sneagan asked for it, 2026-11-01:
 * Talon gave every group a random one.
 */
class ShortNameTest {
    @Test
    fun `a term will do, and empty is a random one`() {
        for (ok in listOf("", "a", "book-club", "club2", "x-", "seed-swap-2026")) assertNull(shortNameProblem(ok), ok)
    }

    @Test
    fun `a name that is not a term says what to change`() {
        assertEquals("Start with a lowercase letter.", shortNameProblem("9lives"))
        assertEquals("Start with a lowercase letter.", shortNameProblem("-club"))
        for (bad in listOf("book club", "book_club", "café", "club!", "a.b")) {
            assertEquals("Use only lowercase letters, digits and hyphens.", shortNameProblem(bad), bad)
        }
    }

    @Test
    fun `what is typed is lowercased, a space a hyphen`() {
        assertEquals("seed-swap", shortNameTyped("Seed Swap"))
        assertEquals("book_club", shortNameTyped("Book_Club"), "the rest is left for the problem to name")
    }
}
