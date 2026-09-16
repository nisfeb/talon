package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.nisfeb.talon.call.AndroidCallEngine
import io.nisfeb.talon.call.AndroidPeerLink
import io.nisfeb.talon.call.CallEngine
import io.nisfeb.talon.call.PeerLink
import io.nisfeb.talon.call.WebRtcFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * libwebrtc's own renderer, embedded through AndroidView.
 *
 * The renderer must be initialised against the same EGL context the
 * factory encodes with — a mismatched one shows black rather than
 * failing — hence [WebRtcFactory.eglBase].
 */
@Composable
actual fun VideoSurface(
    engine: CallEngine,
    local: Boolean,
    modifier: Modifier,
    onFrameAspect: ((Float) -> Unit)?,
) {
    val android = engine as? AndroidCallEngine ?: return
    val video by android.video.collectAsState()
    TrackRenderer(
        track = if (local) android.localVideoTrack else android.remoteVideoTrack,
        on = if (local) video.localOn else video.remoteOn,
        local = local,
        modifier = modifier,
        onFrameAspect = onFrameAspect,
    )
}

@Composable
actual fun VideoSurface(
    link: PeerLink,
    local: Boolean,
    modifier: Modifier,
    onFrameAspect: ((Float) -> Unit)?,
) {
    val p = link as? AndroidPeerLink ?: return
    val video by p.video.collectAsState()
    TrackRenderer(
        track = if (local) p.localVideoTrack else p.remoteVideoTrack,
        on = if (local) video.localOn else video.remoteOn,
        local = local,
        modifier = modifier,
        onFrameAspect = onFrameAspect,
    )
}

@Composable
private fun TrackRenderer(
    track: VideoTrack?,
    on: Boolean,
    local: Boolean,
    modifier: Modifier,
    onFrameAspect: ((Float) -> Unit)?,
) {
    if (track == null || !on) return
    // key(track): AndroidView's factory runs once per node, so when a
    // speaker republishes and we are handed a NEW track, the node kept
    // the old renderer with its sink already removed — the tile froze
    // on the last frame while their audio carried on. Keying rebuilds
    // the view for the new track. Desktop never had this because it
    // renders through DisposableEffect(track).
    androidx.compose.runtime.key(track) {
    val renderer = remember(track) { mutableRendererFor() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                val view = this
                init(
                    WebRtcFactory.eglBase.eglBaseContext,
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() = Unit
                        // The shape of the picture after the phone's
                        // rotation is applied; a portrait camera sends
                        // landscape frames turned 90 degrees.
                        override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                            val turned = rotation == 90 || rotation == 270
                            val aspect = if (turned) h.toFloat() / w else w.toFloat() / h
                            view.post { onFrameAspect?.invoke(aspect) }
                        }
                    },
                )
                // Fit, not fill: the whole picture, letterboxed if the
                // pane is the wrong shape. Fill showed a band across a
                // forehead whenever a portrait camera met a landscape
                // pane, which on a phone was every call.
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                // Our own camera is a mirror, the way every video app
                // and every actual mirror behaves; the far end is not.
                setMirror(local)
                renderer.value = this
                track.addSink(this)
            }
        },
    )
    DisposableEffect(track) {
        onDispose {
            renderer.value?.let {
                runCatching { track.removeSink(it) }
                runCatching { it.release() }
            }
            renderer.value = null
        }
    }
    }
}

/** Holds the view so the dispose hook can detach the sink; a plain
 *  local would be recreated on every recomposition. */
private fun mutableRendererFor() =
    androidx.compose.runtime.mutableStateOf<SurfaceViewRenderer?>(null)
