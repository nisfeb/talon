package io.nisfeb.talon.call

import io.nisfeb.talon.util.createAppHttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** The mute-for-call effect mutes the line on every 1:1 call; off a
 *  line that must stay a no-op. A phantom Live here was the iOS
 *  two-bars-no-audio bug. */
class PartyLineIdleMuteTest {
    @Test
    fun mutingAnIdleLineLeavesItIdle() {
        val line = PartyLine(createAppHttpClient(), DesktopPeerLinkFactory)
        line.setMuted(true)
        line.setMuted(false)
        assertTrue(line.state.value is PartyState.Idle, "state was ${line.state.value}")
    }
}
