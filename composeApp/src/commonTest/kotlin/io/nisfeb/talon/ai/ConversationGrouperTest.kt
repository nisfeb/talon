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

    @Test
    fun `centroidOf averages the vectors and skips empty or off-dim ones`() {
        assertEquals(
            listOf(1f, 2f),
            ConversationGrouper.centroidOf(
                listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 4f), floatArrayOf()),
            )!!.toList(),
        )
        // First vector sets the dim; a stray off-dim vector is dropped.
        assertEquals(
            listOf(2f, 0f),
            ConversationGrouper.centroidOf(
                listOf(floatArrayOf(2f, 0f), floatArrayOf(1f, 2f, 3f)),
            )!!.toList(),
        )
    }

    @Test
    fun `centroidOf is null when there is nothing usable`() {
        assertEquals(null, ConversationGrouper.centroidOf(emptyList()))
        assertEquals(null, ConversationGrouper.centroidOf(listOf(floatArrayOf(), floatArrayOf())))
    }
}
