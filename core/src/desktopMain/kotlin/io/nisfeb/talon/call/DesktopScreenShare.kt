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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Screens, then windows, that this machine can share. On Wayland the
 * system's own dialog picks when the share starts, and libwebrtc lists
 * one untitled stand-in each for screens and windows: offered as they
 * were, they read "Untitled" and pointed at nothing. There, one source
 * stands for the dialog, so the share starts at once.
 */
internal fun desktopScreenSources(): List<ScreenSource> {
    if (isWayland) return listOf(ScreenSource(0, "", isWindow = false))
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
    if (isWayland) GLibLoop.ensureRunning()
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
    /** Told when a share ends itself: no frame came, or they stopped. */
    private val onEnded: () -> Unit,
    /** How long to wait for the first frame: the system's dialog waits on the owner. */
    private val firstFrameMs: Long = 60_000,
    /** How long without a frame is a share that ended (a capture sends steadily, even of a still screen). */
    private val frameGapMs: Long = 10_000,
) {
    private var opened: OpenedCapture? = null
    private val watchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var watch: kotlinx.coroutines.Job? = null
    @kotlin.concurrent.Volatile private var lastFrameMs = 0L
    private val frameSeen = dev.onvoid.webrtc.media.video.VideoTrackSink { lastFrameMs = System.currentTimeMillis() }

    /** The shared track while a share runs, for the self-preview. */
    var track: VideoTrack? = null
        private set

    val active: Boolean get() = opened != null

    /** Share [src] on [sender], replacing any share already running. */
    @Synchronized
    fun start(sender: RTCRtpSender, src: ScreenSource) {
        val next = capture.open(src)
        val nextTrack = runCatching { factory.createVideoTrack("talon-screen", next.source) }
            .onFailure { next.close() }
            .getOrThrow()
        runCatching { sender.replaceTrack(nextTrack) }.onFailure { next.close() }.getOrThrow()
        lastFrameMs = 0L
        nextTrack.addSink(frameSeen)
        val previous = opened
        opened = next
        track = nextTrack
        previous?.close()
    }

    /**
     * Share [src] on [sender], or stop with null, with the camera
     * stopped first ([stopCamera]): the two take turns on the sender.
     * False when the capture would not start; the camera's track is
     * then back on the sender.
     */
    fun set(sender: RTCRtpSender, src: ScreenSource?, camera: VideoTrack?, stopCamera: () -> Unit): Boolean {
        if (src == null) {
            stop(sender, camera)
            return true
        }
        return runCatching {
            runCatching { camera?.isEnabled = false }
            runCatching(stopCamera)
            start(sender, src)
            Log.i(TAG, "sharing ${if (src.isWindow) "window" else "screen"} ${src.title}")
            watchFrames(sender, camera)
            true
        }.getOrElse {
            Log.w(TAG, "could not share ${src.title}", it)
            stop(sender, camera)
            false
        }
    }

    /**
     * End a share that sends nothing: one whose dialog was cancelled or
     * never answered, or whose screen or window went away. It showed
     * "Stop sharing" over a blank picture for as long as the call lasted.
     */
    private fun watchFrames(sender: RTCRtpSender, camera: VideoTrack?) {
        watch?.cancel()
        val startedMs = System.currentTimeMillis()
        watch = watchScope.launch {
            while (active) {
                kotlinx.coroutines.delay(1_000)
                val now = System.currentTimeMillis()
                val last = lastFrameMs
                val ended = if (last == 0L) now - startedMs > firstFrameMs else now - last > frameGapMs
                if (ended && active) {
                    Log.w(TAG, if (last == 0L) "the share sent no frame; ending it" else "the share stopped sending; ending it")
                    stop(sender, camera)
                    onEnded()
                    return@launch
                }
            }
        }
    }

    /** Put [camera] (or nothing) back on [sender] and end the capture. */
    @Synchronized
    fun stop(sender: RTCRtpSender?, camera: VideoTrack?) {
        val current = opened ?: return
        runCatching { sender?.replaceTrack(camera) }
        release(current)
    }

    /** End the capture without touching a sender, for a link being closed. */
    @Synchronized
    fun release() {
        watchScope.cancel()
        opened?.let(::release)
    }

    private fun release(current: OpenedCapture) {
        runCatching { track?.removeSink(frameSeen) }
        opened = null
        track = null
        current.close()
    }
}

private const val TAG = "ScreenShare"
