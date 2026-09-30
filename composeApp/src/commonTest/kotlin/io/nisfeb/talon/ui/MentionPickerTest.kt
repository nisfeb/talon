package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins how a typed @mention, or a ship box, is read for suggestions. */
class MentionPickerTest {

    @Test
    fun `mention token survives the dots a mnemonym query needs`() {
        assertEquals("accept.eng" to 0, detectMentionQuery("@accept.eng", 11))
        assertEquals(".accept" to 4, detectMentionQuery("hey @.accept", 12))
        // Mid-word @ is not a trigger; punctuation still ends the token.
        assertNull(detectMentionQuery("mail@accept", 11))
        assertNull(detectMentionQuery("@acc,ept", 8))
    }

    // A comet's name typed as it reads, `..renewed`, opened no picker:
    // only @ and ~ did, so the one name shown everywhere found nothing.
    @Test
    fun `a word name typed bare opens the picker, its dots kept`() {
        assertEquals("..ren" to 3, detectMentionQuery("hi ..ren", 8))
        assertEquals(".bespoke.unn" to 0, detectMentionQuery(".bespoke.unn", 12))
        assertEquals("..renewed.erupt" to 0, detectMentionQuery("..renewed.erupt", 15))
        // Not a name: an ellipsis, a dotted word, dots alone, a dot mid-word.
        assertNull(detectMentionQuery("wait...and", 10))
        assertNull(detectMentionQuery("so ...and", 9))
        assertNull(detectMentionQuery("e.g.", 4))
        assertNull(detectMentionQuery("hi ..", 5))
        assertNull(detectMentionQuery("hi there", 8))
    }

    @Test
    fun `the start of a comet's name finds it among the ships known`() {
        val them = Mnemonym.shipForNym("..bespoke.unnerved.describe.convince.inhale.charade.relieve.obey.reword.dislodge.hereby.kazoo")!!
        val (query, _) = detectMentionQuery("hey ..besp", 10)!!
        val found = suggestionsFor(query, ContactMap.EMPTY, listOf("~zod", them, "~nec"))
        assertEquals(listOf(them), found.map { it.ship })
    }

    // Every box a ship is typed into suggests as a mention does. A box
    // of several suggests for the last name, and keeps the ones before.
    @Test
    fun `a ship box suggests for the name being typed`() {
        assertEquals("" to "sampel", shipDraft("~sampel", several = false))
        assertEquals("" to "sam", shipDraft(" @sam ", several = false))
        assertEquals("~zod, " to "samp", shipDraft("~zod, ~samp", several = true))
        assertEquals("~zod ~bus " to "", shipDraft("~zod ~bus ", several = true))
        assertEquals("" to "zod", shipDraft("~zod", several = true))
    }
}
