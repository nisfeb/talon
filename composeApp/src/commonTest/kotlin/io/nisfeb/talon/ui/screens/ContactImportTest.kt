package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactImportTest {
    private fun c(ship: String, nick: String? = null) = ContactEntity(ship, nick, null, null)

    @Test
    fun `everyone known and not in the book is offered, never ourselves, named first`() {
        val all = listOf(c("~zod"), c("~nec", "Nec"), c("~bus"), c("~wes", "Alice"), c("~nec", "Nec"))
        val got = importCandidates(all, book = setOf("~bus"), ourShip = "~zod").map { it.ship }
        assertEquals(listOf("~wes", "~nec"), got)
    }
}
