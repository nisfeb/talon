package io.nisfeb.talon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import io.nisfeb.talon.call.CallEngine
import io.nisfeb.talon.call.DesktopCallEngine
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Desktop has no video renderer, so this is one.
 *
 * webrtc-java hands over I420 frames and nothing else: no view, no
 * surface. Each frame is converted to RGB and drawn into a Canvas.
 *
 * ponytail: colour conversion in Kotlin, one frame at a time, no
 * hardware path. Fine for the 640x480 the engine captures; if a
 * larger stream ever arrives this is the thing that will show up in a
 * profile, and the upgrade is a Skia surface fed from a native
 * converter rather than a smarter loop here.
 */
@Composable
actual fun VideoSurface(engine: CallEngine, local: Boolean, modifier: Modifier) {
    val desktop = engine as? DesktopCallEngine ?: return
    val video by desktop.video.collectAsState()
    VideoTrackCanvas(
        track = if (local) desktop.localVideoTrack else desktop.remoteVideoTrack,
        on = if (local) video.localOn else video.remoteOn,
        mirror = local,
        modifier = modifier,
    )
}

@Composable
actual fun VideoSurface(
    link: io.nisfeb.talon.call.PeerLink,
    local: Boolean,
    modifier: Modifier,
) {
    val d = link as? io.nisfeb.talon.call.DesktopPeerLink ?: return
    val video by d.video.collectAsState()
    VideoTrackCanvas(
        track = if (local) d.localVideoTrack else d.remoteVideoTrack,
        on = if (local) video.localOn else video.remoteOn,
        mirror = local,
        modifier = modifier,
    )
}

/** Shared renderer: converts a track's I420 frames to a Canvas. */
@Composable
private fun VideoTrackCanvas(
    track: dev.onvoid.webrtc.media.video.VideoTrack?,
    on: Boolean,
    mirror: Boolean,
    modifier: Modifier,
) {
    if (track == null || !on) return

    var bitmap by remember(track) { mutableStateOf<ImageBitmap?>(null) }
    var rotation by remember(track) { mutableStateOf(0) }

    DisposableEffect(track) {
        val converter = FrameConverter()
        val sink = VideoTrackSink { frame: VideoFrame ->
            // The frame is reference-counted and recycled the moment
            // this returns, so it must be converted here rather than
            // stashed for the composition to read later.
            runCatching {
                bitmap = converter.toBitmap(frame)
                rotation = frame.rotation
            }
        }
        track.addSink(sink)
        onDispose { runCatching { track.removeSink(sink) } }
    }

    Canvas(modifier) {
        val image = bitmap ?: return@Canvas
        drawFitted(image, mirror, rotation)
    }
}

/** Draw [image] centred, aspect-fitted, and turned the right way up. */
private fun DrawScope.drawFitted(image: ImageBitmap, mirror: Boolean, rotation: Int) {
    // A phone held in portrait sends landscape frames plus a rotation
    // of 90 or 270; ignoring it drew every mobile camera on its side.
    // Fit against the post-rotation footprint, or a turned frame is
    // scaled to the wrong axis and overflows the tile.
    val turned = rotation == 90 || rotation == 270
    val fitW = if (turned) image.height else image.width
    val fitH = if (turned) image.width else image.height
    val scale = minOf(size.width / fitW, size.height / fitH)
    val w = (image.width * scale).roundToInt()
    val h = (image.height * scale).roundToInt()
    rotate(degrees = rotation.toFloat()) {
        // Our own camera is a mirror, the way every video app behaves.
        if (mirror) {
            scale(scaleX = -1f, scaleY = 1f) {
                drawFittedRaw(image, w, h)
            }
        } else {
            drawFittedRaw(image, w, h)
        }
    }
}

private fun DrawScope.drawFittedRaw(image: ImageBitmap, w: Int, h: Int) {
    drawImage(
        image = image,
        dstOffset = androidx.compose.ui.unit.IntOffset(
            ((size.width - w) / 2).roundToInt(),
            ((size.height - h) / 2).roundToInt(),
        ),
        dstSize = androidx.compose.ui.unit.IntSize(w, h),
    )
}

/**
 * I420 to RGB, reusing its buffers between frames.
 *
 * Allocating a BufferedImage per frame at 30fps is 30 short-lived
 * multi-megabyte arrays a second, which is a GC problem rather than a
 * correctness one — hence the reuse.
 */
private class FrameConverter {
    private var image: BufferedImage? = null
    private var pixels: IntArray = IntArray(0)

    fun toBitmap(frame: VideoFrame): ImageBitmap = frame.buffer.toI420().let { i420 ->
        try {
            convert(i420)
        } finally {
            // toI420() hands back a NEW reference-counted buffer. Not
            // releasing it leaked a native I420 copy every frame — at
            // 30fps that is the whole video pane's memory, per call.
            runCatching { i420.release() }
        }
    }

    private fun convert(i420: dev.onvoid.webrtc.media.video.I420Buffer): ImageBitmap {
        val w = i420.width
        val h = i420.height
        val img = image?.takeIf { it.width == w && it.height == h }
            ?: BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also {
                image = it
                pixels = IntArray(w * h)
            }

        val y = i420.dataY
        val u = i420.dataU
        val v = i420.dataV
        val strideY = i420.strideY
        val strideU = i420.strideU
        val strideV = i420.strideV

        i420ToRgb(y, u, v, strideY, strideU, strideV, w, h, pixels)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        return img.toComposeImageBitmap()
    }
}

/**
 * I420 planes to packed RGB, BT.601 limited range — the colour space
 * libwebrtc encodes in.
 *
 * Split out of [FrameConverter] because it is the only part of the
 * desktop renderer that is arithmetic rather than plumbing, and the
 * only part that fails *quietly*: wrong coefficients, a wrong stride
 * or a wrong chroma subsample all produce a picture, just the wrong
 * one. VideoFrameConversionTest pins it against known colours.
 *
 * Chroma is half resolution in both directions, hence the `shr 1` on
 * both row and column.
 */
internal fun i420ToRgb(
    y: java.nio.ByteBuffer,
    u: java.nio.ByteBuffer,
    v: java.nio.ByteBuffer,
    strideY: Int,
    strideU: Int,
    strideV: Int,
    width: Int,
    height: Int,
    out: IntArray,
) {
    for (row in 0 until height) {
        val uvRow = row shr 1
        for (col in 0 until width) {
            val yy = (y.get(row * strideY + col).toInt() and 0xff) - 16
            val uu = (u.get(uvRow * strideU + (col shr 1)).toInt() and 0xff) - 128
            val vv = (v.get(uvRow * strideV + (col shr 1)).toInt() and 0xff) - 128
            val c = 298 * yy
            val r = ((c + 409 * vv + 128) shr 8).coerceIn(0, 255)
            val g = ((c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
            val b = ((c + 516 * uu + 128) shr 8).coerceIn(0, 255)
            out[row * width + col] = (r shl 16) or (g shl 8) or b
        }
    }
}
