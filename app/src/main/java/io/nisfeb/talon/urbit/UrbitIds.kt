package io.nisfeb.talon.urbit

/**
 * Urbit @ud decimal helpers for wire-format IDs.
 *
 * `%channels` and other agents dejs numeric IDs through `slav %ud`,
 * which in turn uses `dem:ag` — that parser requires dot-grouped
 * decimals (every three digits from the right) for values ≥ 1000.
 * Our local DB stores raw (undotted) IDs; wire payloads must dot them.
 *
 * [dotAtom] is a no-op for:
 *  - strings of length ≤ 3
 *  - already-dotted strings
 *  - non-numeric strings (e.g. "~author/…")
 *
 * Inverse: plain `String.replace(".", "")`.
 */
internal fun dotAtom(decimal: String): String {
    if (decimal.length <= 3) return decimal
    if (!decimal.all { it.isDigit() }) return decimal
    val out = StringBuilder()
    var i = decimal.length
    while (i > 3) {
        out.insert(0, "." + decimal.substring(i - 3, i))
        i -= 3
    }
    out.insert(0, decimal.substring(0, i))
    return out.toString()
}

/** Strip dot grouping from an @ud. Safe on undotted inputs. */
internal fun undotAtom(decimal: String): String = decimal.replace(".", "")
