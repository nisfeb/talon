package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nisfeb.talon.call.CallEngine

/**
 * Renders one side of a call's video.
 *
 * `expect`/`actual` rather than an interface, because every platform
 * that has calls at all must be able to draw them — the CLAUDE.md #2
 * test — and because each one needs its own native surface:
 * SurfaceViewRenderer on Android, RTCMTLVideoView on iOS, and a
 * hand-converted frame on desktop, which has no renderer at all.
 *
 * Takes the engine rather than a track, since a track type cannot
 * cross into commonMain. Draws nothing when [engine] is not the
 * platform's own engine or has no picture yet, so a caller can place
 * it unconditionally.
 */
@Composable
expect fun VideoSurface(
    engine: CallEngine,
    /** True for our own camera, false for the far end's. */
    local: Boolean,
    modifier: Modifier,
)
