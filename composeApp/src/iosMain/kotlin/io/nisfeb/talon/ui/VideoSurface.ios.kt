package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.nisfeb.talon.call.CallEngine
import io.nisfeb.talon.call.IosCallEngine
import io.nisfeb.talon.call.IosPeerLink
import io.nisfeb.talon.call.PeerLink
import platform.UIKit.UIView

/**
 * The `RTCMTLVideoView` the Swift side already owns, embedded through
 * Compose's UIKit interop.
 *
 * The view is created and kept in Swift rather than here because the
 * track has to be attached to it on the main thread at the moment the
 * camera starts, which is Swift's side of the seam.
 */
@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)
@Composable
actual fun VideoSurface(engine: CallEngine, local: Boolean, modifier: Modifier) {
    val ios = engine as? IosCallEngine ?: return
    val video by ios.video.collectAsState()
    val on = if (local) video.localOn else video.remoteOn
    if (!on) return
    val view = (if (local) ios.localView() else ios.remoteView()) as? UIView ?: return
    UIKitView(factory = { view }, modifier = modifier)
}

/** A party-line tile's video: this speaker's camera (a down link) or
 *  ours (the up link). Same RTCMTLVideoView interop as the 1:1 overload,
 *  reading the views off [IosPeerLink]. */
@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)
@Composable
actual fun VideoSurface(
    link: PeerLink,
    local: Boolean,
    modifier: Modifier,
) {
    val ios = link as? IosPeerLink ?: return
    val video by ios.video.collectAsState()
    val on = if (local) video.localOn else video.remoteOn
    if (!on) return
    val view = (if (local) ios.localVideoView() else ios.remoteVideoView()) as? UIView ?: return
    UIKitView(factory = { view }, modifier = modifier)
}
