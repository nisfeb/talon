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
}
