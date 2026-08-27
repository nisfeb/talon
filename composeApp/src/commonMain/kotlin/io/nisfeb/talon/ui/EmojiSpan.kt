package io.nisfeb.talon.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Mixed text+emoji rendering helpers. Compose Desktop's text shaper
 * on Linux falls back to monochrome glyphs (DejaVu Sans's emoji
 * outlines) before reaching the system NotoColorEmoji, even when
 * fontconfig says otherwise. The fix is to tag every emoji codepoint
 * range with [EmojiFontFamily] via [SpanStyle] — letters keep their
 * normal font, emojis pick up the bundled color font.
 *
 * Android's [EmojiFontFamily] resolves to [androidx.compose.ui.text.font.FontFamily.Default]
 * so the spans are effectively no-ops there — the platform's native
 * emoji fallback handles color rendering.
 *
 * Codepoint detection is intentionally permissive: we span every
 * codepoint that *might* be part of an emoji (pictographic Supplementary
 * planes, variation selectors, ZWJ, regional indicators for flags).
 * False positives just route a few extra characters to the emoji font;
 * since we don't strip those characters from layout, no behavioral
 * difference results.
 */

private fun isEmojiCodepoint(cp: Int): Boolean = when {
    // Supplementary pictographs — most modern emojis. This range
    // also subsumes regional indicators 0x1F1E6..0x1F1FF (flag
    // halves), so no separate branch for them.
    cp in 0x1F000..0x1FFFF -> true
    cp in 0x2600..0x27BF -> true         // Misc Symbols + Dingbats (☀️ ✂️ etc.)
    cp in 0x2300..0x23FF -> true         // Misc technical (⏰ ⌚ etc.)
    cp in 0x2B00..0x2BFF -> true         // Misc Symbols and Arrows (⭐ etc.)
    cp == 0x200D -> true                 // ZWJ — joiner inside compound emoji
    cp in 0xFE00..0xFE0F -> true         // Variation selectors (text vs emoji presentation)
    cp in 0x20E0..0x20FF -> true         // Combining marks for keycap-style emojis
    else -> false
}

/**
 * Wrap [this] string in an [AnnotatedString] where every emoji
 * codepoint range gets a [SpanStyle] pointing at [EmojiFontFamily].
 * Non-emoji text is unstyled — it inherits whatever fontFamily the
 * containing [androidx.compose.material3.Text] already provides.
 */
/**
 * Surrogate-aware codepoint read. Kotlin/Native has no public
 * `String.codePointAt` (the JVM leaned on java.lang.String's), so decode
 * the UTF-16 pair by hand: a high surrogate followed by a low surrogate
 * combines into a single supplementary codepoint, otherwise it's the
 * char's own code.
 */
internal fun CharSequence.codePointAtCommon(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}

fun String.applyEmojiSpans(): AnnotatedString {
    val text = this
    if (text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAtCommon(i)
            val len = if (cp >= 0x10000) 2 else 1  // surrogate-pair aware step
            if (isEmojiCodepoint(cp)) {
                addStyle(SpanStyle(fontFamily = EmojiFontFamily), i, i + len)
            }
            i += len
        }
    }
}

/**
 * Apply emoji span styling on top of an already-annotated string —
 * preserves the existing spans (mentions, links, code, etc.) and
 * just layers per-emoji-codepoint fontFamily overrides on top. Used
 * by StoryRenderer where the source AnnotatedString already encodes
 * structural markup.
 */
fun AnnotatedString.applyEmojiSpans(): AnnotatedString {
    val source = this
    if (source.isEmpty()) return source
    return buildAnnotatedString {
        append(source)
        var i = 0
        val text = source.text
        while (i < text.length) {
            val cp = text.codePointAtCommon(i)
            val len = if (cp >= 0x10000) 2 else 1
            if (isEmojiCodepoint(cp)) {
                addStyle(SpanStyle(fontFamily = EmojiFontFamily), i, i + len)
            }
            i += len
        }
    }
}

/**
 * VisualTransformation that re-renders a TextField's plain text with
 * emoji codepoints styled in [EmojiFontFamily]. Drop into the
 * composer's OutlinedTextField:
 *
 *     OutlinedTextField(
 *         value = draft,
 *         onValueChange = updateDraft,
 *         visualTransformation = EmojiVisualTransformation,
 *         ...
 *     )
 *
 * The underlying [androidx.compose.ui.text.input.TextFieldValue]
 * stays plain text; only the display layer changes. OffsetMapping is
 * identity since we add styling, not characters.
 */
val EmojiVisualTransformation: VisualTransformation
    get() = if (!needsEmojiFontSpans) {
        // Nothing to restyle — and an identity transformation is not
        // free on iOS, where it can cost the field its paste menu.
        VisualTransformation.None
    } else {
        VisualTransformation { source ->
            TransformedText(
                text = source.text.applyEmojiSpans(),
                offsetMapping = OffsetMapping.Identity,
            )
        }
    }
