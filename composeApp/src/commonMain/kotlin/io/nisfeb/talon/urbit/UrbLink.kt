package io.nisfeb.talon.urbit

/**
 * Detection of `urb://~ship/path` links in free message text.
 *
 * `urb://` is the address scheme used by Lattice (a peer-to-peer
 * gemtext browser for Urbit). Tlon's server-side linkifier only wraps
 * http(s) URLs into `link` blocks, so a `urb://` address typed into a
 * chat arrives as plain text — this object finds those runs so the
 * renderer can make them tappable and hand them off to Lattice.
 *
 * We deliberately don't validate the ship phonemes (Lattice's own
 * `UrbUrl.parse` is tolerant too); the `urb://` scheme prefix plus a
 * non-empty remainder is signal enough to linkify. Matching mirrors
 * Lattice's `UrbUrl.isUrb` (`startsWith("urb://")`).
 */
object UrbLink {
    const val SCHEME = "urb://"

    // urb:// followed by one-or-more non-whitespace, non-delimiter
    // chars. Excludes the handful of characters that commonly sit
    // *after* a URL in prose (quotes, angle brackets) so we don't
    // swallow them into the link.
    private val URB_RE = Regex("""urb://[^\s<>"'`]+""")

    // Trailing characters trimmed off a matched run — sentence
    // punctuation that almost never belongs to the URL itself. A
    // closing paren is trimmed only when the run has no opening paren
    // (so `urb://~ship/a(b)` keeps its balanced pair).
    private const val TRAILING_TRIM = ".,;:!?"

    /** True when [s] is exactly a urb:// URL (no surrounding text). */
    fun isUrbUrl(s: String): Boolean {
        val t = s.trim()
        if (!t.startsWith(SCHEME)) return false
        if (t.length <= SCHEME.length) return false
        return t.none { it.isWhitespace() }
    }

    /**
     * Find every urb:// run in [text]. Returns ranges (inclusive) into
     * the original string, left-to-right, non-overlapping. Trailing
     * sentence punctuation is excluded from each range.
     */
    fun findRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (m in URB_RE.findAll(text)) {
            var end = m.range.last
            while (end > m.range.first) {
                val c = text[end]
                val trimmable = c in TRAILING_TRIM ||
                    (c == ')' && '(' !in text.substring(m.range.first, end + 1))
                if (trimmable) end-- else break
            }
            // Must have at least one char after the scheme to be a link.
            if (end - m.range.first + 1 > SCHEME.length) {
                out.add(m.range.first..end)
            }
        }
        return out
    }

    /** Extract the urb:// substrings of [text], in order. */
    fun extract(text: String): List<String> =
        findRanges(text).map { text.substring(it.first, it.last + 1) }
}
