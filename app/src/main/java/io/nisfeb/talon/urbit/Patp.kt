package io.nisfeb.talon.urbit

/**
 * Urbit `@p` (ship) helpers. A patp is either a short 3-letter "galaxy"
 * (`~zod`), a 6-letter "star" (`~marzod`), a 6-letter "planet" (`~lodlep`),
 * a 12-char "moon" (`~ragryl-hardus`), or a longer "comet". We use a
 * permissive regex — three-or-six letter syllables joined by dashes —
 * which matches every real ship and rejects obvious garbage.
 */
// Try the 6-letter syllable first. If 3-letter is tried first, `~finned`
// greedily matches as `~fin` (3 letters) and aborts because the next
// char `n` is still a patp-char — so we'd never recognize real stars
// or planets like ~sarlev / ~finned-palmer.
val PATP_REGEX: Regex =
    Regex("~(?:[a-z]{6}|[a-z]{3})(?:-(?:[a-z]{6}|[a-z]{3}))*")

fun String.isPatp(): Boolean = PATP_REGEX.matches(this)
