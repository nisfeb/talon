package io.nisfeb.talon.orrery

/**
 * orrery-utils' rule for when two names are one person, ported from
 * `common/reconcile.py` so the client folds twins the way the owner's
 * own passes do. Kept here rather than invented: a name read one way
 * on the ship and another way in Talon is a twin waiting to happen.
 */

private val ROLE_WORDS = setOf(
    "me", "i", "wife", "husband", "mom", "mum", "dad", "mother", "father", "son", "daughter",
    "brother", "sister", "boss", "friend", "partner", "mr", "mrs", "ms", "dr", "the",
)

private fun words(name: String?): List<String> =
    Regex("[^a-z0-9]+").split((name ?: "").lowercase()).filter { it.isNotEmpty() }

private fun personKey(name: String?): Set<String> = words(name).toSet()

/**
 * Every word of the shorter name is in the longer one, and a one-word
 * name is a first name rather than a role: "dana" and "dana quill" are
 * one person, "dana" and "daniel quill" are not, and "wife" names
 * nobody.
 */
fun samePerson(a: String?, b: String?): Boolean {
    val ka = personKey(a) - ROLE_WORDS
    val kb = personKey(b) - ROLE_WORDS
    if (ka.isEmpty() || kb.isEmpty()) return false
    val aIsShort = ka.size <= kb.size
    val short = if (aIsShort) ka else kb
    val long = if (aIsShort) kb else ka
    if (!long.containsAll(short)) return false
    if (short.size == 1) {
        val first = words(if (aIsShort) a else b).filterNot { it in ROLE_WORDS }
        val other = words(if (aIsShort) b else a).filterNot { it in ROLE_WORDS }
        return first.isNotEmpty() && other.isNotEmpty() && first[0] == other[0]
    }
    return true
}
