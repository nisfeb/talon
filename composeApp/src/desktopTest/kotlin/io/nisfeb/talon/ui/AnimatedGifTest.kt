package io.nisfeb.talon.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.nisfeb.talon.ui.screens.ImageViewerScreen
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** GIFs move (a user's report, 2026-11-01: "GIFs don't play in lightbox preview", desktop). */
@OptIn(ExperimentalTestApi::class, coil3.annotation.DelicateCoilApi::class)
class AnimatedGifTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/gif/$name")!!.readBytes()
    private val loader = talonImageLoader(PlatformContext.INSTANCE)

    private fun fetched(name: String) =
        SourceFetchResult(ImageSource(Buffer().write(bytes(name)), FileSystem.SYSTEM), null, DataSource.NETWORK)

    private fun decoded(name: String) = runBlocking {
        val r = fetched(name)
        animatedImageDecoder()!!.create(r, Options(PlatformContext.INSTANCE), loader)?.decode()?.image
    }

    @AfterTest
    fun reset() = SingletonImageLoader.reset()

    @Test
    fun `a GIF with frames decodes to its frames, at its size`() {
        val image = assertIs<AnimatedSkiaImage>(decoded("two-frames.gif"))
        assertEquals(40, image.width)
        assertEquals(30, image.height)
        assertEquals(2L * 40 * 30 * 4, image.size)
    }

    @Test
    fun `a GIF of one frame is a still, and anything else is left to Coil`() {
        assertIs<BitmapImage>(decoded("one-frame.gif"))
        assertNull(decoded("still.png"), "not a GIF: Coil's own decoder takes it")
    }

    // The whole way: Coil fetches the GIF over HTTP and the viewer shows it
    // moving, red, then blue, then red again, as the frames come round.
    @Test
    fun `a GIF in the picture viewer moves`() {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a.gif") { ex ->
                val body = bytes("two-frames.gif")
                ex.responseHeaders.add("Content-Type", "image/gif")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            SingletonImageLoader.setUnsafe(talonImageLoader(PlatformContext.INSTANCE))
            runComposeUiTest {
                setContent { ImageViewerScreen(listOf("http://127.0.0.1:${server.address.port}/a.gif"), onClose = {}) }
                fun middle(): Color = onNodeWithTag("viewer-image", useUnmergedTree = true).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
                fun near(c: Color, want: Color) = kotlin.math.abs(c.red - want.red) < 0.2f && kotlin.math.abs(c.blue - want.blue) < 0.2f
                waitUntil(timeoutMillis = 10_000) { near(middle(), Color.Red) }
                waitUntil(timeoutMillis = 5_000) { near(middle(), Color.Blue) }
                // came round to the first frame again: it loops
                waitUntil(timeoutMillis = 5_000) { near(middle(), Color.Red) }
            }
        } finally {
            server.stop(0)
        }
    }
}
