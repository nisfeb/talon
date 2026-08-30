package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which volume each call tone comes out of.
 *
 * Reported bug: with the ringer muted and media volume high, placing
 * a call played no ringback at all. The cause was one
 * AudioAttributes usage — VOICE_COMMUNICATION_SIGNALLING, which reads
 * like exactly the right choice and maps to the legacy STREAM_DTMF,
 * which the silent switch mutes.
 *
 * The mapping is now per-tone, and both directions of it are easy to
 * get backwards: send the ringback to the ringer stream and it
 * disappears for anyone with a silent phone; send the incoming ring
 * to the call stream and a silenced phone rings out loud. Neither
 * failure is visible in code review, so they are pinned here.
 */
class ToneStreamTest {

    private class Store(private val entry: SavedSession) : SessionStore {
        override fun all() = listOf(entry)
        override fun active() = entry
        override fun activeShip() = entry.ship
        override fun save(entry: SavedSession, makeActive: Boolean) {}
        override fun setActive(ship: String) {}
        override fun remove(ship: String) {}
        override fun clearAll() {}
    }

    /** Records what stream each looping tone was asked for. */
    private class Recorder : CallSoundPlayer {
        val loops = mutableListOf<Pair<ByteArray, ToneStream>>()
        override fun play(pcm: ByteArray, stream: ToneStream) {}
        override fun loop(pcm: ByteArray, gapMs: Int, stream: ToneStream) {
            loops += pcm to stream
        }
        override fun stopLoop() {}
    }

    @Test
    fun `an incoming ring honours the silent switch`() = runBlocking {
        val sse = buildString {
            append("id: 1\ndata: {\"id\":1,\"response\":\"poke\",\"ok\":true}\n\n")
            append(
                "id: 2\ndata: " +
                    """{"id":2,"response":"diff","json":""" +
                    """{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}}""" +
                    "\n\n",
            )
        }
        val engine = MockEngine { req ->
            when {
                req.method.value == "PUT" -> respond("", HttpStatusCode.NoContent)
                req.url.encodedPath.startsWith("/~/scry") ->
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(
                    sse, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }
        }
        val ship = "~nec"
        val session = UrbitSession(
            HttpClient(engine),
            Store(
                SavedSession(
                    shipUrl = "https://ship.test",
                    ship = ship,
                    cookieName = "urbauth-" + ship,
                    cookieValue = "0v1",
                    cookieDomain = "ship.test",
                ),
            ),
        )
        session.tryRestore()
        val rec = Recorder()
        val controller = CallController(
            session,
            CallEngineProvider { error("no media needed to ring") },
            sounds = rec,
        )
        try {
            controller.start()
            withTimeout(10_000) {
                controller.state.first { it is CallUiState.Incoming }
            }
            // The tone loop is started from the same state collector,
            // so it has run by the time the state is observable.
            withTimeout(5_000) {
                while (rec.loops.isEmpty()) kotlinx.coroutines.delay(20)
            }
            val (pcm, stream) = rec.loops.first()
            assertTrue(
                pcm.contentEquals(CallSounds.incoming()),
                "expected the incoming ring, got some other tone",
            )
            assertEquals(
                ToneStream.Ringer,
                stream,
                "an incoming ring must be silenceable — that is what the switch is for",
            )
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `ringback is a call tone, so a silent phone still hears it`() {
        // The bug itself: this is the tone that vanished. It is the
        // default for every tone that is not an incoming ring, so the
        // check is that the default has not drifted.
        val rec = Recorder()
        rec.loop(CallSounds.ringback(), 0)
        assertEquals(ToneStream.Call, rec.loops.single().second)
    }
}
