package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the mnemonym encoding against the reference implementation: the
 * nyms below came out of the mnemonyms repo's python library, and the
 * @p syllable values were cross-checked against urbit-ob, so the
 * tables, the parse and the encoding are all anchored outside this
 * codebase. MnemonymVectorsTest pins the same encoder against the
 * repo's own published vectors.
 */
class MnemonymTest {

    /** ~racmus…, whose sixteen syllables spell b85d43c6…1ca5. */
    private val comet = "~racmus-mollen-fallyt-linpex--watres-sibbur-modlux-rinmex"

    /** Bytes 00..0f, as a comet. */
    private val counting = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"

    @Test
    fun `comet nyms match the reference implementation`() {
        assertEquals(
            "..portrays.transcribe.unkempt.boutique.affixed.desire.infer" +
                ".abreast.comprise.maintains.recluse.cuirass",
            Mnemonym.forShip(comet),
        )
        assertEquals(
            "..admire.evince.admire.deface.absurd.auteurs.avert.affix" +
                ".dethroned.connects.attune",
            Mnemonym.forShip(counting),
        )
    }

    @Test
    fun `only comets get a nym`() {
        // A planet's and a moon's @p is ob-scrambled: the syllables
        // spell no key, so a nym built from one would read like the
        // real thing and mean nothing. Naming those takes their public
        // key, which a name lookup cannot go and fetch.
        assertNull(Mnemonym.forShip("~sampel-palnet"))
        assertNull(Mnemonym.forShip("~ricsul-bilwyt"))
        assertNull(Mnemonym.forShip("~sampel-palnet-sampel-palnet"))
        assertNull(Mnemonym.forShip("~zod"))
        assertNull(Mnemonym.forShip("~marzod"))
    }

    @Test
    fun `non-ships fall through to null`() {
        assertNull(Mnemonym.forShip("chat/~zod/general"))
        assertNull(Mnemonym.forShip("~sampel-xxxxxx"))
        assertNull(Mnemonym.forShip("sampel-palnet"))
        assertNull(Mnemonym.forShip(""))
    }

    @Test
    fun `display is the scheme's two-word abridgement`() {
        assertEquals("..portrays...cuirass", Mnemonym.display(comet))
        assertEquals("..admire...attune", Mnemonym.display(counting))
        assertNull(Mnemonym.display("~sampel-palnet"))
    }

    @Test
    fun `a nym already short enough is left whole`() {
        // Leading zero words drop, so a small value can come out under
        // the two words an abridgement would leave.
        assertEquals("..abducts", Mnemonym.encode(ByteArray(16), tweaked = false))
    }

    @Test
    fun `displayName prefers a nickname, then the nym, then the at-p`() {
        val named = ContactEntity(
            ship = comet, nickname = "Sam", bio = null, avatarUrl = null,
        )
        val map = ContactMap(contacts = listOf(named))
        assertEquals("Sam", map.displayName(comet))
        assertEquals("..admire...attune", map.displayName(counting))
        // A planet has no nym now, so it keeps its @p.
        assertEquals("~ricsul-bilwyt", map.displayName("~ricsul-bilwyt"))
        assertEquals("~marzod", map.displayName("~marzod"))
    }
}
