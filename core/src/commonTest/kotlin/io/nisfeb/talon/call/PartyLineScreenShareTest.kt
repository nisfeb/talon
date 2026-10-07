package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A shared screen on a party line: it goes out on the up link's video
 * sender, takes turns with the camera, marks us video-on for the room,
 * and survives the up link being republished.
 */
class PartyLineScreenShareTest {
    private class ScriptedLink(private val shareOk: Boolean = true, private val log: MutableList<String> = mutableListOf()) : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        val videoFlow = MutableStateFlow(VideoState())
        override val video: StateFlow<VideoState> get() = videoFlow
        val shares = mutableListOf<ScreenSource?>()
        val cameras = mutableListOf<Boolean>()
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() { log += "close ${hashCode()}" }
        override suspend fun setCameraEnabled(enabled: Boolean): Boolean = true.also { cameras += enabled }
        override suspend fun screenSources(): List<ScreenSource> = listOf(SCREEN)
        override suspend fun setScreenShare(source: ScreenSource?): Boolean = (shareOk || source == null).also { ok ->
            shares += source
            log += "share ${hashCode()}"
            if (ok) videoFlow.value = VideoState(localOn = source != null, sharing = source != null)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun joined() = json.decodeFromString<JsonObject>(
        """{"type":"joined","kind":"join","rtcConfiguration":{"iceServers":[]}}""",
    )
    private fun abort(id: String) = json.decodeFromString<JsonObject>("""{"type":"abort","id":"$id"}""")

    private fun line(ups: MutableList<ScriptedLink>, log: MutableList<String> = mutableListOf(), shareOk: (Int) -> Boolean = { true }) = PartyLine(
        HttpClient(),
        links = { _, sendAudio -> ScriptedLink(shareOk(ups.size), log).also { if (sendAudio) ups += it } },
    )

    @Test
    fun `a share goes out on the up link and shows us as video`() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val l = line(ups)
        l.handle(joined())
        assertEquals(listOf(SCREEN), l.screenSources())
        assertTrue(l.setScreenShare(SCREEN))
        assertEquals(listOf<ScreenSource?>(SCREEN), ups.single().shares)
        assertTrue((l.shared.value != null))
        assertTrue(l.videoOn.value.isNotEmpty(), "the room is told our video is on")
        assertTrue(l.setScreenShare(null))
        assertFalse((l.shared.value != null))
        assertTrue(l.videoOn.value.isEmpty(), "and that it went off")
    }

    @Test
    fun `the camera and a share take turns`() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val l = line(ups)
        l.handle(joined())
        assertTrue(l.setCameraEnabled(true))
        assertTrue(l.setScreenShare(SCREEN))
        assertFalse(l.cameraOn.value, "a share turns the camera off")
        assertTrue((l.shared.value != null))
        assertTrue(l.setCameraEnabled(true))
        assertTrue(l.cameraOn.value)
        assertFalse((l.shared.value != null), "the camera takes the sender back")
    }

    @Test
    fun `a refused share sets nothing`() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val l = line(ups) { false }
        l.handle(joined())
        assertFalse(l.setScreenShare(SCREEN))
        assertFalse((l.shared.value != null))
        assertTrue(l.videoOn.value.isEmpty())
    }

    @Test
    fun `without an up link there is nothing to share on`() = runBlocking {
        val l = PartyLine(HttpClient(), links = { _, _ -> ScriptedLink() })
        assertFalse(l.setScreenShare(SCREEN))
        assertTrue(l.screenSources().isEmpty())
    }

    @Test
    fun `a republished up link shares again`() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val l = line(ups)
        l.handle(joined())
        assertTrue(l.setScreenShare(SCREEN))
        l.handle(abort(l.upId))
        assertEquals(2, ups.size, "the aborted up link was republished")
        withTimeout(5_000) { while (ups[1].shares.isEmpty()) delay(10) }
        assertEquals(listOf<ScreenSource?>(SCREEN), ups[1].shares)
        assertTrue((l.shared.value != null))
    }

    // Opened again, the capture asked Wayland's system dialog again in the
    // middle of the share. Taken before the old link lets it go, the
    // capture is handed over (desktop: SharedScreenCapture).
    @Test
    fun `a republished up link takes the share before the old one lets it go`() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val log = mutableListOf<String>()
        val l = line(ups, log)
        l.handle(joined())
        assertTrue(l.setScreenShare(SCREEN))
        log.clear()
        l.handle(abort(l.upId))
        assertEquals(listOf("share ${ups[1].hashCode()}", "close ${ups[0].hashCode()}"), log.take(2))
    }

    @Test
    fun `a share that will not restart is dropped, not left flagged`() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        // The first up link shares; its replacement refuses.
        val l = line(ups) { it == 0 }
        l.handle(joined())
        assertTrue(l.setScreenShare(SCREEN))
        l.handle(abort(l.upId))
        withTimeout(5_000) { while ((l.shared.value != null)) delay(10) }
        assertTrue(l.videoOn.value.isEmpty(), "peers are not left with a frameless tile")
    }

    @Test
    fun `a share the link ends itself ends on the line too`() = runBlocking {
        // A cancelled system dialog, or a capture that stopped sending:
        // the link ends the share, and the room and the button must follow.
        val ups = mutableListOf<ScriptedLink>()
        val l = line(ups)
        l.handle(joined())
        assertTrue(l.setScreenShare(SCREEN))
        ups.single().videoFlow.value = VideoState()
        withTimeout(5_000) { while (l.shared.value != null) delay(10) }
        assertTrue(l.videoOn.value.isEmpty(), "the room is told our video went off")
    }

    private companion object {
        val SCREEN = ScreenSource(1, "Built-in display", isWindow = false)
    }
}
