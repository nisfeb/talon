package io.nisfeb.talon.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.coroutines.resume

// Delegates are retained here for the lifetime of the presentation —
// UIKit holds picker.delegate weakly, so without a strong reference the
// delegate would be collected before the callback fires.
private val activeDelegates = mutableSetOf<NSObject>()

@Composable
actual fun rememberImagePicker(): suspend () -> PickedImage? = remember {
    { pickPhoto() }
}

@Composable
actual fun rememberAnyFilePicker(): suspend () -> PickedImage? = remember {
    { pickDocument() }
}

@OptIn(ExperimentalForeignApi::class)
actual fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? {
    val image = UIImage(data = bytes.toNSData()) ?: return null
    return image.size.useContents {
        val w = width.toInt()
        val h = height.toInt()
        if (w > 0 && h > 0) w to h else null
    }
}

private fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

private suspend fun pickPhoto(): PickedImage? = suspendCancellableCoroutine { cont ->
    val root = topViewController()
    if (root == null) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    val picker = UIImagePickerController()
    val delegate = PhotoPickerDelegate { result ->
        cont.resume(result)
    }
    activeDelegates.add(delegate)
    picker.sourceType =
        UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

private suspend fun pickDocument(): PickedImage? = suspendCancellableCoroutine { cont ->
    val root = topViewController()
    if (root == null) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    val delegate = DocumentPickerDelegate { result ->
        cont.resume(result)
    }
    activeDelegates.add(delegate)
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem))
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

private class PhotoPickerDelegate(
    private val onResult: (PickedImage?) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        val png = image?.let { UIImagePNGRepresentation(it) }
        val result = png?.let {
            PickedImage(bytes = it.toByteArray(), mimeType = "image/png", displayName = "photo.png")
        }
        picker.dismissViewControllerAnimated(true, completion = null)
        activeDelegates.remove(this)
        onResult(result)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        activeDelegates.remove(this)
        onResult(null)
    }
}

private class DocumentPickerDelegate(
    private val onResult: (PickedImage?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        val result = url?.let { u ->
            val scoped = u.startAccessingSecurityScopedResource()
            val data = NSData.dataWithContentsOfURL(u)
            if (scoped) u.stopAccessingSecurityScopedResource()
            data?.let {
                PickedImage(
                    bytes = it.toByteArray(),
                    mimeType = mimeFor(u.pathExtension),
                    displayName = u.lastPathComponent ?: "file",
                )
            }
        }
        controller.dismissViewControllerAnimated(true, completion = null)
        activeDelegates.remove(this)
        onResult(result)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        controller.dismissViewControllerAnimated(true, completion = null)
        activeDelegates.remove(this)
        onResult(null)
    }
}

/** Best-effort MIME from a file extension for the picked document; the
 *  upload path re-derives content type, so octet-stream is a safe floor. */
private fun mimeFor(ext: String?): String = when (ext?.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}
