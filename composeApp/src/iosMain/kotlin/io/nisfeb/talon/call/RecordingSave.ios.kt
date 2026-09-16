package io.nisfeb.talon.call

import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// iOS calls are held; no recording backend.
actual suspend fun saveWavFile(bytes: ByteArray, name: String): String? = null

/** Into Documents/Talon, which the Files app shows under Talon. */
actual suspend fun saveFile(bytes: ByteArray, name: String, extension: String, mime: String): String? =
    withContext(ioDispatcher) {
        runCatching {
            val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
            val dir = "$docs/Talon".toPath()
            FileSystem.SYSTEM.createDirectories(dir)
            val safe = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("").ifBlank { "file" }
            FileSystem.SYSTEM.write(dir / "$safe.$extension") { write(bytes) }
            "the Files app, Talon folder"
        }.getOrNull()
    }
