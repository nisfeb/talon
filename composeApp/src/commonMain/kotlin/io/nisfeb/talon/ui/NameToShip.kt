package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.isValidPatp

/**
 * Turning whatever somebody typed into the ship they meant.
 *
 * A comet now goes by several names -- its @p, the two-word short name
 * every screen shows, and the twelve-word one its profile shows -- and
 * a person inviting one will type whichever they were given. Only the
 * @p used to be accepted, which meant the name Talon shows everywhere
 * was the one name the invite box refused.
 *
 * Three of the four resolve with no help. The short name cannot: it
 * keeps two words of twelve, so it has to be matched against ships
 * already known, and it is the one form that can name two of them.
 */
object NameToShip {

    sealed interface Result {
        /** Exactly one ship. Safe to act on. */
        data class One(val ship: String) : Result
        /** The short name fits more than one known ship; the person
         *  has to say which. Never guess: they are different people. */
        data class Several(val ships: List<String>) : Result
        /** Nothing recognisable, or a name for nobody we know. */
        data object None : Result
    }

    /**
     * [typed] is raw input. [known] is every ship worth matching a
     * short name or nickname against -- contacts, members, whoever the
     * caller can see. [nicknameOf] supplies the reader's own name for
     * a ship, so a pet name works too.
     */
    fun resolve(
        typed: String,
        known: Collection<String> = emptyList(),
        nicknameOf: (String) -> String? = { null },
    ): Result {
        val text = typed.trim()
        if (text.isEmpty()) return Result.None

        // A @p, with the sig optional because people paste both ways.
        // isValidPatp, not the shape regex: the regex passes ~wisdom,
        // which the ship then refuses along with whatever it was
        // attached to. Its own doc records that bug.
        val asPatp = if (text.startsWith("~")) text else "~$text"
        if (isValidPatp(asPatp)) return Result.One(asPatp)

        // Somebody known, by any name the reader has for them. This
        // comes BEFORE decoding on purpose: a four-bit checksum lets
        // one word in sixteen decode as a near-zero comet, so a contact
        // nicknamed "Alone" typed as "alone" would otherwise start a
        // conversation with nobody.
        val needle = text.lowercase()
        val hits = known.distinct().filter { ship ->
            shipHandle(ship).equals(needle, ignoreCase = true) ||
                shipHandleLong(ship).equals(needle, ignoreCase = true) ||
                nicknameOf(ship)?.equals(needle, ignoreCase = true) == true
        }
        when (hits.size) {
            1 -> return Result.One(hits.first())
            0 -> Unit
            else -> return Result.Several(hits)
        }

        // Last, a full word name for a comet nobody here has met. It
        // decodes on its own, checksum and all.
        Mnemonym.shipForNym(text)?.let { return Result.One(it) }
        return Result.None
    }

    /**
     * What to tell somebody whose input did not land, or null when it
     * did. Written for the person, not about the parser.
     *
     * [typed] is their raw input, so an empty box says nothing rather
     * than scolding somebody who has not typed yet.
     */
    fun hint(result: Result, typed: String): String? = when (result) {
        is Result.One -> null
        is Result.Several ->
            "That name fits ${result.ships.size} ships. Use the full name to pick one."
        Result.None -> if (typed.isBlank()) null else "No ship goes by that name."
    }
}
