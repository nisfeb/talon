package io.nisfeb.talon.orrery

/**
 * Two of orrery-utils' rules, ported from `common/analyze.py` so the
 * client folds twins the way the analyst does. Kept here rather than
 * invented: a title normalised one way on the ship and another way in
 * Talon is a twin waiting to happen.
 */

private val NOISE = Regex("^(?:reminder|invitation|updated invitation|fwd|fw|re|notification)\\s*:\\s*", RegexOption.IGNORE_CASE)
private val DATEISH = Regex(
    "\\b(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b|\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b|" +
        "\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?\\b|" +
        "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:,?\\s+\\d{4})?\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * A title with its noise stripped: prefixes like "Reminder:", dates,
 * times and weekdays, punctuation and case. "Reminder: Pottery @ Thu
 * May 14, 6:00pm" and "Pottery" normalise to the same key, while
 * "Robin- Pottery/Wheel" stays its own.
 */
fun normalizeTitle(text: String?): String {
    var t = NOISE.replace((text ?: "").trim(), "")
    t = NOISE.replace(t, "")
    t = DATEISH.replace(t, " ")
    t = Regex("[^a-z0-9/ ]+").replace(t.lowercase(), " ")
    return Regex("\\s+").replace(t, " ").trim()
}

private val ROLE_WORDS = setOf(
    "me", "i", "wife", "husband", "mom", "mum", "dad", "mother", "father", "son", "daughter",
    "brother", "sister", "boss", "friend", "partner", "mr", "mrs", "ms", "dr", "the",
)

private fun words(name: String?): List<String> =
    Regex("[^a-z0-9]+").split((name ?: "").lowercase()).filter { it.isNotEmpty() }

private fun personKey(name: String?): Set<String> = words(name).toSet()

/**
 * Every word of the shorter name is in the longer one, and a one-word
 * name is a first name rather than a role: "dana" and "dana quill" are
 * one person, "dana" and "daniel quill" are not, and "wife" names
 * nobody.
 */
fun samePerson(a: String?, b: String?): Boolean {
    val ka = personKey(a) - ROLE_WORDS
    val kb = personKey(b) - ROLE_WORDS
    if (ka.isEmpty() || kb.isEmpty()) return false
    val aIsShort = ka.size <= kb.size
    val short = if (aIsShort) ka else kb
    val long = if (aIsShort) kb else ka
    if (!long.containsAll(short)) return false
    if (short.size == 1) {
        val first = words(if (aIsShort) a else b).filterNot { it in ROLE_WORDS }
        val other = words(if (aIsShort) b else a).filterNot { it in ROLE_WORDS }
        return first.isNotEmpty() && other.isNotEmpty() && first[0] == other[0]
    }
    return true
}
