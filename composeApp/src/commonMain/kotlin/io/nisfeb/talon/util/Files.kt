package io.nisfeb.talon.util

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Multiplatform file helpers replacing the `java.io.File` calls
 * commonMain reached for. Backed by okio's system FileSystem, which
 * every Talon target (JVM/Android/iOS) supports.
 */

/** Best-effort delete. No-op if the file is already gone. */
fun deleteFile(path: String) {
    runCatching { FileSystem.SYSTEM.delete(path.toPath(), mustExist = false) }
}

/** Read the whole file into memory. Throws if it doesn't exist. */
fun readFileBytes(path: String): ByteArray =
    FileSystem.SYSTEM.source(path.toPath()).buffer().use { it.readByteArray() }

/** The final path segment, e.g. "voice-123.m4a". */
fun fileName(path: String): String = path.toPath().name

/**
 * Where Talon keeps the files it makes to hand to another app (an
 * event's .ics), under its own cache and not the shared temp directory,
 * where other users could read them. The desktop link handler opens a
 * `file:` address only from here.
 */
fun openedFilesDir(): String = (cacheDirPath.toPath() / "opened").toString()

/** How long a handed-over file is kept: the other app reads it at once. */
private const val OPENED_FILE_KEEP_MS = 24 * 60 * 60 * 1000L

/**
 * Write [content] to a uniquely-named file in [openedFilesDir] and
 * return its `file://` URI, dropping the ones older than a day there.
 * ponytail: uniqueness rides on a wall-clock stamp; a collision would
 * just overwrite the prior file, which is harmless here (one-shot
 * ICS/handoff files that the OS handler reads immediately).
 */
fun createTempFileUri(prefix: String, suffix: String, content: String): String {
    val dir = openedFilesDir().toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    pruneOlderThan(dir, nowMs() - OPENED_FILE_KEEP_MS)
    val path = dir / "$prefix${nowMs()}$suffix"
    FileSystem.SYSTEM.sink(path).buffer().use { it.writeUtf8(content) }
    return "file://$path"
}

/** Delete the files in [dir] last changed before [cutoffMs]. */
internal fun pruneOlderThan(dir: okio.Path, cutoffMs: Long) {
    runCatching {
        FileSystem.SYSTEM.list(dir).forEach { f ->
            val changed = FileSystem.SYSTEM.metadataOrNull(f)?.lastModifiedAtMillis ?: return@forEach
            if (changed < cutoffMs) FileSystem.SYSTEM.delete(f, mustExist = false)
        }
    }
}
