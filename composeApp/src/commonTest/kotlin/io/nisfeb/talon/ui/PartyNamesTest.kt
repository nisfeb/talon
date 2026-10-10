package io.nisfeb.talon.ui

import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.data.ContactEntity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a party line calls us, and the names members give it. */
class PartyNamesTest {
    private val comet = "~foppel-fitdyn-doznux-fithut--somdur-famdev-forpet-daplyd"

    @AfterTest
    fun names() { ShipNames.alwaysPatp.value = false }

    @Test
    fun weGoByOurNicknameElseOurMnemonymElseOurPatp() {
        val contacts = ContactMap(contacts = listOf(ContactEntity("~zod", "Zed", null, null)))
        assertEquals("Zed", partyName(contacts, "~zod"))
        assertEquals(Mnemonym.forShip(comet), partyName(contacts, comet))
        assertEquals("~nec", partyName(contacts, "~nec"))
    }

    @Test
    fun aNameGivenToTheLineFillsInOnlyWhereWeHaveNone() {
        val live = PartyState.Live(
            room = "r",
            members = listOf(PartyMember("1", "~nec", name = "Necco"), PartyMember("2", "~bus", name = "Not Bus")),
            muted = false,
            media = io.nisfeb.talon.call.MediaState.Idle,
        )
        val ours: (String) -> String = { if (it == "~bus") "Bus" else it }
        val named = withLineNames(ours, live)
        assertEquals("Necco", named("~nec"))
        assertEquals("Bus", named("~bus"), "our own name for them wins")
        assertEquals("~wes", named("~wes"))

        ShipNames.alwaysPatp.value = true
        assertEquals("~nec", withLineNames(ours, live)("~nec"), "names set aside means theirs too")
    }

    // Trunk wire 16: someone with no ship, in from an invite link.
    @Test
    fun aGuestIsAlwaysMarkedSoNobodyPassesAsAShip() {
        val live = PartyState.Live(
            room = "r",
            members = listOf(
                PartyMember("1", "guest-3fa9c07b12de", name = "~zod"),
                PartyMember("2", "guest-00000000beef"),
                PartyMember("3", "~nec", name = "Necco"),
            ),
            muted = false,
            media = io.nisfeb.talon.call.MediaState.Idle,
        )
        val ours: (String) -> String = { if (it == "~zod") "Zed" else it }
        val named = withLineNames(ours, live)
        assertEquals("~zod (guest)", named("guest-3fa9c07b12de"), "a typed @p is still a guest")
        assertEquals("guest-00000000beef (guest)", named("guest-00000000beef"), "no name typed: the seat's own id, as trunk's pages show it")
        assertEquals("Necco", named("~nec"))
        assertEquals("Zed", named("~zod"), "the real ~zod is untouched")
        ShipNames.alwaysPatp.value = true
        assertEquals("~zod (guest)", withLineNames(ours, live)("guest-3fa9c07b12de"), "marked with names set aside too")
    }
}
