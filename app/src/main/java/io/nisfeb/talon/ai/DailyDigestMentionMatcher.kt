package io.nisfeb.talon.ai

/**
 * Patp-bounded `~ourpatp` substring detection. See spec §Generation/Mentions.
 *
 * "Patp boundary" means the character following the patp is NOT a
 * letter or `-`. So `~mister-foo` matches `hi ~mister-foo!` but NOT
 * `~mister-foo-bar` — otherwise `~mister-botter` would match every
 * one of `~mister-botter-dozzod-nisfeb`'s messages.
 */
object DailyDigestMentionMatcher {

    fun containsMention(haystack: String, patp: String): Boolean {
        if (patp.isEmpty() || haystack.isEmpty()) return false
        val needle = "~" + patp.lowercase()
        val h = haystack.lowercase()
        var i = 0
        while (true) {
            val found = h.indexOf(needle, startIndex = i)
            if (found < 0) return false
            val end = found + needle.length
            val after = if (end >= h.length) ' ' else h[end]
            if (!after.isLetter() && after != '-') return true
            i = found + 1
        }
    }
}
