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
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImageJPEGRepresentation
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
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
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
 * Each of these was a tap somebody lost. The keyboard is a first
 * responder and presenting while it dismisses is a race, so editing
 * ends first. UIKit drops a presentation asked for mid transition, so
 * a busy screen is tried again a little later ([presentStep]) rather
 * than given up on at once: the gallery "didn't consistently open".
 * And a presentation is counted as dropped only once it has had time
 * to show ([PRESENT_CONFIRM_MS]); counted after one turn of the main
 * queue, a picker that opened a moment later took the photo picked in
 * it nowhere: "selecting an image doesn't attach it".
 */
private fun present(picker: UIViewController, attempt: Int = 0, onDropped: () -> Unit) {
    if (attempt == 0) activeWindow()?.endEditing(true)
    dispatch_async(dispatch_get_main_queue()) {
        val root = topViewController()
        val busy = root == null || root.isBeingDismissed() || root.isBeingPresented()
        when (presentStep(busy, attempt)) {
            PresentStep.RETRY -> after(PRESENT_RETRY_MS) { present(picker, attempt + 1, onDropped) }
            PresentStep.GIVE_UP -> onDropped()
            PresentStep.PRESENT -> {
                var shown = false
                root!!.presentViewController(picker, animated = true, completion = { shown = true })
                after(PRESENT_CONFIRM_MS) {
                    if (!shown && picker.presentingViewController == null) onDropped()
                }
            }
        }
    }
}

/** [block] on the main queue after [ms]. */
private fun after(ms: Long, block: () -> Unit) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ms * 1_000_000L), dispatch_get_main_queue(), block)
}

private suspend fun pickPhoto(): PickedImage? = suspendCancellableCoroutine { cont ->
    val picker = UIImagePickerController()
    var done = false
    val delegate = PhotoPickerDelegate { result ->
        if (!done) { done = true; cont.resumeWith(result) }
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
    /** The photo, null when cancelled, or why it could not be read: said in the composer, not dropped. */
    private val onResult: (Result<PickedImage?>) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true, completion = null)
        activeDelegates.remove(this)
        if (image == null) {
            onResult(Result.failure(IllegalStateException("That photo could not be read.")))
            return
        }
        // Off the main queue: a full photo was turned into a PNG here,
        // seconds of work and hundreds of MB for a 48 MP one, and nothing
        // was attached when it failed. A JPEG of it, no larger than
        // MAX_IMAGE_SIDE, as the paste path makes.
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            val result = runCatching { jpegOf(image) ?: error("That photo could not be read.") }
            dispatch_async(dispatch_get_main_queue()) { onResult(result) }
        }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        activeDelegates.remove(this)
        onResult(Result.success(null))
    }
}

/**
 * [image] as a JPEG, scaled to fit [io.nisfeb.talon.ui.MAX_IMAGE_SIDE]
 * pixels on its longest side. Drawn through UIImage, not its CGImage,
 * so a photo keeps its orientation.
 */
@OptIn(ExperimentalForeignApi::class)
private fun jpegOf(image: UIImage): PickedImage? {
    val (pw, ph) = image.size.useContents { (width * image.scale).toInt() to (height * image.scale).toInt() }
    if (pw <= 0 || ph <= 0) return null
    val (w, h) = io.nisfeb.talon.ui.fitWithin(pw, ph)
    val drawn = if (w == pw && h == ph) image else {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w.toDouble(), h.toDouble()), false, 1.0)
        try {
            image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
            UIGraphicsGetImageFromCurrentImageContext()
        } finally {
            UIGraphicsEndImageContext()
        }
    } ?: return null
    val jpeg = UIImageJPEGRepresentation(drawn, 0.9) ?: return null
    return PickedImage(bytes = jpeg.toByteArray(), mimeType = "image/jpeg", displayName = "photo.jpg")
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
