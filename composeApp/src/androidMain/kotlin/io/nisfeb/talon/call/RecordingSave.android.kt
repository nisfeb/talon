package io.nisfeb.talon.call

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.nisfeb.talon.talonAppContext
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * Save a recorded party line's WAV. On API 29+ it goes through
 * MediaStore into Downloads/Talon so it shows up in Files; older
 * devices fall back to the app's external Music dir (no permission
 * needed). Best-effort — returns null on any failure.
 */
actual suspend fun saveWavFile(bytes: ByteArray, name: String): String? =
    withContext(ioDispatcher) {
        val ctx = talonAppContext ?: return@withContext null
        val safe = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("").ifBlank { "recording" }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$safe.wav")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Talon")
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                resolver.openOutputStream(uri).use { out ->
                    out?.write(bytes) ?: error("no output stream")
                }
                "Downloads/Talon/$safe.wav"
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: error("no external files dir")
                val f = java.io.File(dir, "$safe.wav")
                f.writeBytes(bytes)
                f.absolutePath
            }
        }.getOrNull()
    }
