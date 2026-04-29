package io.nisfeb.talon.util

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred

@Composable
actual fun rememberImagePicker(): suspend () -> PickedImage? {
    val context = LocalContext.current
    val pending = remember { mutableListOf<CompletableDeferred<PickedImage?>>() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val deferred = pending.removeFirstOrNull() ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            deferred.complete(null)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("cannot read image bytes")
            PickedImage(bytes, mime, name)
        }.onSuccess { deferred.complete(it) }
            .onFailure { deferred.complete(null) }
    }
    return remember(launcher) {
        suspend {
            val deferred = CompletableDeferred<PickedImage?>()
            pending += deferred
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            deferred.await()
        }
    }
}

@Composable
actual fun rememberAnyFilePicker(): suspend () -> PickedImage? {
    val context = LocalContext.current
    val pending = remember { mutableListOf<CompletableDeferred<PickedImage?>>() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val deferred = pending.removeFirstOrNull() ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            deferred.complete(null)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("cannot read file bytes")
            PickedImage(bytes, mime, name)
        }.onSuccess { deferred.complete(it) }
            .onFailure { deferred.complete(null) }
    }
    return remember(launcher) {
        suspend {
            val deferred = CompletableDeferred<PickedImage?>()
            pending += deferred
            launcher.launch("*/*")
            deferred.await()
        }
    }
}

actual fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    opts.outWidth to opts.outHeight
}.getOrNull()
