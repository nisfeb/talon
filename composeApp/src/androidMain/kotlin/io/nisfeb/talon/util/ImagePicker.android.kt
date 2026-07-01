package io.nisfeb.talon.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@Composable
actual fun rememberImagePicker(): suspend () -> PickedImage? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableListOf<CompletableDeferred<PickedImage?>>() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val deferred = pending.removeFirstOrNull() ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            deferred.complete(null) // user cancelled — not an error
            return@rememberLauncherForActivityResult
        }
        // Read + HEIC->JPEG transcode off the main thread: a large Samsung
        // HEIC decode/compress on the UI thread can ANR, and reading a big
        // photo blocks it too. A read/decode failure now surfaces to the
        // caller (completeExceptionally) instead of being swallowed to null,
        // so "nothing happens" becomes a real error message.
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri) ?: "image/jpeg"
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("cannot read image bytes")
                    // A cloud/online photo (Samsung Gallery, Google Photos)
                    // that hasn't downloaded can hand back a valid-but-empty
                    // stream: readBytes() returns 0 bytes with no exception.
                    // Uploading that produces a hosted URL to a blank object,
                    // and the chat sends an empty image. Fail loudly instead.
                    if (bytes.isEmpty()) error(
                        "image came back empty — if it's an online/cloud photo, " +
                            "open it in your Gallery first so it downloads, then retry",
                    )
                    val (b, m, n) = transcodeHeicToJpeg(bytes, mime, name)
                    PickedImage(b, m, n)
                }
            }
            result.onSuccess { deferred.complete(it) }
                .onFailure { deferred.completeExceptionally(it) }
        }
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
    val scope = rememberCoroutineScope()
    val pending = remember { mutableListOf<CompletableDeferred<PickedImage?>>() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val deferred = pending.removeFirstOrNull() ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            deferred.complete(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("cannot read file bytes")
                    if (bytes.isEmpty()) error("file came back empty (0 bytes)")
                    PickedImage(bytes, mime, name)
                }
            }
            result.onSuccess { deferred.complete(it) }
                .onFailure { deferred.completeExceptionally(it) }
        }
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

/**
 * Samsung / iPhone cameras default to HEIC/HEIF, which many upload
 * targets and non-Android viewers can't render. Transcode to JPEG so
 * the image uploads and shows everywhere. ImageDecoder (API 28+)
 * decodes HEIF AND applies the EXIF orientation, so the result isn't
 * sideways. Non-HEIC input — or any decode failure — passes through
 * unchanged (we upload the original bytes, no worse than before).
 */
private fun transcodeHeicToJpeg(
    bytes: ByteArray,
    mime: String,
    name: String,
): Triple<ByteArray, String, String> {
    val ext = name.substringAfterLast('.', "").lowercase()
    val looksHeic = mime.equals("image/heic", ignoreCase = true) ||
        mime.equals("image/heif", ignoreCase = true) ||
        ext == "heic" || ext == "heif"
    if (!looksHeic || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return Triple(bytes, mime, name)
    }
    val bitmap = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            // Hardware bitmaps can't be read back / compressed; force software.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull() ?: return Triple(bytes, mime, name)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    val base = name.substringBeforeLast('.', name).ifEmpty { "image" }
    return Triple(out.toByteArray(), "image/jpeg", "$base.jpg")
}

actual fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    opts.outWidth to opts.outHeight
}.getOrNull()
