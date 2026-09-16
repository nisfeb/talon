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
}
