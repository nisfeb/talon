// TEMPORARY DUPLICATE of the pure-string-helpers section of
// app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt. The full
// Watchwords class (Embedder + EmbeddingIndexer + ML Kit
// integration) stays Android-only; commonMain only needs the
// sanitizeTerm helper that SettingsSyncImpl uses to namespace
// %settings entries by canonical-form term.
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
