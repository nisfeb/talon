package io.nisfeb.talon.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pin the per-codepoint emoji-span behavior that drives color emoji
 * rendering on Compose Desktop.
 *
 * Key invariants:
 *   - Pure-ASCII / pure-letter input gets no spans.
 *   - Pure emoji text gets a span on every codepoint.
 *   - Mixed text gets spans only on emoji code points; letters / digits
 *     / punctuation pass through unstyled.
 *   - Surrogate-pair emoji codepoints (>= U+10000) get a single
 *     2-character span — not two 1-character spans.
 *   - VisualTransformation hands back identity OffsetMapping so caret
 *     positions / selection / text replacement aren't disturbed.
 */
class EmojiSpanTest {

    @Test
    fun `applyEmojiSpans tags each emoji in a multi-emoji string`() {
        // 👍❤️🔥 — three glyphs but ❤️ is U+2764 (BMP, 1 char) +
        // U+FE0F variation selector (BMP, 1 char). 👍 + 🔥 are each
        // 2-char surrogate pairs. Total 6 chars, 4 codepoints.
        val text = "👍❤️🔥"
        val out = text.applyEmojiSpans()
        // 👍 (1 span over 2 chars) + ❤ (1 span over 1 char) +
        // FE0F variation selector (1 span over 1 char) +
        // 🔥 (1 span over 2 chars) = 4 spans
        assertEquals(4, out.spanStyles.size)
        for (span in out.spanStyles) {
            assertEquals(EmojiFontFamily, span.item.fontFamily)
        }
        // No gaps: combined span coverage equals full text
        val sorted = out.spanStyles.sortedBy { it.start }
        var cursor = 0
        for (span in sorted) {
            assertEquals(cursor, span.start)
            cursor = span.end
        }
        assertEquals(text.length, cursor)
    }

    @Test
    fun `applyEmojiSpans on AnnotatedString preserves existing spans`() {
        // Input already has a SpanStyle (e.g., a link / mention) —
        // the emoji-span pass shouldn't drop it.
        val input = buildAnnotatedString {
            append("look ")
            pushStyle(SpanStyle(fontFamily = EmojiFontFamily))  // pretend pre-existing
            append("here")
            pop()
            append(" 👍")  // " 👍"
        }
        val out = input.applyEmojiSpans()
        assertEquals(input.text, out.text)
        // Pre-existing span survives
        val preExisting = out.spanStyles.firstOrNull {
            it.start == 5 && it.end == 9 && it.item.fontFamily == EmojiFontFamily
        }
        assertTrue(preExisting != null, "pre-existing SpanStyle on 'here' must survive")
        // Plus a new span for 👍 at indices 10..12
        val emojiSpan = out.spanStyles.firstOrNull {
            it.start == 10 && it.end == 12
        }
        assertTrue(emojiSpan != null, "emoji span on the thumbs-up must be added")
    }

    @Test
    fun `EmojiVisualTransformation uses identity OffsetMapping`() {
        // Regression guard: if someone rewrites the transformation to
        // strip / insert characters, OffsetMapping would need to be
        // non-identity, which would silently break caret + selection.
        // We assert identity — any rewrite needs to update both.
        val source = AnnotatedString("hi 👍")
        val transformed = EmojiVisualTransformation.filter(source)
        assertSame(OffsetMapping.Identity, transformed.offsetMapping)
        // Text content is unchanged (only spans differ)
        assertEquals(source.text, transformed.text.text)
    }

    @Test
    fun `EmojiVisualTransformation styles emojis in transformed text`() {
        val source = AnnotatedString("hello 🎉")  // "hello 🎉"
        val transformed = EmojiVisualTransformation.filter(source)
        // 🎉 at 6..7 (surrogate pair)
        val emojiSpan = transformed.text.spanStyles.firstOrNull {
            it.start == 6 && it.end == 8
        }
        assertTrue(emojiSpan != null, "🎉 should be wrapped in an emoji span")
        assertEquals(EmojiFontFamily, emojiSpan!!.item.fontFamily)
    }

    @Test
    fun `applyEmojiSpans treats ZWJ and variation selectors as emoji`() {
        // U+200D (ZWJ) and U+FE0F (variation selector) live INSIDE
        // emoji ligatures (e.g. family emoji, gendered profession).
        // They must be tagged so the whole sequence routes to the
        // emoji font, not split mid-glyph.
        val text = "‍️"
        val out = text.applyEmojiSpans()
        assertEquals(2, out.spanStyles.size)
    }

    @Test
    fun `applyEmojiSpans on punctuation produces no spans`() {
        // Standard ASCII punctuation must NOT be tagged. Lowest range
        // covered by isEmojiCodepoint is 0x2300; everything below stays
        // unstyled.
        val out = "hello, world! (1+2=3)".applyEmojiSpans()
        assertEquals(0, out.spanStyles.size)
    }

    // The next four pin specific codepoints inside each minor emoji
    // range. The mutation tester surfaced them as gaps — without a
    // test that pins a codepoint per range, flipping the range's
    // `true` to `false` would never fail a test.

    @Test
    fun `applyEmojiSpans tags codepoints in the misc-technical range`() {
        // ⏰ U+23F0 (alarm clock) — falls inside 0x2300..0x23FF.
        val out = "⏰".applyEmojiSpans()
        assertEquals(1, out.spanStyles.size)
        assertEquals(0, out.spanStyles[0].start)
        assertEquals(1, out.spanStyles[0].end)
        assertEquals(EmojiFontFamily, out.spanStyles[0].item.fontFamily)
    }

    @Test
    fun `applyEmojiSpans tags codepoints in the misc-symbols-and-arrows range`() {
        // ⭐ U+2B50 (star) — falls inside 0x2B00..0x2BFF.
        val out = "⭐".applyEmojiSpans()
        assertEquals(1, out.spanStyles.size)
        assertEquals(EmojiFontFamily, out.spanStyles[0].item.fontFamily)
    }

    @Test
    fun `applyEmojiSpans tags combining-keycap codepoints`() {
        // U+20E3 (combining enclosing keycap) — used by keycap-style
        // emojis like 1️⃣. Inside 0x20E0..0x20FF.
        val out = "⃣".applyEmojiSpans()
        assertEquals(1, out.spanStyles.size)
        assertEquals(EmojiFontFamily, out.spanStyles[0].item.fontFamily)
    }
}
