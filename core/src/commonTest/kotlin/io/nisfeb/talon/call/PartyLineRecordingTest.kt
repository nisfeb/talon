package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Call recording's capture rules, which had no coverage at all.
 *
 * The bugs these pin down were all silent: audio that vanished on a
 * republish, speakers who never spoke being billed to Whisper as empty
 * uploads, and one shared sample rate pitch-shifting whoever didn't
 * match it.
 */
class PartyLineRecordingTest {

    private class FakeLink : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        var sink: ((ByteArray, Int) -> Unit)? = null
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0 fake-offer"
        override suspend fun answerTo(remoteSdp: String): String = "v=0 fake-answer"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun onPcm(sink: ((pcm: ByteArray, sampleRate: Int) -> Unit)?) {
            this.sink = sink
        }
        override fun close() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val made = mutableListOf<FakeLink>()
    private fun line() = PartyLine(
        HttpClient(),
        links = { _, _ -> FakeLink().also { made += it } },
    )

    private fun offer(id: String, who: String): JsonObject = json.decodeFromString(
        """{"type":"offer","id":"$id","username":"$who","sdp":"v=0 remote"}""",
    )

    /** [samples] frames of a constant non-silent tone. */
    private fun pcm(samples: Int, value: Int = 1000): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun capturesASpeakerAtTheirOwnRate() = runTest {
        val l = line()
        l.handle(offer("s1", "~hapnyl-fotlyx"))
        l.startRecording("~ricsul-bilwyt")
        made.last().sink!!(pcm(480), 48_000)

        val rec = l.stopRecording()
        assertEquals(setOf("~hapnyl-fotlyx"), rec.clips.keys)
        assertEquals(48_000, rec.rateOf("~hapnyl-fotlyx"))
        assertTrue(rec.clips["~hapnyl-fotlyx"]!!.isNotEmpty())
    }

    @Test
    fun eachSpeakerKeepsTheRateTheyWereCapturedAt() = runTest {
        val l = line()
        l.handle(offer("s1", "~hapnyl-fotlyx"))
        val a = made.last()
        l.handle(offer("s2", "~ricsul-bilwyt"))
        val b = made.last()

        l.startRecording("~sampel-palnet")
        a.sink!!(pcm(441), 44_100)
        b.sink!!(pcm(480), 48_000)
        val rec = l.stopRecording()

        // One shared rate used to win last-writer, so whichever speaker
        // lost was encoded — and transcribed — at the wrong pitch.
        assertEquals(44_100, rec.rateOf("~hapnyl-fotlyx"))
        assertEquals(48_000, rec.rateOf("~ricsul-bilwyt"))
        // The mixdown runs at the highest rate present, so nothing is
        // resampled downward by default.
        assertEquals(48_000, rec.sampleRate)
    }

    @Test
    fun aSpeakerWhoNeverSpokeIsNotAClip() = runTest {
        val l = line()
        l.handle(offer("s1", "~hapnyl-fotlyx"))
        val a = made.last()
        l.handle(offer("s2", "~ricsul-bilwyt")) // joins, never sends audio

        l.startRecording("~sampel-palnet")
        a.sink!!(pcm(480), 48_000)
        val rec = l.stopRecording()

        // The silent one used to arrive as a pad-only clip: a WAV of
        // pure zeros, uploaded to Whisper and billed, then attributed.
        assertEquals(setOf("~hapnyl-fotlyx"), rec.clips.keys)
    }

    @Test
    fun reTappingASpeakerKeepsWhatWasAlreadyCaptured() = runTest {
        val l = line()
        l.handle(offer("s1", "~hapnyl-fotlyx"))
        val first = made.last()

        l.startRecording("~ricsul-bilwyt")
        first.sink!!(pcm(480), 48_000)
        val before = l.isRecording()

        // Same ship republishes on a new stream — what a network blip
        // does mid-recording. The clip must grow, not restart.
        l.handle(offer("s2", "~hapnyl-fotlyx"))
        made.last().sink!!(pcm(480), 48_000)
        val rec = l.stopRecording()

        assertTrue(before)
        val clip = rec.clips["~hapnyl-fotlyx"]!!
        // Both bursts are 480 frames = 960 bytes; the second tap used to
        // replace the list and throw the first away.
        assertTrue(
            clip.size >= 960 * 2,
            "re-tap should keep the earlier audio, got ${clip.size} bytes",
        )
    }

    @Test
    fun stoppingIsIdempotentAndPublishesTheResult() = runTest {
        val l = line()
        l.handle(offer("s1", "~hapnyl-fotlyx"))
        l.startRecording("~ricsul-bilwyt")
        made.last().sink!!(pcm(480), 48_000)

        assertNull(l.lastRecording.value)
        val first = l.stopRecording()
        // Published, not merely returned, so a recording finalized by
        // teardown still reaches the UI.
        assertNotNull(l.lastRecording.value)
        assertEquals(first.clips.keys, l.lastRecording.value!!.clips.keys)

        val second = l.stopRecording()
        assertTrue(second.isEmpty, "a second stop should not re-emit the clips")
        assertTrue(!l.isRecording())

        l.clearLastRecording()
        assertNull(l.lastRecording.value)
    }

    @Test
    fun startingTwiceDoesNotRestartTheCapture() = runTest {
        val l = line()
        l.handle(offer("s1", "~hapnyl-fotlyx"))
        l.startRecording("~ricsul-bilwyt")
        made.last().sink!!(pcm(480), 48_000)
        l.startRecording("~ricsul-bilwyt") // no-op
        made.last().sink!!(pcm(480), 48_000)

        val clip = l.stopRecording().clips["~hapnyl-fotlyx"]!!
        assertTrue(clip.size >= 960 * 2, "the second start must not clear the clips")
    }
}
