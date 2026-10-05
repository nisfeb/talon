package io.nisfeb.talon.call

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSource
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer
import io.nisfeb.talon.util.Log

/**
 * Screens, then windows, that this machine can share. On Wayland the
 * system portal shows its own picker when capture starts, and its one
 * source stands for whatever is chosen there.
 */
internal fun desktopScreenSources(): List<ScreenSource> {
    fun list(open: () -> DesktopCapturer, isWindow: Boolean) = runCatching {
        val capturer = open()
        try {
            capturer.desktopSources.orEmpty().map { ScreenSource(it.id, it.title.orEmpty(), isWindow) }
        } finally {
            capturer.dispose()
        }
    }.onFailure { Log.w(TAG, "could not list ${if (isWindow) "windows" else "screens"}", it) }
        .getOrDefault(emptyList())
    return list(::ScreenCapturer, isWindow = false) + list(::WindowCapturer, isWindow = true)
}

/** A started capture: what a track is made from, and how to end it. */
class OpenedCapture(val source: VideoTrackSource, val close: () -> Unit)

/**
 * Opens a [ScreenSource] for sending: the real capture by default, a
 * fake in tests, since a CI runner has no screen to capture.
 */
fun interface ScreenCapture {
    fun open(source: ScreenSource): OpenedCapture
}

val DesktopScreenCapture = ScreenCapture { src ->
    val capture = VideoDesktopSource()
    capture.setSourceId(src.id, src.isWindow)
    // Shared screens are mostly text: keep the resolution, spend less on
    // motion. webrtc-java has no content hint to say so to the encoder.
    capture.setFrameRate(15)
    capture.setMaxFrameSize(1920, 1080)
    capture.start()
    OpenedCapture(capture) {
        runCatching { capture.stop() }
        runCatching { capture.dispose() }
    }
}

/**
 * The share on one video sender. It swaps a capture track onto the
 * sender in place of the camera's, and the camera's back when it stops,
 * so nothing is renegotiated: the far side just sees our video change.
 */
internal class ScreenShareSlot(
    private val factory: PeerConnectionFactory,
    private val capture: ScreenCapture,
) {
    private var opened: OpenedCapture? = null

    /** The shared track while a share runs, for the self-preview. */
    var track: VideoTrack? = null
        private set

    val active: Boolean get() = opened != null

    /** Share [src] on [sender], replacing any share already running. */
    fun start(sender: RTCRtpSender, src: ScreenSource) {
        val next = capture.open(src)
        val nextTrack = runCatching { factory.createVideoTrack("talon-screen", next.source) }
            .onFailure { next.close() }
            .getOrThrow()
        runCatching { sender.replaceTrack(nextTrack) }.onFailure { next.close() }.getOrThrow()
        val previous = opened
        opened = next
        track = nextTrack
        previous?.close()
    }

    /** Put [camera] (or nothing) back on [sender] and end the capture. */
    fun stop(sender: RTCRtpSender?, camera: VideoTrack?) {
        val current = opened ?: return
        runCatching { sender?.replaceTrack(camera) }
        release(current)
    }

    /** End the capture without touching a sender, for a link being closed. */
    fun release() {
        opened?.let(::release)
    }

    private fun release(current: OpenedCapture) {
        opened = null
        track = null
        current.close()
    }
}

private const val TAG = "ScreenShare"
