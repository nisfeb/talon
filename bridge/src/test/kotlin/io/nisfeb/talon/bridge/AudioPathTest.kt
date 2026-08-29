package io.nisfeb.talon.bridge

import io.nisfeb.talon.call.MediaState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole audio path, over real WebRTC: a WAV goes in one end and
 * comes out of a peer connection at the other.
 *
 * Worth the five seconds it costs, because this is precisely the
 * part that reasoning got wrong. An AudioDeviceModule's audio sink
 * and audio source look like the injection points and are not — they
 * stop firing once a PeerConnectionFactory owns the module, so a
 * bridge wired that way connects, negotiates, reports itself healthy
 * and transmits pure silence. Nothing short of moving real samples
 * across a real peer connection would have caught it.
 *
 * Only the media half is exercised. The ship login, the ticket and
 * the Galène join are the party line's, tested where they live.
 */
class AudioPathTest {

    @Test
    fun aFilePlayedIntoALinkIsHeardAtTheOtherEnd() = runBlocking {
        val play = File.createTempFile("bridge-in", ".wav").apply { deleteOnExit() }
        val recorded = File.createTempFile("bridge-out", ".wav").apply { deleteOnExit() }

        // A second of 440Hz at what WebRTC deals in, so nothing is
        // resampled and a silent result can only mean a broken path.
        val format = PcmFormat(48_000, 1)
        WavPcmSink(play).apply {
            val pcm = ByteBuffer.allocate(48_000 * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 48_000) {
                pcm.putShort((sin(2 * PI * 440.0 * i / 48_000) * 12000).toInt().toShort())
            }
            write(pcm.array(), 48_000, format)
            close()
        }

        val audio = BridgeAudio(
            source = WavPcmSource(play, loop = true),
            sink = WavPcmSink(recorded),
        )
        audio.start()

        // Two links from the production factory, wired to each other
        // the way Galène wires them: the sender offers, the receiver
        // answers, candidates trickle both ways.
        val up = audio.peerLinks.create(emptyList(), sendAudio = true)
        val down = audio.peerLinks.create(emptyList(), sendAudio = false)
        try {
            up.onLocalCandidate { down.addRemoteCandidate(it) }
            down.onLocalCandidate { up.addRemoteCandidate(it) }

            val offer = up.offer()
            up.applyAnswer(down.answerTo(offer))

            val connected = withTimeoutOrNull(20_000) {
                while (up.state.value != MediaState.Live || down.state.value != MediaState.Live) {
                    delay(100)
                }
                true
            }
            assertTrue(connected == true, "the links never connected: ${up.state.value} / ${down.state.value}")

            // Long enough for the pump to move a few hundred slabs.
            delay(3_000)
        } finally {
            up.close()
            down.close()
            audio.close()
        }

        val heard = WavPcmSource(recorded)
        assertTrue(heard.frames > 4_800, "recorded only ${heard.frames} frames — under 100ms")

        val buf = ByteArray(480 * format.bytesPerFrame)
        var peak = 0
        var slabs = 0
        while (true) {
            val n = heard.read(buf, 480, format)
            if (n == 0) break
            slabs++
            val s = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until n) peak = maxOf(peak, abs(s.get(i).toInt()))
        }
        assertTrue(
            peak > 2_000,
            "the line was silent (peak $peak over $slabs slabs) — audio never crossed the link",
        )
    }
}
