package io.nisfeb.talon.call

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A 1:1 call's camera: a camera chosen mid-call reopens on it, and one
 * that won't open says so. Party lines had both; a 1:1 call had neither.
 */
class CallCameraTest {
    /** Media whose camera opens or not as the test says, and remembers each try. */
    private class CameraEngine : CallEngine {
        private val desc = SessionDesc("v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB")
        val stateFlow = MutableStateFlow(MediaState.Idle)
        override val state: StateFlow<MediaState> = stateFlow
        val videoFlow = MutableStateFlow(VideoState())
        override val video: StateFlow<VideoState> = videoFlow
        val tries = CopyOnWriteArrayList<Boolean>()
        @Volatile var opens = true
        override suspend fun setCameraEnabled(enabled: Boolean): Boolean {
            tries += enabled
            if (!enabled) {
                videoFlow.value = videoFlow.value.copy(localOn = videoFlow.value.sharing)
                return true
            }
            if (opens) videoFlow.value = videoFlow.value.copy(localOn = true)
            return opens
        }
        override suspend fun createOffer() = desc
        override suspend fun acceptOffer(remote: SessionDesc) = desc
        override suspend fun setAnswer(remote: SessionDesc) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    private val engines = CopyOnWriteArrayList<CameraEngine>()

    private fun call(block: suspend (TrunkHarness, CallController) -> Unit) = runBlocking {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { CameraEngine().also { engines += it } })
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            block(h, c)
        } finally {
            c.stop()
        }
    }

    private suspend fun placed(h: TrunkHarness, c: CallController): CameraEngine {
        val before = engines.size
        c.placeCall("~zod")
        until("the call has an id") { c.currentCallId != null }
        val id = c.currentCallId!!
        // This call's offer: an earlier call's is in the log too.
        h.awaitPut { TrunkHarness.sigIdIn(it, "offer") == id }
        h.emitFact("""{"recv":{"from":"~zod","sig":{"accept":{"id":"$id","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}""")
        return live(c, before)
    }

    private suspend fun answered(h: TrunkHarness, c: CallController, id: String): CameraEngine {
        val before = engines.size
        h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"$id"}}}}""")
        withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }
        h.emitFact("""{"recv":{"from":"~zod","sig":{"offer":{"id":"$id","sdp":"v=0\na=fingerprint:sha-256 AA:AA\n","fpr":"sha-256 AA:AA"}}}}""")
        c.accept()
        return live(c, before)
    }

    /** The call's engine, made after the [before] engines of earlier calls; an answer makes it after the call reads as on. */
    private suspend fun live(c: CallController, before: Int): CameraEngine {
        withTimeout(10_000) { c.state.first { it is CallUiState.Active } }
        until("the call made its engine") { engines.size > before }
        val eng = engines.last()
        eng.stateFlow.value = MediaState.Live
        withTimeout(10_000) { c.state.first { (it as? CallUiState.Active)?.media == MediaState.Live } }
        return eng
    }

    private suspend fun until(what: String, ok: () -> Boolean) {
        runCatching { withTimeout(5_000) { while (!ok()) delay(20) } }
            .onFailure { throw AssertionError(what) }
    }

    @Test
    fun `a camera chosen mid-call closes and reopens, in that order`() = call { h, c ->
        val eng = placed(h, c)
        c.setCamera(true)
        until("the camera opened") { eng.videoFlow.value.localOn }
        c.restartCamera()
        until("reopened: ${eng.tries}") { eng.tries.size == 3 }
        assertEquals(listOf(true, false, true), eng.tries.toList())
        assertTrue(eng.videoFlow.value.localOn)
        assertFalse(c.cameraFailed.value)
    }

    @Test
    fun `a camera that is off stays off when another is chosen`() = call { h, c ->
        val eng = placed(h, c)
        c.restartCamera()
        delay(300)
        assertEquals(emptyList(), eng.tries.toList(), "choosing a camera opened it")
    }

    @Test
    fun `a share keeps its sender when a camera is chosen`() = call { h, c ->
        val eng = placed(h, c)
        eng.videoFlow.value = VideoState(localOn = true, sharing = true)
        c.restartCamera()
        delay(300)
        assertEquals(emptyList(), eng.tries.toList(), "the camera took the share's place")
    }

    @Test
    fun `a camera that won't open says so, until it opens or is turned off`() = call { h, c ->
        val eng = placed(h, c)
        eng.opens = false
        c.setCamera(true)
        until("the failure is said") { c.cameraFailed.value }
        c.setCamera(false)
        until("turning it off clears it") { !c.cameraFailed.value }
        c.setCamera(true)
        until("said again") { c.cameraFailed.value }
        eng.opens = true
        c.setCamera(true)
        until("opening clears it") { !c.cameraFailed.value }
    }

    @Test
    fun `a chosen camera that won't open says so`() = call { h, c ->
        val eng = placed(h, c)
        c.setCamera(true)
        until("the camera opened") { eng.videoFlow.value.localOn }
        eng.opens = false
        c.restartCamera()
        until("the failure is said") { c.cameraFailed.value }
    }

    // A failure belongs to its call: placed then answered, and answered
    // then placed, as the two make their engines in different places.
    @Test
    fun `the next call starts with no camera failure`() = call { h, c ->
        placed(h, c).opens = false
        c.setCamera(true)
        until("said on the first call") { c.cameraFailed.value }
        c.hangup()
        withTimeout(10_000) { c.state.first { it !is CallUiState.Active } }
        c.dismissEnded()
        answered(h, c, "a2").opens = false
        assertFalse(c.cameraFailed.value, "an answered call began with the last call's failure")
        c.setCamera(true)
        until("said on the second call") { c.cameraFailed.value }
        c.hangup()
        withTimeout(10_000) { c.state.first { it !is CallUiState.Active } }
        c.dismissEnded()
        placed(h, c)
        assertFalse(c.cameraFailed.value, "a placed call began with the last call's failure")
    }
}
