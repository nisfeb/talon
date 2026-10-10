package io.nisfeb.talon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.skiaCanvas
import dev.onvoid.webrtc.media.video.VideoFrame
import io.nisfeb.talon.call.CallEngine
import io.nisfeb.talon.call.DesktopCallEngine
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode
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
actual fun VideoSurface(
    engine: CallEngine,
    local: Boolean,
    modifier: Modifier,
    onFrameAspect: ((Float) -> Unit)?,
) {
    val desktop = engine as? DesktopCallEngine ?: return
    val video by desktop.video.collectAsState()
    VideoTrackCanvas(
        track = if (local) desktop.localVideoTrack else desktop.remoteVideoTrack,
        on = if (local) video.localOn else video.remoteOn,
        // A mirror suits a camera; a shared screen's text must read right.
        mirror = local && !video.sharing,
        modifier = modifier,
        onFrameAspect = onFrameAspect,
    )
}

@Composable
actual fun VideoSurface(
    link: io.nisfeb.talon.call.PeerLink,
    local: Boolean,
    modifier: Modifier,
    onFrameAspect: ((Float) -> Unit)?,
) {
    val d = link as? io.nisfeb.talon.call.DesktopPeerLink ?: return
    val video by d.video.collectAsState()
    VideoTrackCanvas(
        track = if (local) d.localVideoTrack else d.remoteVideoTrack,
        on = if (local) video.localOn else video.remoteOn,
        mirror = local && !video.sharing,
        modifier = modifier,
        onFrameAspect = onFrameAspect,
    )
}

/** Shared renderer: converts a track's I420 frames to a Canvas. */
@Composable
internal fun VideoTrackCanvas(
    track: dev.onvoid.webrtc.media.video.VideoTrack?,
    on: Boolean,
    mirror: Boolean,
    modifier: Modifier,
    onFrameAspect: ((Float) -> Unit)?,
) {
    if (track == null || !on) return

    val holder = remember(track) { FrameHolder() }
    // Bumped per frame so the Canvas draws again; the frame is in [holder].
    var shown by remember(track) { mutableStateOf(0L) }
    var rotation by remember(track) { mutableStateOf(0) }

    val frames = remember(track) { java.util.concurrent.atomic.AtomicLong(0) }
    val lastFrameMs = remember(track) { java.util.concurrent.atomic.AtomicLong(0) }
    val failures = remember(track) { java.util.concurrent.atomic.AtomicLong(0) }
    DisposableEffect(track) {
        val converter = FrameConverter()
        var lastAspect = 0f
        val sink = io.nisfeb.talon.call.releasingSink { frame: VideoFrame ->
            // The frame is reference-counted and recycled the moment
            // this returns, so it must be converted here rather than
            // stashed for the composition to read later.
            runCatching {
                holder.put(converter.toImage(frame))
                rotation = frame.rotation
                val turned = frame.rotation == 90 || frame.rotation == 270
                val w = frame.buffer.width; val h = frame.buffer.height
                val aspect = if (turned) h.toFloat() / w else w.toFloat() / h
                if (aspect != lastAspect) { lastAspect = aspect; onFrameAspect?.invoke(aspect) }
                lastFrameMs.set(System.currentTimeMillis())
                // Counted before it is set: 0 over 0 would not redraw, and the
                // first frame stayed black until the second arrived.
                shown = frames.incrementAndGet()
                if (shown == 1L) {
                    io.nisfeb.talon.util.Log.i(
                        "VideoSurface",
                        "first frame ${frame.buffer.width}x${frame.buffer.height} rot=${frame.rotation} " +
                            "buffer=${frame.buffer::class.simpleName} mirror=$mirror",
                    )
                }
            }.onFailure {
                // Every failure used to vanish here, which is what a
                // "video freezes after a few seconds" report looks like
                // from the inside: the last good frame stays on screen.
                if (failures.getAndIncrement() < 3) {
                    io.nisfeb.talon.util.Log.w("VideoSurface", "frame ${frames.get()} could not be drawn", it)
                }
            }
        }
        track.addSink(sink)
        onDispose {
            runCatching { track.removeSink(sink) }
            holder.close()
        }
    }
    // Say so when frames stop while the pane is still meant to be live.
    LaunchedEffect(track) {
        var stalled = false
        while (true) {
            kotlinx.coroutines.delay(1_000)
            val last = lastFrameMs.get()
            val quiet = last != 0L && System.currentTimeMillis() - last > 3_000
            if (quiet && !stalled) {
                io.nisfeb.talon.util.Log.w(
                    "VideoSurface",
                    "no frames for 3s after ${frames.get()} frames (${failures.get()} failed to draw)",
                )
            } else if (!quiet && stalled) {
                io.nisfeb.talon.util.Log.i("VideoSurface", "frames resumed at ${frames.get()}")
            }
            stalled = quiet
        }
    }

    Canvas(modifier) {
        shown
        holder.use { drawFitted(it, mirror, rotation) }
    }
}

/**
 * The frame a tile shows: one Skia raster image at a time, closed as
 * soon as the next replaces it.
 *
 * Each frame used to become a BufferedImage, then a Compose bitmap: 8 MB
 * of Java garbage and an 8 MB native bitmap per 1080p frame, the bitmap
 * freed only once a collection got round to it. At 15 fps one tile
 * churned about 240 MB a second, and Talon on Windows swung between 2
 * and 5 GB through a screen share (2026-10-10). Closing here frees a
 * frame's pixels without waiting for a collection; a frame the canvas
 * is still drawing keeps them, since Skia counts its own references.
 */
internal class FrameHolder {
    private var image: Image? = null
    private var closed = false

    @Synchronized fun put(next: Image) {
        if (closed) { next.close(); return }
        image?.close()
        image = next
    }

    /** [block] runs with the image held now, which cannot be closed meanwhile. */
    @Synchronized fun <T> use(block: (Image) -> T): T? = image?.let(block)

    @Synchronized fun close() {
        closed = true
        image?.close()
        image = null
    }
}

/** Draw [image] centred, aspect-fitted, and turned the right way up. */
internal fun DrawScope.drawFitted(image: Image, mirror: Boolean, rotation: Int) {
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

private fun DrawScope.drawFittedRaw(image: Image, w: Int, h: Int) {
    val x = ((size.width - w) / 2).roundToInt().toFloat()
    val y = ((size.height - h) / 2).roundToInt().toFloat()
    // Linear, as Compose's drawImage filtered by default.
    drawContext.canvas.skiaCanvas.drawImageRect(
        image, 0f, 0f, image.width.toFloat(), image.height.toFloat(),
        x, y, x + w, y + h, SamplingMode.LINEAR, null, true,
    )
}

/**
 * I420 to a Skia raster image, through arrays reused between frames, so
 * a frame makes no Java garbage: the image's own native copy is all it
 * allocates, and [FrameHolder] frees that.
 */
internal class FrameConverter {
    private var pixels: IntArray = IntArray(0)
    private var bytes: ByteArray = ByteArray(0)

    fun toImage(frame: VideoFrame): Image = frame.buffer.toI420().let { i420 ->
        try {
            convert(i420)
        } finally {
            // The frame itself is released by its sink (releasingSink).
            // On the native buffers a sink gets, toI420() is that same
            // buffer, and releasing it here as well would free it twice;
            // only a buffer converted into a copy is this one's to free.
            if (i420 !== frame.buffer) runCatching { i420.release() }
        }
    }

    private fun convert(i420: dev.onvoid.webrtc.media.video.I420Buffer): Image {
        val w = i420.width
        val h = i420.height
        if (pixels.size != w * h) {
            pixels = IntArray(w * h)
            bytes = ByteArray(w * h * 4)
        }

        val y = i420.dataY
        val u = i420.dataU
        val v = i420.dataV
        val strideY = i420.strideY
        val strideU = i420.strideU
        val strideV = i420.strideV

        i420ToRgb(y, u, v, strideY, strideU, strideV, w, h, pixels)
        // 0xRRGGBB, made opaque, laid out little-endian: Skia's N32 order (B, G, R, A).
        for (i in pixels.indices) pixels[i] = pixels[i] or OPAQUE
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
        return Image.makeRaster(ImageInfo.makeN32(w, h, ColorAlphaType.OPAQUE), bytes, w * 4)
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24
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
