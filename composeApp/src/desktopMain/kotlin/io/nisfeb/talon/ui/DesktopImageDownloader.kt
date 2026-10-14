package io.nisfeb.talon.ui

import io.nisfeb.talon.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Desktop backend for [ImageDownloader]. Drops files into a Talon/
 * subfolder of the user's downloads dir — same default Firefox /
 * Chrome use, no save dialog so the action stays one-click. Caller-facing location
 * string is the directory path so the snackbar can offer "Saved to
 * /home/.../Downloads/Talon".
 */
class DesktopImageDownloader(
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
        val dir = io.nisfeb.talon.util.talonDownloadsDir()
        runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("couldn't create $dir")
            }
            val out = io.nisfeb.talon.util.uniquifyDownload(File(dir, fileName))
            out.writeBytes(bytes)
            out.absolutePath
        }.fold(
            onSuccess = { SaveResult.Saved(it) },
            onFailure = { e ->
                Log.w(TAG, "write failed for $fileName", e)
                SaveResult.Failed("Couldn't save image: ${e.message ?: e::class.simpleName}")
            },
        )
    }

    override val canSaveFiles: Boolean get() = true

    /**
     * The same place, name and pending-flag dance as a saved recording:
     * one saveFile, not a second weaker copy that dropped the
     * Downloads/Talon folder and the IS_PENDING step and called
     * everything octet-stream.
     */
    override suspend fun saveBytes(fileName: String, bytes: ByteArray): SaveResult {
        val name = fileName.ifBlank { "attachment" }
        val ext = name.substringAfterLast('.', "").ifBlank { "bin" }
        val stem = name.substringBeforeLast('.', name)
        val where = io.nisfeb.talon.call.saveFile(bytes, stem, ext, mimeForName(name))
        return if (where != null) SaveResult.Saved(where)
        else SaveResult.Failed("Couldn't save $name.")
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

    private fun deriveFileName(url: String, contentType: String?): String {
        val ext = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/heic" -> "heic"
            else -> null
        }
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
        private const val TAG = "DesktopImageDownloader"
    }
}
