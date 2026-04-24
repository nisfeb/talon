package io.nisfeb.talon.ui

/**
 * Slash-command parsing. The composer hands raw text here; callers
 * dispatch by cmd name. Kept deliberately unopinionated — a slash
 * command is just `/name arg1 arg2 …` and the return is a plain pair
 * so handlers can unit-test without pulling in any UI code.
 *
 * Mirrors yap/ui/src/util/slash.ts.
 */
data class SlashCommand(val cmd: String, val args: List<String>)

/** Composer autocomplete signal: the "/" prefix is active and the
 *  cursor is still inside the command-name segment. */
data class SlashTrigger(val query: String)

/**
 * True iff the whole input starts with `/` and the cursor hasn't
 * crossed any whitespace yet. Once the user types a space (starting
 * args) the picker should close.
 */
fun detectSlashTrigger(text: String, cursor: Int): SlashTrigger? {
    if (cursor <= 0 || cursor > text.length) return null
    if (text.isEmpty() || text[0] != '/') return null
    val before = text.substring(0, cursor)
    if (before.any { it.isWhitespace() }) return null
    return SlashTrigger(before.substring(1))
}

fun parseSlash(text: String): SlashCommand? {
    val t = text.trimStart()
    if (!t.startsWith('/')) return null
    val body = t.substring(1).trim()
    if (body.isEmpty()) return null
    val parts = body.split(Regex("\\s+"))
    val cmd = parts[0].lowercase()
    if (cmd.isEmpty()) return null
    return SlashCommand(cmd, parts.drop(1))
}
