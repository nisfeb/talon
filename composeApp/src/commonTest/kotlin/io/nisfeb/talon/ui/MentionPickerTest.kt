package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the @mention matching contract: nickname, @p, and mnemonym are
 *  interchangeable ways to find the same ship. */
class MentionPickerTest {

    /** Two comets (the only ships with a nym) and a star. */
    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"
    private val other = "~racmus-mollen-fallyt-linpex--watres-sibbur-modlux-rinmex"
    private val ships = listOf(comet, other, "~marzod")
    private val contacts = ContactMap(
        contacts = listOf(
            ContactEntity(ship = comet, nickname = "Sam Iam", bio = null, avatarUrl = null),
        ),
    )

    private fun hits(query: String, map: ContactMap = contacts) =
        suggestionsFor(query, map, ships).map { it.ship }

    @Test
    fun `nickname patp and mnemonym all find the same ship`() {
        assertEquals(listOf(comet), hits("sam iam")) // nick substring
        assertEquals(listOf(comet), hits("doznec")) // patp prefix
        assertEquals(listOf(comet), hits("admire")) // nym first word
        assertEquals(listOf(comet), hits("..admire.ev")) // nym with dots
        assertEquals(listOf(other), hits("portrays"))
    }

    @Test
    fun `a ship with no nym is still found by its patp`() {
        assertEquals(listOf("~marzod"), hits("marzod"))
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
