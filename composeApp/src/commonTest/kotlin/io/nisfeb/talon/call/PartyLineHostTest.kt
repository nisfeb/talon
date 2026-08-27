package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every member has to derive the same (host, room) from a group with no
 * shared state, and the host has to be the ship that actually owns the
 * SFU — so this mapping is load-bearing for authorization, not just for
 * naming.
 *
 * The room is derived from the *group* flag, not a channel nest: one
 * line per group, so every channel in it lands on the same room and a
 * group's admins turn it on once.
 */
class PartyLineHostTest {

    @Test
    fun groupFlagMapsToHostAndRoom() {
        assertEquals("~zod" to "lounge", PartyLineHost.roomForGroup("~zod/lounge"))
        assertEquals(
            "~ricsul-bilwyt" to "networked-subject",
            PartyLineHost.roomForGroup("~ricsul-bilwyt/networked-subject"),
        )
    }

    @Test
    fun everyChannelInAGroupSharesOneRoom() {
        // The whole point of keying on the group: #general and #random
        // are the same line, so an admin enables it once.
        val a = PartyLineHost.roomForGroup("~zod/dev")
        val b = PartyLineHost.roomForGroup("~zod/dev")
        assertEquals(a, b)
        assertEquals("~zod" to "dev", a)
    }

    @Test
    fun slashesInTheFlagCollapseToOneRoomName() {
        // The room name becomes a Galène subgroup, where a slash would
        // start a *different* subgroup — and so a different token
        // audience than the host signed.
        assertEquals("~zod" to "team-standup", PartyLineHost.roomForGroup("~zod/team/standup"))
    }

    @Test
    fun malformedFlagsHaveNoLine() {
        assertNull(PartyLineHost.roomForGroup("~litzod"))
        assertNull(PartyLineHost.roomForGroup(""))
        assertNull(PartyLineHost.roomForGroup("~zod/"))
        // A flag whose host isn't a patp can't name a hosting ship.
        assertNull(PartyLineHost.roomForGroup("zod/lounge"))
    }
}
