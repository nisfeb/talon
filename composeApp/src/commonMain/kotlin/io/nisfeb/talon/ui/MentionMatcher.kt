package io.nisfeb.talon.ui

/**
 * Patp-bounded `~ourpatp` substring detection.
 *
 * "Patp boundary" means the character following the patp is NOT a
 * letter or `-`. So `~mister-foo` matches `hi ~mister-foo!` but NOT
 * `~mister-foo-bar` — otherwise `~mister-botter` would match every
 * one of `~mister-botter-dozzod-anycpu`'s messages. The same rule
 * guards the left edge: a letter or `-` glued to the tilde makes the
 * whole thing one token (`x~zod`), not a mention.
 *
 * [patp] is passed WITHOUT its leading `~` — callers strip it (see
 * DmListScreen's `activeShip?.removePrefix("~")`); the matcher adds
 * the tilde itself so a bare `mister-foo` in prose never counts.
 */
object MentionMatcher {
    // Lived in the daily digest until the digest was removed. Nothing
    // about it was ever about digests: it is what the mentions tab
    // uses to decide whether somebody said your name.

    fun containsMention(haystack: String, patp: String): Boolean {
        if (patp.isEmpty() || haystack.isEmpty()) return false
        val needle = "~" + patp.lowercase()
        val h = haystack.lowercase()
        var i = 0
        while (true) {
            val found = h.indexOf(needle, startIndex = i)
            if (found < 0) return false
            val before = if (found == 0) ' ' else h[found - 1]
            val end = found + needle.length
            val after = if (end >= h.length) ' ' else h[end]
            if (!before.isLetter() && before != '-' && !after.isLetter() && after != '-') return true
            i = found + 1
        }
    }
}
