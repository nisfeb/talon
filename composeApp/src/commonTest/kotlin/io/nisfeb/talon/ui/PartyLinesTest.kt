package io.nisfeb.talon.ui

import io.nisfeb.talon.call.PartyInvite
import io.nisfeb.talon.call.PartyRoom
import io.nisfeb.talon.data.GroupEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PartyLinesTest {
    private val names = mapOf("~zod" to "Zod", "~nec" to "Nec")
    private fun nameFor(s: String) = names[s] ?: s

    @Test
    fun rollCallPrefersNamesAndKeepsTheHostCount() {
        assertEquals("Nobody is on the party line right now", partyRollCall(0, emptyList(), ::nameFor))
        assertEquals("1 on the party line", partyRollCall(1, emptyList(), ::nameFor))
        assertEquals("3 on the party line", partyRollCall(3, emptyList(), ::nameFor))
        assertEquals("2 on the party line: Nec, Zod", partyRollCall(0, listOf("~zod", "~nec"), ::nameFor))
        // an old member that does not heartbeat: count floors at the roster
        assertEquals("3 on the party line: Nec, Zod", partyRollCall(3, listOf("~zod", "~nec"), ::nameFor))
    }

    @Test
    fun rowsMatchRoomsToGroupsByFlagAndDedupeInvites() {
        val groups = listOf(
            GroupEntity(flag = "~zod/nisfeb-software", title = "Nisfeb Software", image = null),
            GroupEntity(flag = "~nec/other", title = "Other", image = null),
        )
        val rooms = mapOf(
            "~zod/nisfeb-software" to PartyRoom("nisfeb-software", "raw title", true, "", false),
        )
        val invites = mapOf(
            "~zod/nisfeb-software" to PartyInvite("~zod", "nisfeb-software", "dup", true, ""),
            "~nec/other" to PartyInvite("~nec", "other", "Other line", true, ""),
            "~bud/stranger" to PartyInvite("~bud", "stranger", "Stranger", true, ""),
        )
        val rows = partyLineRows(rooms, invites, groups)
        assertEquals(listOf("~zod/nisfeb-software", "~nec/other", "~bud/stranger"), rows.map { it.key })
        assertEquals("Nisfeb Software", rows[0].title)
        assertEquals("~zod/nisfeb-software", rows[0].groupFlag)
        assertEquals("~nec/other", rows[1].groupFlag)
        assertEquals(null, rows[2].groupFlag)
    }

    @Test
    fun `pip lights up from either presence source`() {
        val rows = listOf(
            PartyLineRow("~zod", "a", "A", null),
            PartyLineRow("~nec", "b", "B", null),
        )
        assertFalse(anyPartyLineOccupied(rows, emptyMap(), emptyMap()))
        // an old host answers a count without names
        assertTrue(anyPartyLineOccupied(rows, mapOf("~nec/b" to 2), emptyMap()))
        // a wire-8 host answers names; a zero count alongside them loses
        assertTrue(anyPartyLineOccupied(rows, mapOf("~zod/a" to 0), mapOf("~zod/a" to setOf("~bud"))))
        // stale entries for lines we no longer know about don't count
        assertFalse(anyPartyLineOccupied(rows, mapOf("~bus/gone" to 9), emptyMap()))
    }
}
