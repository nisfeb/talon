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
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.endEditing
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
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

/** The foreground scene's key window, walking scenes — the app's
 *  keyWindow is deprecated (same walk as QrLoginScanner). */
private fun activeWindow(): UIWindow? {
    val scenes = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
    val scene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?: scenes.firstOrNull()
    val windows = scene?.windows?.filterIsInstance<UIWindow>().orEmpty()
    return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
}

private fun topViewController(): UIViewController? {
    var vc = activeWindow()?.rootViewController
    while (true) {
        // A controller on its way out cannot present anything: UIKit
        // drops the presentation without a word, and the picker never
        // opens. Stop at the last one that is actually staying.
        val next = vc?.presentedViewController ?: break
        if (next.isBeingDismissed()) break
        vc = next
    }
    return vc
}

/**
 * Show a picker, or say it never opened.
 *
 * Three things, each a tap somebody lost. The keyboard is a first
 * responder and presenting while it dismisses is a race, so editing
 * ends first. The presentation goes on the main queue, after that
 * dismissal has begun. And a presentation UIKit declines is checked
 * for rather than assumed, because the delegate would never fire and
 * the caller would wait for ever.
 */
private fun present(picker: UIViewController, onDropped: () -> Unit) {
    activeWindow()?.endEditing(true)
    dispatch_async(dispatch_get_main_queue()) {
        val root = topViewController()
        if (root == null || root.isBeingDismissed() || root.isBeingPresented()) {
            onDropped()
            return@dispatch_async
        }
        root.presentViewController(picker, animated = true, completion = null)
        dispatch_async(dispatch_get_main_queue()) {
            if (picker.presentingViewController == null) onDropped()
        }
    }
}

private suspend fun pickPhoto(): PickedImage? = suspendCancellableCoroutine { cont ->
    val picker = UIImagePickerController()
    var done = false
    val delegate = PhotoPickerDelegate { result ->
        if (!done) { done = true; cont.resume(result) }
    }
    activeDelegates.add(delegate)
    picker.sourceType =
        UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
    picker.delegate = delegate
    present(picker) {
        activeDelegates.remove(delegate)
        if (!done) { done = true; cont.resume(null) }
    }
}

private suspend fun pickDocument(): PickedImage? = suspendCancellableCoroutine { cont ->
    var done = false
    val delegate = DocumentPickerDelegate { result ->
        if (!done) { done = true; cont.resume(result) }
    }
    activeDelegates.add(delegate)
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem))
    picker.delegate = delegate
    present(picker) {
        activeDelegates.remove(delegate)
        if (!done) { done = true; cont.resume(null) }
    }
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
                    mimeType = io.nisfeb.talon.ui.mimeForName(u.lastPathComponent ?: ""),
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
