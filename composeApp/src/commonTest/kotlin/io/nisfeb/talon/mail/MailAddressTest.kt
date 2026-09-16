package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailAddressTest {

    @Test
    fun `the four ship classes are ships`() {
        assertTrue(isShip("~zod"), "galaxy")
        assertTrue(isShip("~marzod"), "star")
        assertTrue(isShip("~sampel-palnet"), "planet")
        assertTrue(isShip("~doznec-marzod-sampel-palnet"), "moon")
        assertTrue(
            isShip("~doznec-marzod-sampel-palnet-doznec-marzod-sampel-palnet"),
            "comet",
        )
    }

    @Test
    fun `an even group count is not the rule`() {
        // Six, ten and twelve are all even and none of them names a ship.
        assertFalse(isShip("~sampel-palnet-sampel-palnet-sampel-palnet"))
    }

    @Test
    fun `a galaxy is the whole name or nothing`() {
        // Two valid counts and two legal lengths, and still not a name @p
        // ever renders. This is the case the length rule alone admits.
        assertFalse(isShip("~zod-zod"))
    }

    @Test
    fun `shape errors are refused`() {
        assertFalse(isShip("zod"), "no sigil")
        assertFalse(isShip("~"), "sigil alone")
        assertFalse(isShip("~sampel-"), "trailing separator")
        assertFalse(isShip("~SAMPEL-PALNET"), "not lowercase")
        assertFalse(isShip("~samp3l-palnet"), "not letters")
        assertFalse(isShip("~sampel-paln"), "wrong syllable length")
    }

    @Test
    fun `a recipient field keeps what it could not read`() {
        val (good, bad) = parseRecipients("~zod, sampel-palnet  nonsense")
        assertEquals(listOf("~zod", "~sampel-palnet"), good)
        assertEquals(listOf("nonsense"), bad)
    }

    @Test
    fun `a recipient named twice is one recipient`() {
        val (good, _) = parseRecipients("~zod ~zod")
        assertEquals(listOf("~zod"), good)
    }

    @Test
    fun `an empty field is neither good nor bad`() {
        val (good, bad) = parseRecipients("  ,  ")
        assertTrue(good.isEmpty())
        assertTrue(bad.isEmpty())
    }
}
