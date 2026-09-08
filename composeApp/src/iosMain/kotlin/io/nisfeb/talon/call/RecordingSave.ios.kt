package io.nisfeb.talon.call

// iOS calls are held; no recording backend.
actual suspend fun saveWavFile(bytes: ByteArray, name: String): String? = null

actual suspend fun saveFile(bytes: ByteArray, name: String, extension: String, mime: String): String? = null
