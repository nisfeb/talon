package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.nisfeb.talon.urbit.UrbHttp

/**
 * Full-screen in-app viewer for a `urb://` link (Android/iOS). A
 * webview loads the lattice reader URL on the viewer's ship with the
 * session cookie injected. The header shows the canonical address and
 * an "open in browser" escape hatch; the system back button and the
 * back arrow both dismiss.
 *
 * A full-screen Dialog, NOT a bottom sheet: a sheet's drag-to-dismiss
 * gesture swallows the webview's vertical scroll, so the page can't be
 * scrolled. The Dialog leaves all gestures to the web content.
 *
 * Desktop never shows this — it opens the system browser instead
 * (isUrbWebViewSupported = false).
 */
@Composable
fun UrbViewerSheet(
    urbUrl: String,
    shipUrl: String,
    cookie: String,
    onDismiss: () -> Unit,
) {
    val readerUrl = UrbHttp.readerUrl(shipUrl, urbUrl)
    val uriHandler = LocalUriHandler.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                    Text(
                        urbUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { runCatching { uriHandler.openUri(readerUrl) } }) {
                        Icon(Icons.Filled.Public, contentDescription = "Open in browser")
                    }
                }
                HorizontalDivider()
                UrbWebView(
                    url = readerUrl,
                    origin = shipUrl,
                    cookie = cookie,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}
