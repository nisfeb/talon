package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The legacy-room binder must bind exactly the group whose flag
 * derives the room's name for members' clients — same derivation as
 * [PartyLineHost.roomForGroup] — and must refuse to guess.
 */
class LegacyBindMatchTest {

    @Test
    fun bindsTheGroupWhoseSlugIsTheRoomName() {
        assertEquals(
            "~hodler-lorfeb/v769287",
            PartyLineHost.legacyFlagFor(
                roomName = "v769287",
                ourShip = "~hodler-lorfeb",
                groupFlags = listOf(
                    "~hodler-lorfeb/v769287",
                    "~hodler-lorfeb/v15qagkt",
                    "~darduc-mitfen/tradcatholic",
                ),
            ),
        )
    }

    @Test
    fun neverBindsAcrossHosts() {
        // A group we merely belong to, hosted elsewhere, must not
        // claim a room we host that happens to share its slug.
        assertNull(
            PartyLineHost.legacyFlagFor(
                roomName = "tradcatholic",
                ourShip = "~hodler-lorfeb",
                groupFlags = listOf("~darduc-mitfen/tradcatholic"),
            ),
        )
    }

    @Test
    fun ambiguityMeansHandsOff() {
        // Can't happen with real flags (host+slug is unique), but the
        // matcher must not guess if it ever does.
        assertNull(
            PartyLineHost.legacyFlagFor(
                roomName = "line",
                ourShip = "~zod",
                groupFlags = listOf("~zod/line", "~zod/line"),
            ),
        )
    }

    @Test
    fun noMatchMeansHandsOff() {
        assertNull(
            PartyLineHost.legacyFlagFor(
                roomName = "handmade-room",
                ourShip = "~zod",
                groupFlags = listOf("~zod/some-group"),
            ),
        )
    }
}
