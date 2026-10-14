package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog split into categories must stay lossless, and the picker's
 * section assembly ([emojiPickerSections]) is the pure logic behind the
 * sheet. Both run here so a botched category-boundary edit or a broken
 * frequent/search branch fails the build without a composition.
 */
class ReactionPickerSheetTest {

    @Test
    fun `categories cover every entry, once`() {
        val fromCategories = EmojiCatalog.categories.flatMap { it.second }
        // The split is lossless: entries is exactly the categories joined.
        assertEquals(EmojiCatalog.entries, fromCategories)
        // No category is empty (a boundary edit that ate a section).
        assertTrue(EmojiCatalog.categories.all { it.second.isNotEmpty() }, "an empty category")
        // No shortcode appears twice (a boundary edit that duplicated one).
        val codes = EmojiCatalog.entries.map { it.shortcode }
        assertEquals(codes.size, codes.toSet().size, "duplicate shortcode in the catalog")
    }

    @Test
    fun `blank query shows frequently-used first, then the categories`() {
        val sections = emojiPickerSections(frequent = listOf(":fire:"), query = "")
        assertEquals("Frequently used", sections.first().first)
        assertEquals(
            listOf("Frequently used") + EmojiCatalog.categories.map { it.first },
            sections.map { it.first },
        )
    }

    @Test
    fun `no frequent reactions means no frequently-used section`() {
        val sections = emojiPickerSections(frequent = emptyList(), query = "")
        assertEquals(EmojiCatalog.categories.map { it.first }, sections.map { it.first })
    }

    @Test
    fun `frequent de-dupes by canonical glyph`() {
        // ":heart:" and "❤️" are the same reaction; the row shows one.
        val sections = emojiPickerSections(frequent = listOf(":heart:", "❤️", ":fire:"), query = "")
        val frequent = sections.first { it.first == "Frequently used" }.second
        assertEquals(2, frequent.size, "the heart forms should collapse to one")
    }

    @Test
    fun `a query collapses to one results section`() {
        val sections = emojiPickerSections(frequent = listOf(":fire:"), query = "fire")
        assertEquals(1, sections.size)
        assertEquals("Results", sections.first().first)
        assertTrue(sections.first().second.any { it.glyph == "🔥" }, "fire not in results")
    }

    @Test
    fun `a query that matches nothing yields no sections`() {
        assertEquals(emptyList(), emojiPickerSections(frequent = listOf(":fire:"), query = "zzzznope"))
    }
}
