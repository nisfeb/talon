package io.nisfeb.talon.ui

/**
 * `/poll <question> | <opt1> | <opt2> | ...`
 *
 * Encodes a poll into a chat message body with a machine-readable tag.
 * Votes are expressed as reactions using keycap-digit emojis — no new
 * agent state; reactions are already synced and counted.
 *
 * Mirrors yap/ui/src/util/poll.ts.
 */
data class Poll(val question: String, val options: List<String>)

/** Keycap-digit emojis used as vote reactions. Index matches option index. */
val VOTE_EMOJIS: List<String> = listOf(
    "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣",
    "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟",
)

const val MAX_POLL_OPTIONS: Int = 10

sealed interface PollParseResult {
    data class Ok(val poll: Poll) : PollParseResult
    data class Err(val error: String) : PollParseResult
}

fun parsePollInput(raw: String): PollParseResult {
    val parts = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 3) {
        return PollParseResult.Err("need at least a question and 2 options, e.g. \"q? | a | b\"")
    }
    val question = parts.first()
    val options = parts.drop(1)
    if (options.size > MAX_POLL_OPTIONS) {
        return PollParseResult.Err("max $MAX_POLL_OPTIONS options")
    }
    if (options.any { it.length > 120 }) {
        return PollParseResult.Err("each option must be under 120 chars")
    }
    if (question.length > 240) {
        return PollParseResult.Err("question must be under 240 chars")
    }
    return PollParseResult.Ok(Poll(question, options))
}

private fun esc(s: String): String = s.replace("|", "%7C").replace("]", "%5D")
private fun unesc(s: String): String = s.replace("%7C", "|").replace("%5D", "]")

fun encodePollTag(poll: Poll): String {
    val segs = (listOf(poll.question) + poll.options).joinToString("|") { esc(it) }
    return "[poll|$segs]"
}

val POLL_TAG_RE: Regex = Regex("\\[poll\\|([^\\]]+)\\]")

fun decodePollTag(text: String): Poll? {
    val m = POLL_TAG_RE.find(text) ?: return null
    val segs = m.groupValues[1].split("|").map(::unesc)
    if (segs.size < 3) return null
    val question = segs.first()
    val options = segs.drop(1)
    return Poll(question, options)
}
