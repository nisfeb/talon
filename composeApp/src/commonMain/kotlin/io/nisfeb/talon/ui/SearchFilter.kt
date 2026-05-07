package io.nisfeb.talon.ui

/**
 * Parsed shape of a search query string. The user types a free-form
 * line that may include zero or more operators; the parser separates
 * the operators from the keyword text (the "needle"). The DAO layer
 * uses the structured fields to build a conditional WHERE clause —
 * see `MessageDao.searchFiltered`.
 *
 * Supported operators (all optional, case-insensitive on the key):
 *   from:~ship-name      — match a specific author
 *   in:chat/foo/bar      — match a specific conversation whom
 *   since:1w | 3d | 2h   — only messages newer than the offset
 *   has:image            — only messages with at least one image media
 *   has:link             — only messages with at least one link media
 *
 * Anything that isn't a known operator falls back into [needle], so a
 * malformed `from:` doesn't silently swallow the user's keyword.
 *
 * Keep this in commonMain — the DAO query is platform-agnostic and the
 * parser is pure, easy to fuzz, and load-bearing for the search UX.
 */
data class SearchFilter(
    /** The free-text portion of the query — what gets LIKE'd against
     *  contentJson. Empty when the user typed only operators. */
    val needle: String,
    /** patp like "~mister-botter" — already includes the leading
     *  tilde. */
    val fromShip: String? = null,
    /** Conversation key like "chat/~ship/channel-id". Whatever the
     *  user typed verbatim; we don't validate against known whoms. */
    val inWhom: String? = null,
    /** Cutoff in ms-since-epoch. Messages with sentMs >= this are
     *  included; older are filtered out. Computed at parse time
     *  against [nowMs] so the value is stable for the query. */
    val sinceMs: Long? = null,
    val hasImage: Boolean = false,
    val hasLink: Boolean = false,
) {
    /** True when the user has typed nothing useful — neither an
     *  operator nor enough text to make the LIKE worthwhile. The
     *  search screen short-circuits to empty results in this case
     *  to avoid scanning the whole table on every keystroke. */
    val isTrivial: Boolean
        get() = needle.length < 2 && fromShip == null && inWhom == null &&
            sinceMs == null && !hasImage && !hasLink
}

/**
 * Parse a search query string into [SearchFilter]. Pure function,
 * tested in commonTest.
 *
 * @param query raw text from the search box.
 * @param nowMs current wallclock; takes a parameter rather than
 *   calling [System.currentTimeMillis] so tests can pin it.
 */
fun parseSearchFilter(query: String, nowMs: Long): SearchFilter {
    val keywordParts = mutableListOf<String>()
    var fromShip: String? = null
    var inWhom: String? = null
    var sinceMs: Long? = null
    var hasImage = false
    var hasLink = false

    // Split on whitespace but keep operator values intact. Quoted
    // values aren't supported yet — if we ever need `in:"chat/with
    // spaces"` we'll add a tokenizer. For now Tlon whoms don't have
    // spaces so the simple split is fine.
    for (token in query.trim().split(Regex("\\s+"))) {
        if (token.isEmpty()) continue
        val colonIdx = token.indexOf(':')
        if (colonIdx <= 0 || colonIdx == token.lastIndex) {
            // Not an operator — bare text or a malformed `from:` /
            // `since:` with empty value. Keep it as keyword text so
            // the user's input never silently disappears.
            keywordParts += token
            continue
        }
        val key = token.substring(0, colonIdx).lowercase()
        val value = token.substring(colonIdx + 1)
        when (key) {
            "from" -> fromShip = normalizeFromShip(value) ?: run {
                keywordParts += token
                null
            }
            "in" -> inWhom = value
            "since" -> sinceMs = parseSinceMs(value, nowMs) ?: run {
                keywordParts += token
                null
            }
            "has" -> when (value.lowercase()) {
                "image", "images", "img" -> hasImage = true
                "link", "links", "url" -> hasLink = true
                else -> keywordParts += token
            }
            else -> keywordParts += token
        }
    }

    return SearchFilter(
        needle = keywordParts.joinToString(" "),
        fromShip = fromShip,
        inWhom = inWhom,
        sinceMs = sinceMs,
        hasImage = hasImage,
        hasLink = hasLink,
    )
}

/** Coerce `from:` value into a leading-tilde patp shape. Accepts
 *  `~ship`, `ship`, or trims @ for tolerance. Returns null on garbage
 *  so the caller can keep it as keyword text. */
private fun normalizeFromShip(raw: String): String? {
    val trimmed = raw.trim().trimStart('@').lowercase()
    if (trimmed.isEmpty()) return null
    val patpBody = trimmed.trimStart('~')
    if (patpBody.isEmpty()) return null
    // Patp shape: lowercase letters + dashes; rough guard, server-
    // side validation is the actual filter when the query runs.
    if (!patpBody.all { it.isLetter() || it == '-' }) return null
    return "~$patpBody"
}

/**
 * Parse `Nu` durations: `m` (minute), `h` (hour), `d` (day), `w`
 * (week), `mo` (month, treated as 30 days). Number prefix can be
 * absent (treated as 1) or any positive integer. Returns the
 * absolute ms-since-epoch cutoff, or null on garbage.
 */
internal fun parseSinceMs(value: String, nowMs: Long): Long? {
    val v = value.trim().lowercase()
    if (v.isEmpty()) return null
    // Match "Nmo" first so `mo` doesn't get split into `m` + leftover.
    val re = Regex("^(\\d+)?(mo|m|h|d|w)$")
    val m = re.matchEntire(v) ?: return null
    val n = m.groupValues[1].ifEmpty { "1" }.toLongOrNull() ?: return null
    val unit = m.groupValues[2]
    val ms: Long = when (unit) {
        "m" -> n * 60_000L
        "h" -> n * 3_600_000L
        "d" -> n * 86_400_000L
        "w" -> n * 7L * 86_400_000L
        "mo" -> n * 30L * 86_400_000L
        else -> return null
    }
    return nowMs - ms
}
