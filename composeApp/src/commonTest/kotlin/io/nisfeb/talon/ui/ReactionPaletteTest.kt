package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins reaction de-duplication: the "two hearts" report. A shortcode, a
 * bare glyph, and a variation-selector glyph for the same reaction must
 * share one canonical key so they group as one chip / one picker option.
 */
class ReactionPaletteTest {

    @Test
    fun `normalize unifies shortcode, bare glyph, and variation-selector glyph`() {
        val bare = "❤"          // ❤  (U+2764)
        val emoji = "❤️"   // ❤️ (U+2764 U+FE0F)
        val key = ReactionPalette.normalize(bare)
        assertEquals(key, ReactionPalette.normalize(emoji))
        assertEquals(key, ReactionPalette.normalize(":heart:"))
        assertTrue(
            key.none { it.code in 0xFE00..0xFE0F },
            "variation selector not stripped from key: $key",
        )
    }

    @Test
    fun `normalize unifies thumbsup variants including the legacy plus-one`() {
        val g = ReactionPalette.normalize("👍") // 👍
        assertEquals(g, ReactionPalette.normalize(":thumbsup:"))
        assertEquals(g, ReactionPalette.normalize(":+1:")) // legacy code still recognized
    }

    @Test
    fun `catalog has no two shortcodes mapping to the same glyph`() {
        val dupes = EmojiCatalog.entries.groupBy { it.glyph }
            .filterValues { it.size > 1 }
            .mapValues { (_, es) -> es.map { it.shortcode } }
        assertTrue(dupes.isEmpty(), "duplicate glyphs in catalog: $dupes")
    }
}
