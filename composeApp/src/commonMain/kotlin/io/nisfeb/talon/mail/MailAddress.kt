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
fun isShip(s: String): Boolean = io.nisfeb.talon.urbit.isValidPatp(s)


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
