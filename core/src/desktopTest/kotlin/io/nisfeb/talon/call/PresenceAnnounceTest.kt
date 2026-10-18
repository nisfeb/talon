package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wire 9 announces %on-line and never sends %present unasked, so the
 * roster it carries has to move the count too. It did not, and a count
 * left over from the last ask outlived the people it counted: the
 * party tab's dot stayed lit after a line emptied until some unrelated
 * surface happened to ask again. Shipped in 1.7.7, caught in the field.
 */
class PresenceAnnounceTest {

    private fun controller(h: TrunkHarness) = CallController(
        h.session,
        CallEngineProvider { error("no media needed for presence") },
    )

    @Test
    fun `an emptied roster zeroes the count`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h)
        try {
            c.start()
            h.awaitConnected()
            // Someone asked once and the host answered a count.
            h.emitFact("""{"present":{"from":"~zod","name":"lounge","n":"2"}}""")
            h.await { c.presence.value["~zod/lounge"] == 2 }

            // The last of them leaves. The host announces the roster,
            // and nothing else, exactly as it does in the field.
            h.emitFact("""{"on-line":{"from":"~zod","name":"lounge","who":[]}}""")
            h.await {
                val seen = c.onLine.value
                "~zod/lounge" in seen && seen.getValue("~zod/lounge").isEmpty()
            }

            assertEquals(0, c.presence.value["~zod/lounge"], "count must follow the roster down")
        } finally {
            c.stop()
        }
    }

    @Test
    fun `an announced roster sets the count without an ask`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h)
        try {
            c.start()
            h.awaitConnected()
            h.emitFact(
                """{"on-line":{"from":"~zod","name":"lounge","who":["~bud","~wes"]}}""",
            )
            h.await { c.presence.value["~zod/lounge"] == 2 }
            assertEquals(setOf("~bud", "~wes"), c.onLine.value["~zod/lounge"])
        } finally {
            c.stop()
        }
    }

    private fun sent(h: TrunkHarness, kind: String, host: String) =
        h.putsSnapshot().count { it.contains("\"$kind\":{\"host\":\"$host\",\"name\":\"lounge\"}") }

    // Review B5-15: every 30 s beat also asked the occupancy, of a host
    // that sends the count with each roster change. Asked on the first
    // beat and on leave only there; a host that does not push is still
    // asked every beat.
    @Test
    fun `a host that pushes its roster is asked the count on the first beat and on leave`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { error("no media") }, presenceHeartbeatMs = 100)
        val line = kotlinx.coroutines.flow.MutableStateFlow<PartyState>(PartyState.Connecting("lounge"))
        try {
            c.start()
            h.awaitConnected()
            h.emitFact("""{"on-line":{"from":"~zod","name":"lounge","who":["~bud"]}}""")
            h.await { c.announces("~zod") }
            c.beginPresenceAnnounce("~zod", "lounge", line)
            h.await { sent(h, "enter-room", "~zod") >= 4 }
            assertEquals(1, sent(h, "occupancy-of", "~zod"), "the first beat only")
            line.value = PartyState.Idle
            h.await { sent(h, "leave-room", "~zod") == 1 }
            h.await { sent(h, "occupancy-of", "~zod") == 2 }

            // An older host, which never pushes, is asked every beat.
            line.value = PartyState.Connecting("lounge")
            c.beginPresenceAnnounce("~bus", "lounge", line)
            h.await { sent(h, "enter-room", "~bus") >= 3 }
            kotlin.test.assertTrue(sent(h, "occupancy-of", "~bus") >= 3)
        } finally {
            c.stop()
        }
    }
}
