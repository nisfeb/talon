package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.UrbHttp

/**
 * In-app viewer for a `urb://` link (Android/iOS). A tall bottom sheet
 * hosting a webview that loads the lattice reader URL on the viewer's
 * ship, with the ship session cookie injected. The header shows the
 * canonical address and an "open in browser" escape hatch.
 *
 * Desktop never shows this — it opens the system browser instead
 * (isUrbWebViewSupported = false).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrbViewerSheet(
    urbUrl: String,
    shipUrl: String,
    cookie: String,
    onDismiss: () -> Unit,
) {
    val readerUrl = UrbHttp.readerUrl(shipUrl, urbUrl)
    val uriHandler = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
        UrbWebView(
            url = readerUrl,
            origin = shipUrl,
            cookie = cookie,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
