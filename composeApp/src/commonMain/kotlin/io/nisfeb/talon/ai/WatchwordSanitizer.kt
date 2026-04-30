// Pure-string watchword helper consumed by SettingsSyncImpl. Lives
// in its own file (rather than commonMain Watchwords.kt) so it
// doesn't FQN-shadow production app/'s Watchwords.kt — that class
// stays Android-only by design (Embedder + EmbeddingIndexer + ML Kit).
package io.nisfeb.talon.ai

/**
 * Canonical form of a watchword term used as a %settings entry
 * key. Lowercase, non-alphanumerics collapsed to single underscores,
 * trimmed of leading/trailing underscores. Two terms with the same
 * sanitized form share an entry; user can have "kpi" and "K.P.I.!"
 * but not both, %settings-side.
 */
internal fun sanitizeTerm(term: String): String =
    term.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
