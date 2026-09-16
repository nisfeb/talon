package io.nisfeb.talon.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.nisfeb.talon.ui.DesktopUriHandler
import io.nisfeb.talon.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Which installer a desktop Talon can take. An AppImage swaps itself
 * for the new one and restarts; the packaged installs hand the file
 * to the system, which knows how to install a deb, mount a dmg, or
 * run an msi.
 */
enum class DesktopUpdateFlavor(val manifestKey: String) {
    AppImage("appimage"), Deb("deb"), Dmg("dmg"), Msi("msi");
}

/** How this Talon was installed, from the process's own environment. */
fun desktopUpdateFlavor(appImagePath: String?, osName: String): DesktopUpdateFlavor? {
    if (!appImagePath.isNullOrBlank()) return DesktopUpdateFlavor.AppImage
    val os = osName.lowercase()
    return when {
        "linux" in os -> DesktopUpdateFlavor.Deb
        "mac" in os || "darwin" in os -> DesktopUpdateFlavor.Dmg
        "windows" in os -> DesktopUpdateFlavor.Msi
        else -> null
    }
}

/**
 * Desktop [UpdateInstallerHook]: fetches the installer for the way
 * this Talon was installed, checks its hash, and applies it.
 *
 * A manifest from before desktop installers were listed, or an
 * install kind the manifest lacks, falls back to the releases page in
 * a browser, which is all desktop ever had until now.
 */
class DesktopUpdateInstaller(
    private val http: HttpClient,
    private val updatesDir: File,
    /** Shut the app down cleanly and exit; the new AppImage is
     *  already running by the time this is called. */
    private val quit: () -> Unit,
    private val appImagePath: String? = System.getenv("APPIMAGE"),
    private val osName: String = System.getProperty("os.name", ""),
    private val releasesPageUrl: String = "https://github.com/nisfeb/talon/releases/latest",
) : UpdateInstallerHook {

    private val flavor = desktopUpdateFlavor(appImagePath, osName)

    override val readyHint: String = when (flavor) {
        DesktopUpdateFlavor.AppImage -> "Verified — Talon will restart into it."
        else -> "Verified — opens the installer."
    }

    override suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val asset = flavor?.let { manifest.desktop[it.manifestKey] }
        if (asset == null) {
            runCatching { DesktopUriHandler.openUri(releasesPageUrl) }
                .onFailure { onFailure("Couldn't open browser. Visit $releasesPageUrl manually."); return }
            onFailure("Opened the releases page in your browser — download and replace your install.")
            return
        }
        updatesDir.mkdirs()
        val target = File(updatesDir, asset.url.substringAfterLast('/'))
        val part = File(updatesDir, target.name + ".part")
        runCatching {
            http.prepareGet(asset.url) {
                header("User-Agent", "Talon-UpdateInstaller")
                timeout { requestTimeoutMillis = 30L * 60 * 1000 }
            }.execute { resp ->
                if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
                val total = resp.contentLength() ?: -1L
                val channel = resp.bodyAsChannel()
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 99))
                    }
                }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got != asset.sha256) error("downloaded file failed SHA-256 check")
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            part.delete()
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "download failed: ${it.message}")
            onFailure("Couldn't download the update: ${it.message ?: it::class.simpleName}")
            return
        }
        onReady(target.absolutePath)
    }

    override fun install(apkPath: String) {
        val file = File(apkPath)
        if (flavor == DesktopUpdateFlavor.AppImage && appImagePath != null) {
            replaceAppImage(file, File(appImagePath))
        } else {
            // The system knows the file: a deb opens in the package
            // installer, a dmg mounts, an msi runs.
            DesktopUriHandler.openUri(file.toURI().toString())
        }
    }

    /**
     * Swap the running AppImage for [fresh] and start the new one.
     * Linux keeps the running file's inode alive, so the move is safe
     * under our own feet; a location we cannot write to (a system
     * dir) leaves the download where it is and shows it.
     */
    private fun replaceAppImage(fresh: File, current: File) {
        val ok = runCatching {
            fresh.setExecutable(true, false)
            Files.move(fresh.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            ProcessBuilder(current.absolutePath).start()
        }.onFailure { Log.w(TAG, "could not replace ${current.path}: ${it.message}") }.isSuccess
        if (ok) quit() else DesktopUriHandler.openUri(fresh.parentFile.toURI().toString())
    }

    private companion object {
        const val TAG = "DesktopUpdateInstaller"
    }
}
