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

    // A guest on the line, in the /party note and the party-lines list (trunk wire 16).
    @Test
    fun aGuestInTheRollCallIsNamedAsAGuest() {
        val live = io.nisfeb.talon.call.PartyState.Live(
            room = "lounge",
            members = listOf(
                io.nisfeb.talon.call.PartyMember("1", "guest-9bc96e96826597bd93a19ee8", name = "Grandma"),
                io.nisfeb.talon.call.PartyMember("2", "~nec"),
            ),
            muted = false,
            media = io.nisfeb.talon.call.MediaState.Idle,
        )
        assertEquals(
            "2 on the party line: Grandma (guest), Nec",
            partyRollCall(2, live.members.map { it.ship }, withLineNames({ names[it] ?: it }, live)),
        )
    }

    // ---- The meeting's chat pane ----

    @Test
    fun `a line's group is the one its room is bound to, else the one whose flag names it`() {
        val groups = listOf(
            GroupEntity(flag = "~zod/nisfeb-software", title = "Nisfeb Software", image = null),
            GroupEntity(flag = "~nec/other", title = "Other", image = null),
        )
        val bound = mapOf("~zod/renamed" to PartyRoom("renamed", "t", true, "", false, groupFlag = "~nec/other"))
        assertEquals("~nec/other", lineGroupFlag("~zod/renamed", bound, groups), "bound wins over the name")
        assertEquals("~zod/nisfeb-software", lineGroupFlag("~zod/nisfeb-software", emptyMap(), groups), "a member's line, by its name")
        assertEquals(null, lineGroupFlag("~nec/nisfeb-software", emptyMap(), groups), "the host is part of the name")
        assertEquals(null, lineGroupFlag("~bud/stranger", emptyMap(), groups))
    }

    @Test
    fun `the meeting's chat opens on the open chat, else the line's latest, else its first`() {
        val channels = listOf("chat/~zod/general", "chat/~zod/random")
        assertEquals("chat/~zod/random", meetingChatTarget("chat/~zod/random", channels, listOf("chat/~zod/general")))
        assertEquals("chat/~zod/random", meetingChatTarget("~bus", channels, listOf("~bus", "chat/~zod/random", "chat/~zod/general")))
        assertEquals("chat/~zod/general", meetingChatTarget(null, channels, listOf("~bus")))
        assertEquals(null, meetingChatTarget("~bus", emptyList(), listOf("~bus")))
    }

    @Test
    fun `only a chat opens beside the meeting`() {
        assertTrue(isChatScreenWhom("~bus"))
        assertTrue(isChatScreenWhom("chat/~zod/general"))
        assertFalse(isChatScreenWhom("diary/~zod/notes"))
        assertFalse(isChatScreenWhom("heap/~zod/pics"))
        assertFalse(isChatScreenWhom("notes/~zod/n"))
    }
}
