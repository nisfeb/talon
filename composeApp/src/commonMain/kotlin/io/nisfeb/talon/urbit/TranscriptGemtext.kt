package io.nisfeb.talon.urbit

/**
 * Turn a set of per-speaker transcript segments into one gemtext page
 * for Lattice.
 *
 * Segments arrive per speaker (one clip per SFU track); here they are
 * merged into a single timeline ordered by start time, with runs of
 * the same speaker collapsed under one byline so the page reads like a
 * transcript rather than a log. Times are relative to the start of the
 * recording (mm:ss), which is what a reader of a call wants — not
 * wall-clock.
 */
object TranscriptGemtext {

    /** One transcribed line: who said it, when (ms from the start of
     *  the recording), and the text. */
    data class Utterance(val speaker: String, val startMs: Long, val text: String)

    /**
     * @param title        page title (the line's title, or a default)
     * @param whenLabel    a human date for the sub-heading, already formatted
     * @param participants patps on the line, for the sub-heading
     * @param utterances   all speakers' segments, any order
     */
    fun build(
        title: String,
        whenLabel: String,
        participants: List<String>,
        utterances: List<Utterance>,
    ): String {
        val sb = StringBuilder()
        sb.append("# ").append(title.ifBlank { "Party line" }).append("\n\n")
        val who = participants.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val meta = listOf(whenLabel, who).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) sb.append(meta).append("\n\n")

        val ordered = utterances
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startMs }
        if (ordered.isEmpty()) {
            sb.append("_No speech was transcribed._\n")
            return sb.toString()
        }

        var lastSpeaker: String? = null
        for (u in ordered) {
            if (u.speaker != lastSpeaker) {
                sb.append("\n## ").append(u.speaker).append(" · ").append(clock(u.startMs)).append("\n")
                lastSpeaker = u.speaker
            }
            sb.append(u.text.trim()).append("\n")
        }
        return sb.toString()
    }

    /** ms from start -> "m:ss" (or "h:mm:ss" past an hour). */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val ss = if (s < 10) "0$s" else "$s"
        return if (h > 0) {
            val mm = if (m < 10) "0$m" else "$m"
            "$h:$mm:$ss"
        } else {
            "$m:$ss"
        }
    }
}
