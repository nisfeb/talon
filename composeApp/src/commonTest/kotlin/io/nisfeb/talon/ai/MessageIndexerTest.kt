package io.nisfeb.talon.ai

import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the smart-search index embeds again, and what a search shows of
 * what it found: an edit is embedded again, a deleted message is not
 * shown with its old text.
 */
class MessageIndexerTest {
    private fun msg(id: String = "1", text: String = "lunch at noon", title: String? = null, deleted: Boolean = false) =
        MessageEntity("~bus", id, "~nec", 1, """[{"inline":["$text"]}]""", "/chat", isDeleted = deleted, title = title)

    @Test
    fun `a message with no vector yet is embedded`() {
        assertTrue(needsEmbedding(msg(), had = null))
    }

    @Test
    fun `an unchanged message is not embedded again`() {
        val m = msg()
        assertFalse(needsEmbedding(m, had = embedHash(m)))
    }

    @Test
    fun `an edit to the text is embedded again`() {
        assertTrue(needsEmbedding(msg(text = "dinner at eight"), had = embedHash(msg())))
    }

    @Test
    fun `an edit to the title alone is embedded again`() {
        val before = msg(title = "Plans")
        val after = msg(title = "Old plans")
        assertNotEquals(embedHash(before), embedHash(after))
        assertTrue(needsEmbedding(after, had = embedHash(before)))
    }

    @Test
    fun `a deleted message is never embedded`() {
        assertFalse(needsEmbedding(msg(deleted = true), had = null))
        assertFalse(needsEmbedding(msg(deleted = true), had = 7L))
    }

    @Test
    fun `a search shows its hits in order and drops the deleted and the gone`() = runTest {
        val kept = mapOf(
            "1" to msg("1"),
            "2" to msg("2", deleted = true),
            "4" to msg("4", text = "lunch plans"),
        )
        val hits = listOf("4", "2", "3", "1").map { SemanticHit("~bus", it, 0.9f) }
        val shown = hydrateHits(hits) { whom, id -> kept[id]?.takeIf { it.whom == whom } }
        assertEquals(listOf("4", "1"), shown.map { it.id })
    }
}
