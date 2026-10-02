package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.nisfeb.talon.util.toNSData
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * iOS backend for [ImageDownloader]: the image goes into Photos, as its
 * own bytes (a GIF stays one). There was none, so the viewer, which
 * hides a download it cannot do, showed no download on iOS at all.
 * Adding to Photos asks once for add-only access
 * (NSPhotoLibraryAddUsageDescription); it never reads the library.
 * Files (mail attachments) go to the Files app, Talon folder, where
 * [io.nisfeb.talon.call.saveFile] puts them; there was no Save for them
 * on iOS before.
 */
class IosImageDownloader(private val http: HttpClient) : ImageDownloader {
    override val canSaveFiles: Boolean get() = true

    override suspend fun saveBytes(fileName: String, bytes: ByteArray): SaveResult {
        val name = fileName.ifBlank { "attachment" }
        val ext = name.substringAfterLast('.', "").ifBlank { "bin" }
        val stem = name.substringBeforeLast('.', name)
        return io.nisfeb.talon.call.saveFile(bytes, stem, ext, mimeForName(name))
            ?.let { SaveResult.Saved(it) }
            ?: SaveResult.Failed("Couldn't save $name to the Files app.")
    }

    override suspend fun saveImage(url: String): SaveResult {
        val bytes = fetchImageBytes(http, url).getOrElse { return SaveResult.Failed(it.message ?: "Couldn't download the image.") }
        if (!mayAddToPhotos()) return SaveResult.Failed("Talon may not add to your photos. Allow it in Settings, Talon, Photos.")
        return if (addToPhotos(bytes)) SaveResult.Saved("Photos") else SaveResult.Failed("Couldn't add the image to Photos.")
    }

    private suspend fun mayAddToPhotos(): Boolean = suspendCancellableCoroutine { cont ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
            cont.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
        }
    }

    private suspend fun addToPhotos(bytes: ByteArray): Boolean = suspendCancellableCoroutine { cont ->
        val data = bytes.toNSData()
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            { PHAssetCreationRequest.creationRequestForAsset().addResourceWithType(PHAssetResourceTypePhoto, data, null) },
        ) { ok, _ -> cont.resume(ok) }
    }
}
