package io.nisfeb.talon.urbit

/**
 * What is wrong with [name] as a new group's or channel's short name, or
 * null where it will do. It is the last part of the id (`~host/name`,
 * `chat/~host/name`), which %groups and %channels take as an Urbit term:
 * lowercase letters, digits and hyphens, a letter first. It cannot change
 * once made. Empty is fine: one is picked at random, as Tlon does.
 */
fun shortNameProblem(name: String): String? = when {
    name.isEmpty() -> null
    name[0] !in 'a'..'z' -> "Start with a lowercase letter."
    name.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' } -> "Use only lowercase letters, digits and hyphens."
    else -> null
}

/** What was typed, nearer a short name: lowercase, a space as a hyphen. */
fun shortNameTyped(typed: String): String = typed.lowercase().replace(' ', '-')
