package io.nisfeb.talon

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * External content handed to us via `ACTION_SEND`. We surface the two
 * shapes Tlon-style messaging cares about: plain text (URLs, snippets)
 * and single image attachments.
 */
sealed class ShareIntent {
    data class Text(val text: String) : ShareIntent()
    data class Image(val uri: Uri, val mimeType: String) : ShareIntent()
    data class File(val uri: Uri, val mimeType: String) : ShareIntent()

    companion object {
        fun from(intent: Intent?): ShareIntent? {
            if (intent == null) return null
            if (intent.action != Intent.ACTION_SEND) return null
            val type = intent.type
            return when {
                type != null && type.startsWith("text/") -> {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                    if (text != null) {
                        Text(text)
                    } else {
                        // File managers share .txt/.csv/.log as text/*
                        // with EXTRA_STREAM and no EXTRA_TEXT — treat
                        // those as a file upload, not a dead end.
                        val uri = extractStream(intent) ?: return null
                        File(uri, type)
                    }
                }
                type != null && type.startsWith("image/") -> {
                    val uri = extractStream(intent) ?: return null
                    Image(uri, type)
                }
                else -> {
                    // Generic file — any MIME we don't specifically handle
                    // falls through as File, uploaded then linked in the
                    // outgoing message as `[filename](url)`.
                    val uri = extractStream(intent) ?: return null
                    File(uri, type ?: "application/octet-stream")
                }
            }
        }

        private fun extractStream(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
    }
}
