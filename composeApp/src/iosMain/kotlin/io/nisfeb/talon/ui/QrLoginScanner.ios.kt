package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.nisfeb.talon.login.TalonLoginUri
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
import platform.UIKit.UIViewController
import platform.UIKit.NSTextAlignmentCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.Foundation.NSSelectorFromString

/**
 * The camera on a login QR: a full-screen preview with a cancel
 * button, closed by the first QR it reads. AVFoundation reads the
 * code itself; no library.
 */
@Composable
actual fun rememberQrLoginScanLauncher(onResult: (TalonLoginUri.Payload?) -> Unit): (() -> Unit)? {
    val current = rememberUpdatedState(onResult)
    return remember { { QrScanPresenter.present { raw -> current.value(raw?.let(TalonLoginUri::decode)) } } }
}

@OptIn(ExperimentalForeignApi::class)
private object QrScanPresenter {
    fun present(onCode: (String?) -> Unit) {
        val go = {
            dispatch_async(dispatch_get_main_queue()) {
                var top = UIApplication.sharedApplication.keyWindow?.rootViewController
                while (top?.presentedViewController != null) top = top.presentedViewController
                val host = top
                if (host == null) onCode(null)
                else host.presentViewController(QrScanController(onCode), animated = true, completion = null)
            }
        }
        if (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized) go()
        else AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted -> if (granted) go() else onCode(null) }
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private class QrScanController(private val onCode: (String?) -> Unit) : UIViewController(nibName = null, bundle = null) {
    private val session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer? = null
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
        val hint = UILabel().apply {
            text = "Point the camera at a Talon login QR"
            textColor = UIColor.whiteColor
            textAlignment = NSTextAlignmentCenter
        }
        view.addSubview(hint)
        val cancel = UIButton.buttonWithType(UIButtonTypeSystem).apply {
            setTitle("Cancel", forState = UIControlStateNormal)
            setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
            addTarget(this@QrScanController, action = NSSelectorFromString("cancelTapped"), forControlEvents = UIControlEventTouchUpInside)
        }
        view.addSubview(cancel)
        view.bounds.useContents {
            hint.setFrame(CGRectMake(0.0, 60.0, size.width, 30.0))
            cancel.setFrame(CGRectMake(0.0, size.height - 90.0, size.width, 44.0))
        }
        dispatch_async(dispatch_get_global_queue(0, 0u)) { session.startRunning() }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.setFrame(view.bounds)
    }

    @ObjCAction
    fun cancelTapped() = finish(null)

    private fun finish(code: String?) {
        if (delivered) return
        delivered = true
        if (session.running) session.stopRunning()
        dismissViewControllerAnimated(true) { onCode(code) }
    }
}
