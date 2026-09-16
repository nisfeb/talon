package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.NSTextAlignmentCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.Foundation.NSSelectorFromString

/**
 * The camera on a QR code: a full-screen preview with a cancel
 * button, closed by the first QR it reads. AVFoundation reads the
 * code itself; no library.
 */
@Composable
actual fun rememberQrScanLauncher(prompt: String, onResult: (String?) -> Unit): (() -> Unit)? {
    val current = rememberUpdatedState(onResult)
    val shown = rememberUpdatedState(prompt)
    return remember { { QrScanPresenter.present(shown.value) { raw -> current.value(raw) } } }
}

/** The key window's root, walking scenes — keyWindow is deprecated. */
@OptIn(ExperimentalForeignApi::class)
private fun qrTopController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
    val scene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?: scenes.firstOrNull()
    val windows = scene?.windows?.filterIsInstance<UIWindow>().orEmpty()
    val window = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
    var top = window?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

@OptIn(ExperimentalForeignApi::class)
private object QrScanPresenter {
    fun present(prompt: String, onCode: (String?) -> Unit) {
        val go = {
            dispatch_async(dispatch_get_main_queue()) {
                when (val host = qrTopController()) {
                    null -> onCode(null)
                    else -> host.presentViewController(QrScanController(prompt, onCode), animated = true, completion = null)
                }
            }
        }
        if (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized) go()
        // The access answer arrives on an AVFoundation background queue;
        // hop like the granted path does so the caller sees one thread.
        else AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            if (granted) go()
            else dispatch_async(dispatch_get_main_queue()) { onCode(null) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private class QrScanController(private val prompt: String, private val onCode: (String?) -> Unit) : UIViewController(nibName = null, bundle = null) {
    private val session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer? = null
    private var hint: UILabel? = null
    private var cancel: UIButton? = null
    private var delivered = false
    // Held here: the output keeps only a weak reference to its delegate.
    private val reader = object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
        override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
            val code = didOutputMetadataObjects.firstNotNullOfOrNull { (it as? AVMetadataMachineReadableCodeObject)?.stringValue } ?: return
            finish(code)
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        // Full screen: the default page sheet lets a swipe-down dismiss
        // without finish() ever running — the camera keeps running and
        // the caller waits on a code that can no longer arrive.
        modalPresentationStyle = UIModalPresentationFullScreen
        view.backgroundColor = UIColor.blackColor
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        if (input == null || !session.canAddInput(input)) { finish(null); return }
        session.addInput(input)
        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) { finish(null); return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(reader, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        val layer = AVCaptureVideoPreviewLayer(session = session)
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
        hint = UILabel().apply {
            text = prompt
            textColor = UIColor.whiteColor
            textAlignment = NSTextAlignmentCenter
        }.also(view::addSubview)
        cancel = UIButton.buttonWithType(UIButtonTypeSystem).apply {
            setTitle("Cancel", forState = UIControlStateNormal)
            setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
            addTarget(this@QrScanController, action = NSSelectorFromString("cancelTapped"), forControlEvents = UIControlEventTouchUpInside)
        }.also(view::addSubview)
        dispatch_async(dispatch_get_global_queue(0, 0u)) { session.startRunning() }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // Frames belong here, not viewDidLoad: a programmatically
        // created controller has zero bounds at load, which used to put
        // the Cancel button at a zero-sized frame off the layout.
        preview?.setFrame(view.bounds)
        view.bounds.useContents {
            hint?.setFrame(CGRectMake(0.0, 60.0, size.width, 30.0))
            cancel?.setFrame(CGRectMake(0.0, size.height - 90.0, size.width, 44.0))
        }
    }

    @ObjCAction
    fun cancelTapped() = finish(null)

    private fun finish(code: String?) {
        if (delivered) return
        delivered = true
        // stopRunning blocks; startRunning already runs on this queue.
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            if (session.running) session.stopRunning()
        }
        dismissViewControllerAnimated(true) { onCode(code) }
    }
}
