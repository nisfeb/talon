package io.nisfeb.talon.mail

import io.nisfeb.talon.ui.screens.mailPeople
import kotlin.test.Test
import kotlin.test.assertEquals

class MailPeopleTest {
    private fun row(from: String, vararg people: String) =
        InboxEntry(id = "t", from = from, participants = people.toList())

    @Test
    fun `the latest sender leads, the others follow and we come last`() {
        assertEquals("~nec, ~bus, me", mailPeople(row("~nec", "~bus", "~zod", "~nec"), "~zod") { it })
    }

    @Test
    fun `our own send names the others first`() {
        assertEquals("~nec, me", mailPeople(row("~zod", "~zod", "~nec"), "~zod") { it })
    }

    @Test
    fun `a row with no participants still names its sender`() {
        assertEquals("Nec", mailPeople(row("~nec"), null) { "Nec" })
    }
}
