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
 * Write [content] to a uniquely-named file in the platform temp dir and
 * return its `file://` URI. Replaces `File.createTempFile(...).toURI()`.
 * ponytail: uniqueness rides on a wall-clock stamp; a collision would
 * just overwrite the prior temp file, which is harmless here (one-shot
 * ICS/handoff files that the OS handler reads immediately).
 */
fun createTempFileUri(prefix: String, suffix: String, content: String): String {
    val path = tempDirPath.toPath() / "$prefix${nowMs()}$suffix"
    FileSystem.SYSTEM.sink(path).buffer().use { it.writeUtf8(content) }
    return "file://$path"
}
