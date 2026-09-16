package io.nisfeb.talon.update

/**
 * The local name a downloaded installer gets, taken from the manifest
 * URL's last segment. The manifest is trusted, but a name is a path
 * once it hits a filesystem, and the check costs one line: strip
 * parent-references and separators FIRST, then fall back when nothing
 * is left — the other order turns a bare `..` into the updates
 * directory itself (which the Android installer then deleted).
 */
internal fun sanitizedUpdateFileName(url: String, fallback: String): String =
    url.substringAfterLast('/')
        .replace("..", "")
        .replace('/', '_')
        .replace('\\', '_')
        .ifBlank { fallback }
