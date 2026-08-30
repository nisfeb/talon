package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.nisfeb.talon.call.AndroidCallEngine
import io.nisfeb.talon.call.CallEngine
import io.nisfeb.talon.call.WebRtcFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * libwebrtc's own renderer, embedded through AndroidView.
 *
 * The renderer must be initialised against the same EGL context the
 * factory encodes with — a mismatched one shows black rather than
 * failing — hence [WebRtcFactory.eglBase].
 */
@Composable
actual fun VideoSurface(engine: CallEngine, local: Boolean, modifier: Modifier) {
    val android = engine as? AndroidCallEngine ?: return
    val video by android.video.collectAsState()
    val track = if (local) android.localVideoTrack else android.remoteVideoTrack
    val on = if (local) video.localOn else video.remoteOn
    if (track == null || !on) return

    val renderer = remember(track) { mutableRendererFor() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(WebRtcFactory.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
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

/** Holds the view so the dispose hook can detach the sink; a plain
 *  local would be recreated on every recomposition. */
private fun mutableRendererFor() =
    androidx.compose.runtime.mutableStateOf<SurfaceViewRenderer?>(null)
