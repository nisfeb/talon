package io.nisfeb.talon.ui

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
    fun `non-ships fall through to null`() {
        assertNull(Mnemonym.forShip("chat/~zod/general"))
        assertNull(Mnemonym.forShip("~sampel-xxxxxx"))
        assertNull(Mnemonym.forShip("sampel-palnet"))
        assertNull(Mnemonym.forShip(""))
    }

}
