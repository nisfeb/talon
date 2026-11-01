package io.nisfeb.talon.ui

import androidx.compose.runtime.mutableIntStateOf
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

actual fun animatedImageDecoder(): Decoder.Factory? = SkiaGifDecoder.Factory()

/**
 * A GIF through Skia's codec, every frame decoded once. One frame, or more
 * frames than [FRAME_BUDGET_BYTES] holds, is a still of the first.
 * ponytail: all frames held decoded; decode each as it is due if large GIFs matter.
 */
class SkiaGifDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        val info = codec.imageInfo
        val frameBytes = info.width.toLong() * info.height * 4
        val count = if (codec.frameCount * frameBytes > FRAME_BUDGET_BYTES) 1 else codec.frameCount.coerceAtLeast(1)
        val bitmaps = (0 until count).map { i -> Bitmap().also { b -> b.allocPixels(info); codec.readPixels(b, i) } }
        if (count == 1) return DecodeResult(bitmaps.single().asImage(), isSampled = false)
        val delays = IntArray(count) { i -> codec.framesInfo[i].duration.let { if (it <= 10) 100 else it } }
        return DecodeResult(AnimatedSkiaImage(bitmaps.map { Image.makeFromBitmap(it) }, delays, frameBytes * count), isSampled = false)
    }

    /** GIFs only, by their header; every other picture goes to Coil's own decoder. */
    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val head = result.source.source().peek()
            return if (head.rangeEquals(0, GIF87) || head.rangeEquals(0, GIF89)) SkiaGifDecoder(result.source) else null
        }
    }
}

/**
 * A GIF's frames, drawn by Coil's painter: the frame is Compose state read
 * while drawing, so each step redraws the picture where it is shown. The
 * clock runs only while something draws it, and stops a second after the
 * last draw (scrolled off, the viewer closed).
 */
class AnimatedSkiaImage(
    private val frames: List<Image>,
    private val delaysMs: IntArray,
    override val size: Long,
) : coil3.Image {
    override val width: Int get() = frames[0].width
    override val height: Int get() = frames[0].height
    override val shareable: Boolean get() = true

    private val frame = mutableIntStateOf(0)

    /** Which frame is drawn: read by a test. */
    val frameIndex: Int get() = frame.intValue
    private var lastDrawMs = 0L
    private var ticking = false

    override fun draw(canvas: Canvas) {
        lastDrawMs = nowMs()
        if (!ticking) tick()
        canvas.drawImage(frames[frame.intValue], 0f, 0f)
    }

    // On the main thread, as drawing is: the flags need no lock.
    private fun tick() {
        ticking = true
        clock.launch {
            while (nowMs() - lastDrawMs < IDLE_STOP_MS) {
                delay(delaysMs[frame.intValue].toLong())
                frame.intValue = (frame.intValue + 1) % frames.size
            }
            ticking = false
        }
    }

    private companion object {
        val clock = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        const val IDLE_STOP_MS = 1_000L
    }
}

/** At most this much decoded frames per GIF: 64 MB, about 200 frames of 300 by 300. */
const val FRAME_BUDGET_BYTES = 64L * 1024 * 1024

private val GIF87 = "GIF87a".encodeUtf8()
private val GIF89 = "GIF89a".encodeUtf8()
