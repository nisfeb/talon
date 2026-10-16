package io.nisfeb.talon.ai

/**
 * What the card says aloud: the highlights, never the whole of a long
 * answer. sneagan, 2026-10-16: "I don't want it to read every detail of a
 * long day but can it speak highlights while showing me the details". The
 * answer opens with a sentence written to be heard ([DictationAgent.role]),
 * and that is what is spoken; the card shows the rest.
 */
object DictationSpeech {
    /** About two spoken sentences: longer and it reads the day out after all. */
    const val MAX_CHARS = 220

    /**
     * The part of [answer] to say: its first paragraph, as plain words
     * (no markdown, links or @p sigils), cut at a sentence end when it runs
     * long. "" when there is nothing to say.
     */
    fun spoken(answer: String): String {
        val first = answer.trim().split(Regex("""\n\s*\n""")).firstOrNull().orEmpty()
        val plain = plain(first)
        if (plain.length <= MAX_CHARS) return plain
        // Whole sentences while they fit; at least the first, cut if it must be.
        val sentences = Regex("""[^.!?]+[.!?]+(\s|$)""").findAll(plain).map { it.value.trim() }.toList()
        val out = StringBuilder()
        for (s in sentences) {
            if (out.isNotEmpty() && out.length + 1 + s.length > MAX_CHARS) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(s)
        }
        return out.toString().ifEmpty { plain.take(MAX_CHARS).substringBeforeLast(' ') + "…" }.take(MAX_CHARS + 40)
    }

    /** What waits, asked aloud, ending in what to say back. */
    fun prompt(reply: String, sending: Boolean, to: String?, text: String?): String = when {
        sending && to != null && text != null -> "Send to ${if (to == "You") "you" else to}: ${plain(text).trimEnd('.', '!', '?')}? Say send, or cancel."
        sending -> "${spoken(reply)} Say send, or cancel."
        else -> "${spoken(reply)} Say yes, or cancel."
    }

    /** [md] as words: markdown marks, link targets, list bullets and @p sigils gone. */
    fun plain(md: String): String = md
        .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?i)\b(?:https?://|urb://|talon://|www\.)\S*[^\s.,;:!?)]"""), "")
        .replace(Regex("""(?m)^\s*(?:[-*+]|\d+[.)]|#{1,6})\s+"""), "")
        .replace(Regex("""[*_`]+"""), "")
        .replace(Regex("""(?<![\w])~(?=[a-z])"""), "")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""\s+([.,;:!?])"""), "$1")
        .trim()
}
