package io.nisfeb.talon.util

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `<downloads>/Talon` — on Linux the real (possibly localized) XDG download
 * dir via `xdg-user-dir` (~/Téléchargements etc.), else ~/Downloads (macOS)
 * / %USERPROFILE%\Downloads (Windows). Shared by the image downloader and the
 * recording/attachment save so they cannot drift (a hardcoded ~/Downloads/Talon
 * in the recording path landed saves in a nonexistent dir on a localized Linux
 * desktop).
 */
internal fun talonDownloadsDir(): File {
    val home = System.getProperty("user.home") ?: error("user.home not set")
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
    // xdg-user-dir echoes $HOME when DOWNLOAD is unset — don't create ~/Talon.
    File(out).takeIf { out.isNotBlank() && out != home && it.isDirectory }
}.getOrNull()

/**
 * If [target] already exists, append " (1)", " (2)", … before the extension
 * until a free name is found. Caps at 99 so a flood of same-named files can't
 * loop; falls back to [target] (overwrite) only past that.
 */
internal fun uniquifyDownload(target: File): File {
    if (!target.exists()) return target
    val name = target.nameWithoutExtension
    val ext = target.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
    for (i in 1..99) {
        val candidate = File(target.parentFile, "$name ($i)$ext")
        if (!candidate.exists()) return candidate
    }
    return target
}
