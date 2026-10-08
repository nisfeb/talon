package io.nisfeb.talon.ui

/**
 * Who reacted, as a reaction chip's hover tip says it: "You, Alice and
 * Bob", the owner first and named "You", at most [MAX_REACTOR_NAMES]
 * names, then "and N more". Users asked for the names on hover, like a
 * tooltip, where a right-click list was the only way to see them
 * (sneagan, 2026-10-07).
 */
fun reactorsLine(authors: List<String>, ourPatp: String?, nameOf: (String) -> String): String {
    val unique = authors.distinct()
    val ordered = unique.filter { it == ourPatp } + unique.filter { it != ourPatp }
    val names = ordered.map { if (it == ourPatp) "You" else nameOf(it) }
    val shown = names.take(MAX_REACTOR_NAMES)
    val more = names.size - shown.size
    return when {
        more > 0 -> shown.joinToString(", ") + " and $more more"
        shown.size <= 1 -> shown.firstOrNull().orEmpty()
        else -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
    }
}

/** How many names a reaction's tip lists before "and N more". */
const val MAX_REACTOR_NAMES = 10
