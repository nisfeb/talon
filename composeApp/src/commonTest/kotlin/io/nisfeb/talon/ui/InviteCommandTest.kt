package io.nisfeb.talon.ui

import io.nisfeb.talon.data.GroupEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteCommandTest {
    private val groups = listOf(
        GroupEntity("~zod/urbit-dev", "Urbit Dev", null),
        GroupEntity("~nec/tlon-studio", "Tlon Studio", null),
        GroupEntity("~bus/books", "Book Club", null),
    )

    @Test
    fun `the first argument picks a group and the second a ship outside a DM`() {
        assertEquals(InviteArg.Group("", 8), detectInviteArg("/invite ", 8, inDm = false))
        assertEquals(InviteArg.Group("tl", 8), detectInviteArg("/invite tl", 10, inDm = false))
        assertEquals(InviteArg.Ship("za", 23), detectInviteArg("/invite ~zod/urbit-dev ~za", 26, inDm = false))
        assertNull(detectInviteArg("/invite ~zod/urbit-dev ", 23, inDm = true))
        assertNull(detectInviteArg("/nick bob", 9, inDm = false))
    }

    @Test
    fun `groups match fuzzily and best first`() {
        assertEquals(listOf("~nec/tlon-studio"), matchGroups("tlon", groups).map { it.flag })
        assertEquals("~bus/books", matchGroups("bkcl", groups).first().flag)
        assertEquals(listOf("Book Club", "Tlon Studio", "Urbit Dev"), matchGroups("", groups).map { it.title })
    }

    @Test
    fun `a finished invite names one group and a ship`() {
        assertEquals(
            InviteParse.Ok("~nec/tlon-studio", "~sampel-palnet"),
            parseInvite("/invite ~nec/tlon-studio ~sampel-palnet", null, groups),
        )
        assertEquals(InviteParse.Ok("~nec/tlon-studio", "~bus"), parseInvite("/invite tlon", "~bus", groups))
        assertTrue(parseInvite("/invite ~nec/tlon-studio", null, groups) is InviteParse.Problem)
        assertTrue(parseInvite("/invite nothing ~zod", null, groups) is InviteParse.Problem)
        assertTrue(parseInvite("/invite ~nec/tlon-studio z0d", null, groups) is InviteParse.Problem)
    }

    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"

    @Test
    fun `invite takes the names every other box takes`() {
        // The twelve-word name, whose words the split used to lose.
        val nym = Mnemonym.forShip(comet)!!
        assertEquals(InviteParse.Ok("~nec/tlon-studio", comet), parseInvite("/invite tlon $nym", null, groups))
        // A short name or a nickname, for somebody already known.
        assertEquals(
            InviteParse.Ok("~nec/tlon-studio", comet),
            parseInvite("/invite tlon ..admire...attune", null, groups, known = listOf(comet)),
        )
        assertEquals(
            InviteParse.Ok("~nec/tlon-studio", comet),
            parseInvite("/invite tlon Sam", null, groups, known = listOf(comet), nicknameOf = { if (it == comet) "Sam" else null }),
        )
        // A name for nobody says so, in the resolver's words.
        val no = parseInvite("/invite tlon nobody", null, groups) as InviteParse.Problem
        assertEquals("No ship goes by that name.", no.message)
    }

    @Test
    fun `a refused invite says what the ship said`() {
        val nacked = io.nisfeb.talon.urbit.PokeNacked("groups", "group-action-4", "no-such-ship")
        assertEquals("Your ship refused the invite: no-such-ship", inviteFailure(nacked))
        val refused = io.nisfeb.talon.urbit.PokeNacked("groups", "group-action-4", "permission denied")
        assertTrue("only admins invite" in inviteFailure(refused), inviteFailure(refused))
        assertTrue("no answer from your ship" in inviteFailure(RuntimeException()), inviteFailure(RuntimeException()))
    }

    // Silence is neither a yes nor a no, and used to be read as yes.
    // What the owner needs is to check before inviting again: an
    // invite that did go, sent twice, is not the harm, but an owner
    // told it went and looking at the wrong ship is.
    @Test
    fun `an invite the ship never answered is not called sent`() {
        val quiet = inviteFailure(io.nisfeb.talon.urbit.PokeUnacked("groups", "group-action-4"))
        assertTrue("did not confirm" in quiet, quiet)
        assertTrue("may still have gone" in quiet, quiet)
        assertTrue("check the group's members" in quiet, quiet)
        // Not worded as a refusal: nothing refused it.
        assertTrue("refused" !in quiet, quiet)
    }
}
