package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every member has to derive the same (host, room) from a channel with
 * no shared state, and the host has to be the ship that actually owns
 * the SFU — so this mapping is load-bearing for authorization, not
 * just for naming.
 */
class PartyLineHostTest {

    @Test
    fun groupChannelMapsToHostAndRoom() {
        assertEquals("~zod" to "lounge", PartyLineHost.roomFor("chat/~zod/lounge"))
        assertEquals(
            "~ricsul-bilwyt" to "general",
            PartyLineHost.roomFor("chat/~ricsul-bilwyt/general"),
        )
    }

    @Test
    fun slashesInTheChannelCollapseToOneRoomName() {
        // The room name becomes a Galène subgroup, where a slash would
        // start a *different* subgroup — and so a different token
        // audience than the host signed.
        assertEquals("~zod" to "team-standup", PartyLineHost.roomFor("chat/~zod/team/standup"))
    }

    @Test
    fun dmsAndClubsHaveNoLine() {
        assertNull(PartyLineHost.roomFor("~litzod"))
        assertNull(PartyLineHost.roomFor("club/0v3.abc"))
        assertNull(PartyLineHost.roomFor("chat/~zod"))
        assertNull(PartyLineHost.roomFor(""))
        // A nest whose host isn't a patp can't name a hosting ship.
        assertNull(PartyLineHost.roomFor("chat/zod/lounge"))
    }
}
