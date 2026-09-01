package io.nisfeb.talon.ui

import io.nisfeb.talon.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
        val dir = downloadsDir()
        runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("couldn't create $dir")
            }
            val out = uniquify(File(dir, fileName))
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

    private fun fetch(url: String): Pair<ByteArray, String?> {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val ct = resp.header("Content-Type")
            val body = resp.body ?: throw IOException("empty body")
            return body.bytes() to ct
        }
    }

    /**
     * <downloads>/Talon/ — on Linux the real (possibly localized) XDG
     * download dir via `xdg-user-dir` (~/Téléchargements etc.), else
     * ~/Downloads (macOS) / %USERPROFILE%\Downloads (Windows).
     */
    private fun downloadsDir(): File {
        val home = System.getProperty("user.home")
            ?: error("user.home not set")
        val base = if ("linux" in System.getProperty("os.name").lowercase()) {
            xdgDownloadDir(home) ?: File(home, "Downloads")
        } else {
            File(home, "Downloads")
        }
        return File(base, "Talon")
    }

    private fun xdgDownloadDir(home: String): File? = runCatching {
        val p = ProcessBuilder("xdg-user-dir", "DOWNLOAD")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroy()
            return null
        }
        val out = p.inputStream.bufferedReader().readText().trim()
        // xdg-user-dir echoes $HOME when DOWNLOAD is unset — don't
        // create ~/Talon in that case.
        File(out).takeIf { out.isNotBlank() && out != home && it.isDirectory }
    }.getOrNull()

    /**
     * If `target` already exists, append " (1)", " (2)", ... before the
     * extension until we find a free name. Caps at 99 attempts and
     * gives up so a malicious server can't loop us.
     */
    private fun uniquify(target: File): File {
        if (!target.exists()) return target
        val name = target.nameWithoutExtension
        val ext = target.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        for (i in 1..99) {
            val candidate = File(target.parentFile, "$name ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return target  // fall back to overwrite — unlikely to hit
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
