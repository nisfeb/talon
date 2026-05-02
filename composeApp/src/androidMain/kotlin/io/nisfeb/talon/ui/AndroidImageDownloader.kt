package io.nisfeb.talon.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Android backend for [ImageDownloader]. Downloads via the shared
 * OkHttp client and writes through [MediaStore] so the file shows up
 * in Photos / Files under "Pictures/Talon/" without having to scan
 * external storage afterwards.
 *
 * On API 29+ this uses the scoped-storage flow (RELATIVE_PATH +
 * IS_PENDING) — no runtime permission needed because the app is
 * writing into its own MediaStore-managed bucket. On API 26-28 we
 * fall back to a direct write under the legacy public Pictures
 * directory; that needs WRITE_EXTERNAL_STORAGE, declared in the
 * manifest with maxSdkVersion="28" so newer devices stay clean.
 */
class AndroidImageDownloader(
    private val context: Context,
    private val http: OkHttpClient,
) : ImageDownloader {

    override suspend fun saveImage(url: String): SaveResult = withContext(Dispatchers.IO) {
        val (bytes, contentType) = runCatching { fetch(url) }
            .getOrElse { e ->
                Log.w(TAG, "fetch failed for $url", e)
                return@withContext SaveResult.Failed(
                    "Couldn't download image: ${e.message ?: e::class.simpleName}",
                )
            }
        val fileName = deriveFileName(url, contentType)
        val mime = contentType?.substringBefore(';')?.trim() ?: "image/jpeg"

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(fileName, mime, bytes)
            } else {
                writeViaLegacyExternal(fileName, bytes)
            }
        }.fold(
            onSuccess = { SaveResult.Saved(it) },
            onFailure = { e ->
                Log.w(TAG, "write failed for $fileName", e)
                SaveResult.Failed("Couldn't save image: ${e.message ?: e::class.simpleName}")
            },
        )
    }

    private fun fetch(url: String): Pair<ByteArray, String?> {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val ct = resp.header("Content-Type")
            val body = resp.body ?: throw IOException("empty body")
            return body.bytes() to ct
        }
    }

    private fun writeViaMediaStore(fileName: String, mime: String, bytes: ByteArray): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Talon")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore.insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // Don't leave a half-written pending row in the store —
            // Photos shows them as broken thumbnails until they age out.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return "Pictures/Talon"
    }

    @Suppress("DEPRECATION")
    private fun writeViaLegacyExternal(fileName: String, bytes: ByteArray): String {
        // Pre-Q path: write directly under /sdcard/Pictures/Talon/ and
        // notify MediaScanner so it shows up in Photos. Needs the
        // maxSdkVersion=28 WRITE_EXTERNAL_STORAGE permission.
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES,
        )
        val dir = File(pictures, "Talon").apply { mkdirs() }
        val out = File(dir, fileName)
        out.writeBytes(bytes)
        // Trigger a media-scan so the file is indexed in Photos
        // without waiting for the next boot-time scan.
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(out.absolutePath), null, null,
            )
        }
        return "Pictures/Talon"
    }

    private fun deriveFileName(url: String, contentType: String?): String {
        val ext = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/heic" -> "heic"
            else -> null
        }
        // Last URL path segment makes the friendliest filename when it
        // has a real extension; otherwise we synthesize one. Strip any
        // query string so we don't end up with "image.jpg?token=…".
        val raw = url.substringBefore('?').substringAfterLast('/').trim()
        val baseHasExt = raw.contains('.') && !raw.endsWith('.')
        return when {
            raw.isNotBlank() && baseHasExt -> raw
            raw.isNotBlank() && ext != null -> "$raw.$ext"
            ext != null -> "talon-${System.currentTimeMillis()}.$ext"
            else -> "talon-${System.currentTimeMillis()}.jpg"
        }
    }

    companion object {
        private const val TAG = "AndroidImageDownloader"
    }
}
