package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.login.TalonLoginUri
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Desktop records no voice messages and scans no QR (the flags are
// false), so these are never composed.

@Composable
actual fun VoiceRecordButton(enabled: Boolean, onRecorded: (path: String, durationMs: Long) -> Unit, modifier: Modifier, externalTrigger: Flow<Unit>?) = Unit

@Composable
actual fun VoicePreviewPlayButton(path: String, enabled: Boolean) = Unit

@Composable
actual fun rememberQrScanLauncher(prompt: String, onResult: (String?) -> Unit): (() -> Unit)? = null

private const val WAIT_FOR_FIRST_FRAME_MS = 8_000L

/**
 * Whether this computer can play a video in the row: vlcj binds to the
 * system's libvlc, and there is no libvlc on a machine without VLC.
 *
 * Null until the answer is in. It is looked for on a thread of its own
 * because looking means walking directories, and vlcj's discovery does
 * that with File.listFiles and File.isDirectory: on a machine with a
 * slow or enormous directory in the search path that is seconds, and it
 * used to happen on the first composition of the whole app, which is
 * before a window is drawn. The report was "no UI loads", with the
 * watchdog naming BaseNativeDiscoveryStrategy.discover on the event
 * thread. Nothing about a video in a chat row is worth a start that
 * never finishes, so until it answers there is no player, and rows
 * fall back to the link the way they do on a machine without VLC.
 *
 * TALON_NO_VLC=1 skips the search, for anyone it still hangs.
 */
private val vlcPresent = androidx.compose.runtime.mutableStateOf<Boolean?>(null)
private val lookedForVlc = kotlinx.atomicfu.atomic(false)

private fun lookForVlc() {
    if (!lookedForVlc.compareAndSet(expect = false, update = true)) return
    if (System.getenv("TALON_NO_VLC")?.trim().orEmpty().let { it == "1" || it.equals("true", true) }) {
        Log.i("DesktopMedia", "TALON_NO_VLC is set; not looking for libvlc")
        vlcPresent.value = false
        return
    }
    kotlin.concurrent.thread(name = "talon-vlc-probe", isDaemon = true) {
        val found = runCatching { NativeDiscovery().discover() }
            .onFailure { Log.i("DesktopMedia", "no libvlc: ${it.message}") }
            .getOrDefault(false)
        Log.i("DesktopMedia", if (found) "libvlc found" else "no libvlc; media rows keep their links")
        vlcPresent.value = found
    }
}

/**
 * Video plays in the row where VLC is installed; audio keeps the link
 * it has always had. Without libvlc there is no player at all and the
 * row falls back to the link, which is what desktop did before.
 */
actual fun platformInlineMediaPlayer(): (@Composable (url: String, kind: MediaKind) -> Unit)? {
    // Read during composition, so the rows redraw with a player the
    // moment the search answers, and the search itself never runs here.
    val present = vlcPresent.value
    if (present == null) {
        lookForVlc()
        return null
    }
    return if (!present) null else { url, kind ->
        when (kind) {
            MediaKind.VIDEO -> DesktopInlineVideoPlayer(url)
            MediaKind.AUDIO -> FallbackInlineMediaRow(url, kind)
        }
    }
}

/**
 * A video in the chat row, decoded by libvlc into frames Compose draws
 * itself. Frames rather than an embedded native surface on purpose: an
 * AWT window inside the Compose scene is its own layer, and on Wayland
 * it lands in the wrong place or over the rest of the app.
 *
 * Nothing is decoded until the person presses play, so a screenful of
 * videos costs nothing, and leaving the row releases the player.
 *
 * ponytail: one frame, one ImageBitmap, which is a copy per frame. It
 * is a chat row, not a cinema; a reused bitmap is the upgrade if a
 * long video ever shows it.
 */
@Composable
private fun DesktopInlineVideoPlayer(url: String) {
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var aspect by remember(url) { mutableStateOf(16f / 9f) }
    var playing by remember(url) { mutableStateOf(false) }
    // A VLC without the codec plays nothing and says nothing: the row
    // would sit black forever. Treated as a failure, it becomes the
    // link again, which at least opens in something that can play it.
    var failed by remember(url) { mutableStateOf(false) }

    // One image and one pixel array for the life of the row, refilled
    // per frame: the call view's frames already work this way, and a
    // fresh allocation per frame is the whole pane's memory at 30fps.
    val canvasFor = remember(url) { arrayOfNulls<BufferedImage>(1) }
    val pixelsFor = remember(url) { arrayOfNulls<IntArray>(1) }

    val component = remember(url) {
        val render = object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
            override fun display(
                mediaPlayer: MediaPlayer,
                buffers: Array<out ByteBuffer>,
                format: BufferFormat,
                width: Int,
                height: Int,
            ) {
                if (width <= 0 || height <= 0) return
                val src = buffers.firstOrNull() ?: return
                val img = canvasFor[0]?.takeIf { it.width == width && it.height == height }
                    ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also {
                        canvasFor[0] = it
                        pixelsFor[0] = IntArray(width * height)
                    }
                val pixels = pixelsFor[0] ?: return
                // RV32 is BGRA laid out little-endian, which read as a
                // 32-bit int is exactly the ARGB BufferedImage wants.
                src.rewind()
                src.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, minOf(pixels.size, src.capacity() / 4))
                img.setRGB(0, 0, width, height, pixels, 0, width)
                runCatching { img.toComposeImageBitmap() }.onSuccess {
                    frame = it
                    aspect = width.toFloat() / height.toFloat()
                }
            }
        }
        val formats = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
                RV32BufferFormat(sourceWidth, sourceHeight)
            override fun newFormatSize(w: Int, h: Int, dw: Int, dh: Int) = Unit
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }
        runCatching { CallbackMediaPlayerComponent(null, null, null, true, render, formats, null) }
            .onFailure { Log.w("DesktopMedia", "the player would not start: ${it.message}") }
            .getOrNull()
    }

    DisposableEffect(url) {
        onDispose { runCatching { component?.release() } }
    }
    DisposableEffect(component) {
        val player = component?.mediaPlayer()
        val listener = object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) {
                failed = true
            }
        }
        player?.events()?.addMediaPlayerEventListener(listener)
        onDispose { runCatching { player?.events()?.removeMediaPlayerEventListener(listener) } }
    }
    // Nothing decoded a while after pressing play is a codec this VLC
    // does not have, which it reports by carrying on with no picture.
    LaunchedEffect(playing, frame) {
        if (playing && frame == null) {
            delay(WAIT_FOR_FIRST_FRAME_MS)
            if (frame == null) {
                Log.i("DesktopMedia", "no picture after ${WAIT_FOR_FIRST_FRAME_MS}ms; falling back to the link")
                runCatching { component?.mediaPlayer()?.controls()?.stop() }
                playing = false
                failed = true
            }
        }
    }

    if (component == null || failed) {
        FallbackInlineMediaRow(url, MediaKind.VIDEO)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable {
                val controls = component.mediaPlayer().controls()
                if (playing) {
                    controls.pause()
                    playing = false
                } else {
                    // The first press is what opens the stream: nothing
                    // is fetched or decoded for a video nobody watches.
                    if (frame == null) component.mediaPlayer().media().play(url) else controls.play()
                    playing = true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!playing) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White)
            }
            if (frame == null) {
                Text(
                    url.substringAfterLast('/').substringBefore('?'),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }
    }
}
