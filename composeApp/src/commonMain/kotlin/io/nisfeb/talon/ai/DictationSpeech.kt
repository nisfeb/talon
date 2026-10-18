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

    /**
     * What waits, asked aloud, ending in what to say back. One answer
     * approves all of [waiting], so with more than one each is read out:
     * only the first message was, and "send" sent the rest unheard.
     */
    fun prompt(reply: String, sending: Boolean, waiting: List<CardProposal>): String {
        val end = if (sending) "Say send, or cancel." else "Say yes, or cancel."
        fun said(p: CardProposal) = if (p.kind == "message" && p.to != null && p.text != null) {
            "Send to ${if (p.to == "You") "you" else p.to}: ${plain(p.text).trimEnd('.', '!', '?')}"
        } else {
            plain(p.title).trimEnd('.', '!', '?')
        }
        val one = waiting.singleOrNull()
        return when {
            waiting.size > 1 -> "${waiting.size} things wait. " + waiting.joinToString(" ") { said(it) + "." } + " $end"
            sending && one?.kind == "message" && one.to != null && one.text != null -> "${said(one)}? $end"
            else -> "${spoken(reply)} $end"
        }
    }

    /**
     * How long saying [text] may take before the voice is stopped: about
     * nine characters a second at Archie's rate, and ten seconds to start.
     * A flat minute cut a long paragraph off mid-word, and the next one or
     * the mic came in over it.
     */
    fun sayBudgetMs(text: String): Long = 10_000L + (text.length * 100L / ArchieVoice.RATE).toLong()

    /** [md] as words: markdown marks, link targets, list bullets and @p sigils gone, and times said as times. */
    fun plain(md: String): String = times(dates(md))
        .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?i)\b(?:https?://|urb://|talon://|www\.)\S*[^\s.,;:!?)]"""), "")
        .replace(Regex("""(?m)^\s*(?:[-*+]|\d+[.)]|#{1,6})\s+"""), "")
        .replace(Regex("""[*_`]+"""), "")
        .replace(Regex("""(?<![\w])~(?=[a-z])"""), "")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""\s+([.,;:!?])"""), "$1")
        .trim()

    private val ISO_DAY = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    /**
     * A tool's dates as they are said: "2026-10-20" is "Tuesday, October
     * 20". The confirmations read them digit by digit. Not a date, left.
     */
    fun dates(text: String): String = ISO_DAY.replace(text) { m ->
        val d = runCatching { kotlinx.datetime.LocalDate.parse(m.value) }.getOrNull() ?: return@replace m.value
        fun named(e: Enum<*>) = e.name.lowercase().replaceFirstChar { it.uppercase() }
        "${named(d.dayOfWeek)}, ${named(d.month)} ${d.day}"
    }

    // A twelve-hour time with its AM or PM, or a twenty-four-hour one
    // with two hour digits. A bare "3:16" may be a verse or a score.
    private val CLOCK = Regex("""\b(?:(\d{1,2}):([0-5]\d)\s?([AaPp])(?:\.[Mm]\.|[Mm])|([01]\d|2[0-3]):([0-5]\d))(?![\w:])""")
    // A span between times, by a dash, never a date's hyphens.
    private val SPAN = Regex("""(\d|[AaPp][Mm])(?:\s*[\u2013\u2014]\s*|\s+-\s+)(\d)""")

    /**
     * Clock times as a person says them: the engine read "4:45" as "four
     * colon forty-five" (sneagan, 2026-10-16). "4:45 PM" is "4 45 PM",
     * "6:05 AM" is "6 oh 5 AM", "1:00 PM" is "1 PM", noon and midnight by
     * name, a 24-hour "16:30" as "4 30 PM", and "4:45 – 7:15" is "4 45 to 7 15".
     */
    fun times(text: String): String = CLOCK.replace(SPAN.replace(text) { "${it.groupValues[1]} to ${it.groupValues[2]}" }) { m ->
        val g = m.groupValues
        val twelve = g[1].isNotEmpty()
        var h = (if (twelve) g[1] else g[4]).toInt()
        val min = (if (twelve) g[2] else g[5]).toInt()
        val pm = if (twelve) g[3].equals("p", ignoreCase = true) else h >= 12
        if (!twelve && h > 12) h -= 12
        if (min == 0 && (h == 12 || h == 0)) return@replace if (pm) "noon" else "midnight"
        if (h == 0) h = 12
        val minutes = when {
            min == 0 -> ""
            min < 10 -> " oh $min"
            else -> " $min"
        }
        "$h$minutes ${if (pm) "PM" else "AM"}"
    }
}
