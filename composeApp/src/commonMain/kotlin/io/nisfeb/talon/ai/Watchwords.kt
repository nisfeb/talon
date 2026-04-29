// Pure-string watchword helpers consumed by SettingsSyncImpl. The
// runtime Watchwords runner (Embedder + EmbeddingIndexer + ML Kit)
// stays in androidMain — commonMain only needs the sanitizer.
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
