package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.ui.LocalImageDownloader
import io.nisfeb.talon.ui.SaveResult
import kotlinx.coroutines.launch

/**
 * Fullscreen image viewer with pinch-to-zoom + pan. Single image for
 * v1; swiping between images in a conversation can come later.
 */
@Composable
fun ImageViewerScreen(
    url: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(url) { mutableStateOf(1f) }
    var offsetX by remember(url) { mutableStateOf(0f) }
    var offsetY by remember(url) { mutableStateOf(0f) }

    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        if (scale > 1f) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            // Snap back to center once the user pinches below 1x.
            offsetX = 0f
            offsetY = 0f
        }
    }

    val downloader = LocalImageDownloader.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saving by remember(url) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.5f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .transformable(state = transform),
        )

        // Top action row: close on the left, download on the right.
        // The download button hides when the active downloader is
        // NoopImageDownloader so platforms without a save backend
        // wired don't surface a button that won't do anything. We
        // detect the noop by sniffing a probe save call's
        // SaveResult.Unsupported — but that requires an actual call,
        // so instead the renderer compares against the singleton.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
            if (downloader !== io.nisfeb.talon.ui.NoopImageDownloader) {
                IconButton(
                    onClick = {
                        if (saving) return@IconButton
                        saving = true
                        scope.launch {
                            val result = downloader.saveImage(url)
                            val msg = when (result) {
                                is SaveResult.Saved -> "Saved to ${result.location}"
                                is SaveResult.Failed -> result.message
                                SaveResult.Unsupported -> "Image save isn't supported here"
                            }
                            snackbarHost.showSnackbar(msg)
                            saving = false
                        }
                    },
                    enabled = !saving,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }
}
