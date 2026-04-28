package io.nisfeb.talon.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class UpdateInstaller(private val context: Context) {

    /**
     * Download the APK to external-files/updates, verify its SHA-256,
     * call onReady with the absolute path. onProgress receives 0..99
     * (100 is reserved for the transition to Ready, signalled by the
     * onReady callback rather than a final progress tick).
     * onFailure receives a human message.
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val updatesDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val target = File(updatesDir, "talon-${manifest.versionName}.apk")
        if (target.exists()) target.delete()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(manifest.url))
            .setTitle("Talon ${manifest.versionName}")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
            .addRequestHeader("User-Agent", "Talon-UpdateInstaller")
        val downloadId = dm.enqueue(req)

        // Poll. DownloadManager's broadcast is racy in our use case
        // (we want fine-grained progress); polling at 250ms is fine
        // for a single foreground-bounded download.
        while (true) {
            val q = DownloadManager.Query().setFilterById(downloadId)
            val cursor: Cursor = dm.query(q) ?: run {
                onFailure("download manager returned null cursor")
                return
            }
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    onFailure("download id $downloadId not found")
                    return
                }
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = c.getInt(statusIdx)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val ok = try {
                            verifySha256(target, manifest.sha256)
                        } catch (e: java.io.IOException) {
                            target.delete()
                            onFailure("SHA-256 check failed: ${e.message ?: e::class.simpleName}")
                            return
                        }
                        if (!ok) {
                            target.delete()
                            onFailure("downloaded APK failed SHA-256 check")
                            return
                        }
                        onReady(target.absolutePath)
                        return
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val reason = c.getInt(reasonIdx)
                        onFailure("download failed (reason $reason)")
                        return
                    }
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_PENDING -> {
                        val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val total = c.getLong(totalIdx)
                        val soFar = c.getLong(soFarIdx)
                        val pct = if (total > 0) ((soFar * 100) / total).toInt() else 0
                        onProgress(pct.coerceIn(0, 99))
                    }
                }
            }
            delay(250)
        }
    }

    /** Hand the verified APK to PackageInstaller via FileProvider URI. */
    fun install(apkPath: String) {
        val file = File(apkPath)
        val authority = "${context.packageName}.updates.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateInstaller", "install intent failed", e)
        }
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        return got.equals(expected, ignoreCase = true)
    }
}
