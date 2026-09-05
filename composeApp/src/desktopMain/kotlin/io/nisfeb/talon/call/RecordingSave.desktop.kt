package io.nisfeb.talon.call

import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext

actual suspend fun saveWavFile(bytes: ByteArray, name: String): String? =
    withContext(ioDispatcher) {
        runCatching {
            val dir = java.io.File(System.getProperty("user.home"), "Downloads/Talon")
            dir.mkdirs()
            val safe = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                .joinToString("")
                .ifBlank { "recording" }
            val f = java.io.File(dir, "$safe.wav")
            f.writeBytes(bytes)
            f.absolutePath
        }.getOrNull()
    }
