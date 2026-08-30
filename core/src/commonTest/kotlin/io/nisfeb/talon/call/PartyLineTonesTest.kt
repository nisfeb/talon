package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who gets a chime, and who doesn't.
 *
 * The interesting case is joining a room that already has people in
 * it: Galène replays the existing roster as a run of user-add
 * messages, and treating those as arrivals means walking into a busy
 * line to a burst of chimes.
 */
class PartyLineTonesTest {

    private class FakeLink : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    /** Records tone lengths; join and leave differ only by direction,
     *  so identity is by content. */
    private class Recorder : CallSoundPlayer {
        val played = mutableListOf<ByteArray>()
        val streams = mutableListOf<ToneStream>()
        override fun play(pcm: ByteArray, stream: ToneStream) {
            played += pcm
            streams += stream
        }
        override fun loop(pcm: ByteArray, gapMs: Int, stream: ToneStream) = Unit
        override fun stopLoop() = Unit
        val joins get() = played.count { it.contentEquals(CallSounds.joined()) }
        val leaves get() = played.count { it.contentEquals(CallSounds.left()) }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun add(id: String, who: String) = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
        """{"type":"user","kind":"add","id":"$id","username":"$who"}""",
    )
    private fun del(id: String) = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
        """{"type":"user","kind":"delete","id":"$id"}""",
    )
    private fun joined() = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
        """{"type":"joined","rtcConfiguration":{"iceServers":[]}}""",
    )

    private fun line(rec: Recorder) =
        PartyLine(HttpClient(), links = { _, _ -> FakeLink() }, sounds = rec)

    @Test
    fun walkingIntoAFullRoomIsSilent() = runTest {
        val rec = Recorder()
        val l = line(rec)
        // The roster arrives before we have finished joining.
        l.handle(add("a", "~ricsul-bilwyt"))
        l.handle(add("b", "~hapnyl-fotlyx"))
        assertEquals(0, rec.joins, "an existing roster is not a series of arrivals")
    }

    @Test
    fun someoneArrivingAfterUsChimes() = runTest {
        val rec = Recorder()
        val l = line(rec)
        l.handle(add("a", "~ricsul-bilwyt"))
        l.onJoined(joined())
        l.handle(add("b", "~hapnyl-fotlyx"))
        assertEquals(1, rec.joins)
    }

    @Test
    fun leavingChimesTheOtherWay() = runTest {
        val rec = Recorder()
        val l = line(rec)
        l.onJoined(joined())
        l.handle(add("b", "~hapnyl-fotlyx"))
        l.handle(del("b"))
        assertEquals(1, rec.joins)
        assertEquals(1, rec.leaves)
        assertTrue(
            !CallSounds.joined().contentEquals(CallSounds.left()),
            "the two tones must be distinguishable",
        )
    }

    @Test
    fun `join and leave chimes ride the call stream, not the ringer`() = runTest {
        // On Android the silent switch mutes the ringer stream. A
        // party-line chime belongs to a call the user is already in,
        // so silencing the ringer must not silence it.
        val rec = Recorder()
        val l = line(rec)
        l.onJoined(joined())
        l.handle(add("b", "~hapnyl-fotlyx"))
        l.handle(del("b"))
        assertEquals(2, rec.streams.size, "expected an arrival and a departure")
        assertTrue(
            rec.streams.all { it == ToneStream.Call },
            "a chime went to ${rec.streams.distinct()} instead of the call stream",
        )
    }
}
