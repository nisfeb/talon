package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A poll's votes are reactions, and a reaction is stored normalized,
 * which drops the U+FE0F most keycaps carry. Matched against the raw
 * keycaps, no vote was ever counted and nobody's own vote was marked.
 */
class PollVotesTest {
    private fun stored(i: Int) = ReactionPalette.normalize(VOTE_EMOJIS[i])

    @Test
    fun `a stored vote names its option as the raw keycap does`() {
        for (i in VOTE_EMOJIS.indices) {
            assertEquals(i, voteIndex(stored(i)), "stored ${VOTE_EMOJIS[i]}")
            assertEquals(i, voteIndex(VOTE_EMOJIS[i]), "raw ${VOTE_EMOJIS[i]}")
        }
        assertEquals("1⃣", stored(0), "stored without its variation selector")
    }

    @Test
    fun `anything else is no vote`() {
        assertEquals(-1, voteIndex(null))
        assertEquals(-1, voteIndex("👍"))
        assertEquals(-1, voteIndex("1"))
        assertEquals(-1, voteIndex(""))
    }

    @Test
    fun `tallies count stored votes per option and nothing past the options`() {
        val reactions = listOf(stored(1), stored(1), VOTE_EMOJIS[0], "🔥", stored(2), stored(9))
        assertEquals(listOf(1, 2, 1), pollTallies(reactions, 3).toList())
        assertEquals(listOf(1, 2), pollTallies(reactions, 2).toList(), "a vote for a third option of two is not counted")
        assertEquals(listOf(0, 0), pollTallies(emptyList(), 2).toList())
        assertEquals(1, pollTallies(reactions, 10)[9], "the tenth, which has no selector to lose")
    }
}
