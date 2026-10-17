package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/** Who the Home statuses widget can pin, which Android never offered. */
class HomePinCandidatesTest {
    private fun posted(ship: String) = ContactEntity(ship, nickname = null, bio = null, avatarUrl = null, status = "out")

    @Test
    fun `everyone who posted, and the pinned who went quiet, by name and once each, but never me`() {
        val got = homePinCandidates(
            statuses = listOf(posted("~bus"), posted("~zod"), posted("~nec")),
            pinned = listOf("~nec", "~wes"),
            me = "~zod",
            nameFor = { if (it == "~bus") "Bus" else it },
        )
        assertEquals(listOf("~bus" to "Bus", "~nec" to "~nec", "~wes" to "~wes"), got)
    }

    @Test
    fun `nobody posted and nobody pinned is nobody to offer`() {
        assertEquals(emptyList(), homePinCandidates(emptyList(), emptyList(), me = null) { it })
    }
}
