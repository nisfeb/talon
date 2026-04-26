package io.nisfeb.talon.ai

/** Hard cap on hit rows kept per term. See spec §Decisions / §Performance. */
const val MAX_HITS_PER_TERM = 1000

/**
 * Word-boundary substring match. Case-insensitive. Punctuation-tolerant.
 *
 * "Mars" matches "Mars Society" / "(Mars)" / "Mars!" but not "Marshmallow".
 * "C++" matches "I love C++" because both sides are non-letters.
 * Multi-word phrases match the literal substring; internal whitespace is
 * matched as-is (so "Mars Society" does NOT match "Mars\nSociety").
 */
internal fun matchesWordBoundary(haystack: String, needle: String): Boolean {
    if (needle.isEmpty()) return false
    val h = haystack.lowercase()
    val n = needle.lowercase()
    var i = 0
    while (true) {
        val found = h.indexOf(n, startIndex = i)
        if (found < 0) return false
        val before = if (found == 0) ' ' else h[found - 1]
        val end = found + n.length
        val after = if (end >= h.length) ' ' else h[end]
        if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
        i = found + 1
    }
}

/**
 * Stable, lower-cased, alphanumerics-only key for a term — used as the
 * %settings entry key when sync is on. Two terms that produce the same
 * key collide on the ship side; the manage UI surfaces a warning.
 */
internal fun sanitizeTerm(term: String): String =
    term.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
