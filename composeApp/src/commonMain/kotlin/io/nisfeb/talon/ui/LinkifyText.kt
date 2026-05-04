package io.nisfeb.talon.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Wrap any URLs found in [text] as `LinkAnnotation.Url` spans so a
 * regular `Text(annotatedString)` opens them via `LocalUriHandler` —
 * Compose 1.7 wires the click for free on Android and Desktop alike.
 *
 * Used for short freeform fields (statuses, bios) where we don't run
 * the full Story parser; link recognition is best-effort, sentence-
 * ending punctuation (`.,;:!?`) is trimmed off the matched URL so
 * "check out https://example.com." doesn't include the period.
 */
fun linkifyStatus(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)

    val matches = URL_RE.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        for (m in matches) {
            val start = m.range.first
            val rawEnd = m.range.last + 1
            // Trim trailing punctuation likely belonging to the
            // sentence rather than the URL. Preserve trailing `)`/`]`
            // only if the URL contains a matching opening bracket
            // (a Wikipedia link like https://en.wikipedia.org/wiki/Foo_(bar)
            // legitimately ends in `)`).
            var end = rawEnd
            while (end > start) {
                val ch = text[end - 1]
                val keepBracket = (ch == ')' && text.substring(start, end).count { it == '(' } >
                    text.substring(start, end).count { it == ')' } - 1) ||
                    (ch == ']' && text.substring(start, end).count { it == '[' } >
                        text.substring(start, end).count { it == ']' } - 1)
                if (ch in TRAILING_PUNCT && !keepBracket) end -= 1 else break
            }

            if (cursor < start) append(text, cursor, start)

            val raw = text.substring(start, end)
            val href = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            withLink(
                LinkAnnotation.Url(
                    url = href,
                    styles = TextLinkStyles(style = LINK_SPAN),
                ),
            ) { append(raw) }

            cursor = end
        }
        if (cursor < text.length) append(text, cursor, text.length)
    }
}

private val URL_RE = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'`]+""")

private val TRAILING_PUNCT = setOf('.', ',', ';', ':', '!', '?', ')', ']')

// Same blue + underline that Story.kt uses for inline `<a>` links so
// linkified statuses match the chat-message link affordance.
private val LINK_SPAN = SpanStyle(
    color = Color(0xFF2962FF),
    textDecoration = TextDecoration.Underline,
)
