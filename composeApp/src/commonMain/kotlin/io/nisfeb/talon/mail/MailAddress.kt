package io.nisfeb.talon.mail

/**
 * Is this the name of a ship?
 *
 * A convenience and never the boundary: the nexus keeps its own
 * validation and will refuse a bad name whatever this says. What it
 * buys is a typo caught at the keystroke rather than surfacing as a
 * refusal from a route that had already answered.
 *
 * Structural rather than a dictionary of syllables, because a client
 * does not carry the syllable tables and a name shaped wrong is the
 * mistake people actually make.
 */
fun isShip(s: String): Boolean {
    if (!s.startsWith("~")) return false
    val body = s.substring(1)
    if (body.isEmpty()) return false
    val parts = body.split("-")
    // Galaxy and star render as one group, planet two, moon four, comet
    // eight. Nothing else names anything: six, ten and twelve are all
    // even and none of them is a ship.
    if (parts.size !in GROUP_COUNTS) return false
    if (parts.any { p -> p.isEmpty() || !p.all { it in 'a'..'z' } }) return false
    if (parts.any { it.length != 3 && it.length != 6 }) return false
    // A three-letter group is a galaxy, and a galaxy is the WHOLE name.
    // Without this, two groups of three passes: the count is valid and
    // each group is a legal length, but @p never renders such a name.
    return parts.size == 1 || parts.all { it.length == 6 }
}

private val GROUP_COUNTS = setOf(1, 2, 4, 8)

/**
 * Split a recipient field into ships, keeping what could not be read as
 * one so the composer can say which token is wrong rather than
 * silently dropping it.
 */
fun parseRecipients(raw: String): Pair<List<String>, List<String>> {
    val tokens = raw.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
    val good = mutableListOf<String>()
    val bad = mutableListOf<String>()
    for (t in tokens) {
        val ship = if (t.startsWith("~")) t else "~$t"
        if (isShip(ship)) { if (ship !in good) good += ship } else bad += t
    }
    return good to bad
}
