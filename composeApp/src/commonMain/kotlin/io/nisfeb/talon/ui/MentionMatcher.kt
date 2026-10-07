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

    /**
     * Whether a post's story JSON names [patp]: a mention inline
     * (`"ship":"~zod"`) or typed in its words. Read raw, so no story is
     * parsed, and so a mention shown under a nickname still counts (the
     * words as shown said the nickname and missed it). A ship inside a
     * path (a cite's `/msg/~zod/…`, a nest's `chat/~zod/…`) is not one.
     */
    fun mentionsIn(contentJson: String, patp: String): Boolean = containsMention(contentJson, patp, notInPaths = true)

    fun containsMention(haystack: String, patp: String, notInPaths: Boolean = false): Boolean {
        if (patp.isEmpty() || haystack.isEmpty()) return false
        val needle = "~$patp"
        var i = 0
        while (true) {
            // Case aside without a lowercased copy of every message scanned.
            val found = haystack.indexOf(needle, startIndex = i, ignoreCase = true)
            if (found < 0) return false
            val before = if (found == 0) ' ' else haystack[found - 1]
            val end = found + needle.length
            val after = if (end >= haystack.length) ' ' else haystack[end]
            val inPath = notInPaths && (before == '/' || after == '/')
            if (!before.isLetter() && before != '-' && !after.isLetter() && after != '-' && !inPath) return true
            i = found + 1
        }
    }
}
