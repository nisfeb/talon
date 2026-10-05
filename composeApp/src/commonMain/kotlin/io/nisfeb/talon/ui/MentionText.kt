package io.nisfeb.talon.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.nisfeb.talon.urbit.mentionRanges

/**
 * The composer's text as it will read once sent: a `~ship` that goes as
 * a mention shows as the name this device gives it (nickname, mnemonym,
 * planet name or the @p, by the sender's own settings), styled as a sent
 * mention is. What is sent is unchanged: the box still holds the @p.
 * [name] is [ShipNames.resolve].
 */
fun mentionTransformation(name: (String) -> String): VisualTransformation = VisualTransformation { source ->
    val text = source.text
    val ranges = mentionRanges(text)
    if (ranges.isEmpty()) {
        return@VisualTransformation TransformedText(if (needsEmojiFontSpans) text.applyEmojiSpans() else source, OffsetMapping.Identity)
    }
    // Each mention as (where it is in the text, where its name is shown).
    val spans = mutableListOf<Pair<IntRange, IntRange>>()
    val shown = buildString {
        var at = 0
        for (r in ranges) {
            append(text, at, r.first)
            val label = name(text.substring(r.first, r.last + 1))
            val start = length
            append(label)
            spans += r to (start until length)
            at = r.last + 1
        }
        append(text, at, text.length)
    }
    val styled = AnnotatedString.Builder(if (needsEmojiFontSpans) shown.applyEmojiSpans() else AnnotatedString(shown)).apply {
        spans.forEach { (_, d) -> addStyle(io.nisfeb.talon.urbit.MENTION_SPAN, d.first, d.last + 1) }
    }.toAnnotatedString()
    TransformedText(styled, MentionOffsets(spans))
}

/**
 * Offsets between the text and what is shown. Inside a mention they run
 * in proportion, so the caret moves through it rather than sticking;
 * [keepMentionsWhole] then moves a caret that lands inside to an edge.
 */
private class MentionOffsets(private val spans: List<Pair<IntRange, IntRange>>) : OffsetMapping {
    private fun map(offset: Int, from: (Pair<IntRange, IntRange>) -> IntRange, to: (Pair<IntRange, IntRange>) -> IntRange): Int {
        var shift = 0
        for (span in spans) {
            val a = from(span)
            val b = to(span)
            val aEnd = a.last + 1
            val bEnd = b.last + 1
            if (offset < a.first) break
            if (offset <= aEnd) {
                val len = aEnd - a.first
                return if (len == 0) b.first else b.first + ((offset - a.first) * (bEnd - b.first) + len / 2) / len
            }
            shift = bEnd - aEnd
        }
        return offset + shift
    }
    override fun originalToTransformed(offset: Int) = map(offset, { it.first }, { it.second })
    override fun transformedToOriginal(offset: Int) = map(offset, { it.second }, { it.first })
}

/**
 * [next] with each mention kept whole: a deletion that reaches into one
 * takes all of it (one backspace after a name takes the name, not the
 * last letter of an @p it hides), and a caret that lands inside one goes
 * to the edge it was heading for.
 */
fun keepMentionsWhole(prev: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val was = prev.text
    val now = next.text
    if (now.length < was.length) {
        // One run taken out: where the two differ, and how much went.
        var a = 0
        while (a < now.length && was[a] == now[a]) a++
        val cut = was.length - now.length
        if (was.substring(a + cut) != now.substring(a)) return next
        var start = a
        var end = a + cut
        for (r in mentionRanges(was)) {
            val rEnd = r.last + 1
            if (start < rEnd && end > r.first && (start > r.first || end < rEnd)) {
                start = minOf(start, r.first)
                end = maxOf(end, rEnd)
            }
        }
        if (start == a && end == a + cut) return next
        val text = was.substring(0, start) + was.substring(end)
        return TextFieldValue(text, TextRange(start))
    }
    val sel = next.selection
    if (!sel.collapsed || now != was) return next
    val at = sel.start
    val r = mentionRanges(now).firstOrNull { at > it.first && at <= it.last } ?: return next
    val forward = at >= prev.selection.start
    return next.copy(selection = TextRange(if (forward) r.last + 1 else r.first))
}
