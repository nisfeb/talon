package io.nisfeb.talon.call

import io.nisfeb.talon.comet.isGuestName
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A browser guest's camera and shared screen, seen from Talon: trunk's
 * guest page (wire 17) in a headless browser with fake devices, Talon's
 * real party line and WebRTC on the same line.
 *
 * Needs trunk's fake ships: ~hec hosting party line 'lounge' on a local
 * Galène, ~nym a member, a permanent guest link, Node and a Chromium
 * browser. Opt-in:
 *
 *   GUEST_VIDEO_E2E=1 TRUNK_NYM_CODE=... ./gradlew :composeApp:desktopTest --tests '*GuestVideoE2E*'
 */
class GuestVideoE2ETest {

    private class MemStore : SessionStore {
        private var s: SavedSession? = null
        private var active: String? = null
        override fun all() = listOfNotNull(s)
        override fun active() = s
        override fun activeShip() = active
        override fun save(entry: SavedSession, makeActive: Boolean) {
            s = entry
            if (makeActive) active = entry.ship
        }
        override fun setActive(ship: String) { active = ship }
        override fun remove(ship: String) { s = null; active = null }
        override fun clearAll() { s = null; active = null }
    }

    /** The guest page in a headless browser, one command at a time (guest-driver.mjs). */
    private class Guest(url: String) {
        private val script = File(javaClass.getResource("/guest-driver.mjs")!!.toURI()).path
        private val proc = ProcessBuilder(System.getenv("NODE") ?: "node", script, url, System.getenv("GUEST_BROWSER") ?: "brave")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val out = proc.inputStream.bufferedReader()
        private val inp = proc.outputStream.bufferedWriter()

        fun send(cmd: String) {
            inp.write(cmd + "\n"); inp.flush()
            val answer = out.readLine()
            println("guest: $answer")
            check(answer == "ok ${cmd.substringBefore(' ')}") { "guest $cmd: $answer" }
        }

        fun quit() {
            runCatching { send("quit") }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    @Test
    fun aGuestsCameraAndShareShowInTalon() {
        if (System.getenv("GUEST_VIDEO_E2E") == null) {
            println("GUEST_VIDEO_E2E not set: skipping the guest video test")
            return
        }
        val url = System.getenv("TRUNK_NYM_URL") ?: "http://localhost:8111"
        val code = System.getenv("TRUNK_NYM_CODE") ?: error("set TRUNK_NYM_CODE")
        val host = System.getenv("TRUNK_HOST") ?: "~hec"
        val room = System.getenv("TRUNK_ROOM") ?: "lounge"
        val guestUrl = System.getenv("GUEST_URL") ?: "http://localhost:8110/apps/trunk/guest/groundwire-standup"

        runBlocking {
            val http = createAppHttpClient()
            val session = UrbitSession(http, MemStore())
            val me = session.login(url, code).getOrThrow()
            val party = PartyLine(http, DesktopPeerLinkFactory, videoSupported = true)
            val ctl = CallController(session, DesktopCallEngineProvider)
            ctl.onTicket = { _, t -> party.join(t, me) }
            ctl.start()
            delay(3_000)
            ctl.joinRoom(host, room)
            withTimeout(30_000) { party.state.first { it is PartyState.Live } }
            println("$me is on $host's $room")

            val guest = Guest(guestUrl)
            try {
                guest.send("join Grandma")
                val g = withTimeout(30_000) {
                    party.state.mapNotNull { s ->
                        (s as? PartyState.Live)?.members?.firstOrNull { isGuestName(it.ship) && it.name == "Grandma" }
                    }.first()
                }
                println("the guest is on the roster as ${g.ship}")

                guest.send("camera")
                withTimeout(20_000) { party.videoOn.first { g.ship in it } }
                println("talon-video says the guest's camera is on")

                // Every frame the guest's tile would draw, by size.
                val sizes = ConcurrentLinkedQueue<Pair<Int, Int>>()
                val track = withTimeout(20_000) {
                    var t: dev.onvoid.webrtc.media.video.VideoTrack? = null
                    while (t == null) {
                        t = (party.videoLinkFor(g.ship) as? DesktopPeerLink)?.remoteVideoTrack
                        if (t == null) delay(100)
                    }
                    t
                }
                track.addSink(releasingSink { f -> sizes.add(f.buffer.width to f.buffer.height) })
                suspend fun framesOf(what: String, min: Int = 20, unlike: Pair<Int, Int>? = null): Pair<Int, Int> {
                    val start = sizes.size
                    withTimeout(30_000) {
                        while (sizes.drop(start).count { unlike == null || it != unlike } < min) delay(100)
                    }
                    val size = sizes.drop(start).last()
                    println("$what: ${sizes.size - start} frames, now ${size.first}x${size.second}")
                    return size
                }
                val camera = framesOf("camera")

                guest.send("share")
                val screen = framesOf("screen share", unlike = camera)
                assertTrue(screen != camera, "the share replaced the camera on the same tile")
                assertTrue(g.ship in party.videoOn.value, "still on while sharing")

                guest.send("share")
                framesOf("back to the camera", unlike = screen)

                guest.send("camera")
                withTimeout(20_000) { party.videoOn.first { g.ship !in it } }
                println("talon-video says the guest's video is off")
            } finally {
                guest.quit()
                party.leave()
                ctl.stop()
            }
        }
    }
}
