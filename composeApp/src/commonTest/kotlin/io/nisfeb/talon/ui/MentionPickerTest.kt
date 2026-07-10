package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the @mention matching contract: nickname, @p, and mnemonym are
 *  interchangeable ways to find the same ship. */
class MentionPickerTest {

    private val ships = listOf("~sampel-palnet", "~ricsul-bilwyt", "~marzod")
    private val contacts = ContactMap(
        contacts = listOf(
            ContactEntity(ship = "~sampel-palnet", nickname = "Sam Iam", bio = null, avatarUrl = null),
        ),
        mnemonymNames = true,
    )

    private fun hits(query: String, map: ContactMap = contacts) =
        suggestionsFor(query, map, ships).map { it.ship }

    @Test
    fun `nickname patp and mnemonym all find the same ship`() {
        assertEquals(listOf("~sampel-palnet"), hits("sam iam")) // nick substring
        assertEquals(listOf("~sampel-palnet"), hits("sampel")) // patp prefix
        assertEquals(listOf("~sampel-palnet"), hits("accept")) // nym first word
        assertEquals(listOf("~sampel-palnet"), hits(".accept.eng")) // nym with dots
        assertEquals(listOf("~ricsul-bilwyt"), hits("misrule"))
    }

    @Test
    fun `mnemonym matching is gated on the naming setting`() {
        val off = contacts.copy(mnemonymNames = false)
        assertEquals(emptyList(), hits("accept", off))
        assertEquals(listOf("~sampel-palnet"), hits("sampel", off)) // patp still works
    }

    @Test
    fun `mention token survives the dots a mnemonym query needs`() {
        assertEquals("accept.eng" to 0, detectMentionQuery("@accept.eng", 11))
        assertEquals(".accept" to 4, detectMentionQuery("hey @.accept", 12))
        // Mid-word @ is not a trigger; punctuation still ends the token.
        assertNull(detectMentionQuery("mail@accept", 11))
        assertNull(detectMentionQuery("@acc,ept", 8))
    }
}
