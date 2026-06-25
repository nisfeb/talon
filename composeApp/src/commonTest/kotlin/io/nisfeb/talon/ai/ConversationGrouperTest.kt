package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the topic-grouping decision + centroid update — the logic that
 *  bounds the assistant's context. */
class ConversationGrouperTest {

    @Test
    fun `same direction continues, orthogonal does not`() {
        assertTrue(ConversationGrouper.continues(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        // cosine 1/sqrt2 ~= 0.707, above the 0.45 default
        assertTrue(ConversationGrouper.continues(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f)))
        assertFalse(ConversationGrouper.continues(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }

    @Test
    fun `null, empty, and mismatched dims are treated as a new topic`() {
        assertFalse(ConversationGrouper.continues(null, floatArrayOf(1f, 0f)))
        assertFalse(ConversationGrouper.continues(floatArrayOf(1f, 0f), null))
        assertFalse(ConversationGrouper.continues(floatArrayOf(), floatArrayOf(1f)))
        assertFalse(ConversationGrouper.continues(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun `updateCentroid is a running mean`() {
        // centroid [2,4] summarising 1 vector, fold in [0,0] -> [1,2]
        assertEquals(
            listOf(1f, 2f),
            ConversationGrouper.updateCentroid(floatArrayOf(2f, 4f), count = 1, vec = floatArrayOf(0f, 0f)).toList(),
        )
    }

    @Test
    fun `updateCentroid ignores an empty incoming vector`() {
        assertEquals(
            listOf(2f, 4f),
            ConversationGrouper.updateCentroid(floatArrayOf(2f, 4f), count = 3, vec = floatArrayOf()).toList(),
        )
    }
}
