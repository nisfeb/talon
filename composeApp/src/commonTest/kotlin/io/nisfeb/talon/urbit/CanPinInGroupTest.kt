package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanPinInGroupTest {

    private fun adminGroup(flag: String) = AdminGroup(
        flag = flag,
        title = null,
        description = null,
        image = null,
        cover = null,
        members = emptyList(),
        cordonKind = "shut",
        privacy = null,
        bannedShips = emptySet(),
        invitedTokenByShip = emptyMap(),
        directInvitedShips = emptySet(),
        pendingShips = emptySet(),
        adminSects = emptySet(),
    )

    @Test
    fun `group host can always pin even before admin cache is populated`() {
        // Implicit-host shortcut — without this, host-admins would lose
        // the pin option for the first 10-30s of every session while
        // the bootstrap admin-groups scry is in flight.
        assertTrue(
            canPinInGroup(
                ourPatp = "~zod",
                groupFlag = "~zod/my-group",
                adminGroups = null,
            ),
        )
    }

    @Test
    fun `non-host admin can pin when adminGroups lists their group`() {
        assertTrue(
            canPinInGroup(
                ourPatp = "~bus",
                groupFlag = "~zod/co-op",
                adminGroups = listOf(adminGroup("~zod/co-op")),
            ),
        )
    }

    @Test
    fun `non-host non-admin cannot pin even with admin cache populated`() {
        // The bug we're fixing: previously canPin was just "is this a
        // top-level message in a chat channel" — totally unrelated to
        // permissions. A non-host non-admin would see the option, tap
        // it, and either silently no-op or NACK on the ship.
        //
        // adminGroups is always from the current user's perspective —
        // fetchAdminGroupsLive filters down to groups WE are admin in —
        // so a non-admin's list would never contain the group flag in
        // question. Modelled here as a list of groups we ARE admin in,
        // none of which is ~zod/co-op.
        assertFalse(
            canPinInGroup(
                ourPatp = "~rando",
                groupFlag = "~zod/co-op",
                adminGroups = listOf(adminGroup("~rando/my-other-group")),
            ),
        )
    }

    @Test
    fun `non-host with null admin cache cannot pin`() {
        // Until adminGroupsFlow populates, non-host users get false.
        // Slight regression for non-host admins on first 10-30s after
        // launch; acceptable in exchange for never showing pin to
        // non-admins.
        assertFalse(
            canPinInGroup(
                ourPatp = "~bus",
                groupFlag = "~zod/co-op",
                adminGroups = null,
            ),
        )
    }

    @Test
    fun `null group flag returns false`() {
        // Channel hasn't been linked to its group yet — possible
        // briefly during bootstrap. Hide the affordance rather than
        // potentially mislead.
        assertFalse(
            canPinInGroup(
                ourPatp = "~zod",
                groupFlag = null,
                adminGroups = listOf(adminGroup("~zod/g")),
            ),
        )
    }

    @Test
    fun `empty admin cache treats only host as admin`() {
        // adminGroups = emptyList() (not null) means "we ran the scry
        // and found I'm admin in zero groups". Distinct from null
        // (cache hasn't populated yet). Host shortcut still applies.
        assertTrue(
            canPinInGroup("~zod", "~zod/my", emptyList()),
        )
        assertFalse(
            canPinInGroup("~bus", "~zod/my", emptyList()),
        )
    }
}
