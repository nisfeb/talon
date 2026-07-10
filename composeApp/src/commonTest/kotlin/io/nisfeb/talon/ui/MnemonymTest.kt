package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the mnemonym encoding against the reference implementations:
 * expected nyms below were generated with the mnemonyms repo's python
 * library, and the @p syllable values were cross-checked against
 * urbit-ob (so the tables, the parse, and the encoding are all
 * anchored outside this codebase).
 */
class MnemonymTest {

    @Test
    fun `planet nyms match the reference implementation`() {
        assertEquals(".accept.engulf.relents", Mnemonym.forShip("~sampel-palnet"))
        assertEquals(".misrule.canals.attest", Mnemonym.forShip("~ricsul-bilwyt"))
        assertEquals(".crusade.tattoos.regimes", Mnemonym.forShip("~minder-folden"))
    }

    @Test
    fun `moon and comet nyms match the reference implementation`() {
        // Syllable value 0x046f7f4f046f7f4f, fed to the python lib at
        // strength=64.
        assertEquals(
            ".accept.engulf.relents.ado.unstuck.misspell",
            Mnemonym.forShip("~sampel-palnet-sampel-palnet"),
        )
        assertEquals(
            ".persuades.today.unite.bombard.affirm.depends.hotel.above" +
                ".compares.invests.rebuff.convene",
            Mnemonym.forShip("~racmus-mollen-fallyt-linpex--watres-sibbur-modlux-rinmex"),
        )
    }

    @Test
    fun `official untweaked vectors from the repo pin the core encoding`() {
        // test-vectors.json entries (128-bit, '..' prefix).
        assertEquals(
            "..intrust.confound.detract.defeat.cascades.detracts.obscured" +
                ".restrain.canoe.constructs.constraint.marines",
            Mnemonym.encode(hex("9e885d952ad362caeb4efe34a8e91bd2"), tweaked = false),
        )
        assertEquals("..abducts", Mnemonym.encode(ByteArray(16), tweaked = false))
    }

    @Test
    fun `galaxies stars and non-ships fall through to null`() {
        assertNull(Mnemonym.forShip("~zod"))
        assertNull(Mnemonym.forShip("~marzod"))
        assertNull(Mnemonym.forShip("chat/~zod/general"))
        assertNull(Mnemonym.forShip("~sampel-xxxxxx")) // unknown syllable
        assertNull(Mnemonym.forShip("sampel-palnet")) // no sig
    }

    @Test
    fun `display abridges moons and comets but not planets`() {
        assertEquals(".accept.engulf.relents", Mnemonym.display("~sampel-palnet"))
        assertEquals(
            ".accept...misspell",
            Mnemonym.display("~sampel-palnet-sampel-palnet"),
        )
        assertEquals(
            ".persuades...convene",
            Mnemonym.display("~racmus-mollen-fallyt-linpex--watres-sibbur-modlux-rinmex"),
        )
    }

    @Test
    fun `displayName falls back to mnemonym only when enabled and unnamed`() {
        val named = ContactEntity(
            ship = "~sampel-palnet", nickname = "Sam", bio = null, avatarUrl = null,
        )
        val on = ContactMap(contacts = listOf(named), mnemonymNames = true)
        assertEquals("Sam", on.displayName("~sampel-palnet"))
        assertEquals(".misrule.canals.attest", on.displayName("~ricsul-bilwyt"))
        assertEquals("~marzod", on.displayName("~marzod")) // star keeps @p

        val off = on.copy(mnemonymNames = false)
        assertEquals("~ricsul-bilwyt", off.displayName("~ricsul-bilwyt"))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
