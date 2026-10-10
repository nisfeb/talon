package io.nisfeb.talon.comet

/**
 * The ship behind a name Galène hands out.
 *
 * %trunk (wire 10) puts a comet's full mnemonym in a ticket's subject
 * instead of its @p, so a listener's browser, which has no ship and no
 * word list, can show the name Talon shows. Galène takes the username
 * from that subject, so everything keyed on a ship (the roster, who
 * owns a stream) reads the name back through here.
 *
 * No checksum: the name comes out of a token the host signed, so it is
 * decoded, not doubted. A name a person typed goes through
 * Mnemonym.shipForNym, which checks one. Anything that isn't a full
 * nym (a planet's @p, a listener) comes back unchanged.
 */
fun shipOfGaleneName(name: String): String {
    if (!name.startsWith(".")) return name
    val words = name.trimStart('.').split('.')
    if (words.size > 12) return name
    // Leading zero words were dropped from the name. Put them back.
    val indices = List(12 - words.size) { 0 } + words.map { wordIndex[it] ?: return name }
    // Twelve 11-bit words: the 128-bit value, then four checksum bits.
    val bytes = ByteArray(16)
    var acc = 0
    var bits = 0
    var n = 0
    for (i in indices) {
        acc = (acc shl 11) or i
        bits += 11
        while (bits >= 8 && n < 16) {
            bits -= 8
            bytes[n++] = (acc ushr bits).toByte()
            acc = acc and ((1 shl bits) - 1)
        }
    }
    return cometPatp(bytes)
}

/** The @p whose sixteen syllables spell [bytes], most significant first. */
fun cometPatp(bytes: ByteArray): String {
    if (bytes.size != 16) return ""
    val syllables = (0 until 16).map {
        if (it % 2 == 0) PATP_PREFIXES[bytes[it].toInt() and 0xff]
        else PATP_SUFFIXES[bytes[it].toInt() and 0xff]
    }
    val pairs = (0 until 16 step 2).map { syllables[it] + syllables[it + 1] }
    return "~" + pairs.take(4).joinToString("-") + "--" + pairs.drop(4).joinToString("-")
}

private val wordIndex: Map<String, Int> =
    MNEMONYM_WORDS.withIndex().associate { (i, w) -> w to i }

/**
 * A guest seat (trunk wire 16): someone with no ship, in from an invite
 * link, whom Galène names `guest-` and some hex digits (24 since trunk
 * desk revision 35, 12 before). Never an @p (an @p
 * starts with ~, a comet's Galène name with a dot), so nothing keyed on a
 * ship (a DM, a mute kept on the host) applies to one. Trunk's own pages
 * test the same prefix.
 */
fun isGuestName(name: String): Boolean = name.startsWith("guest-")
