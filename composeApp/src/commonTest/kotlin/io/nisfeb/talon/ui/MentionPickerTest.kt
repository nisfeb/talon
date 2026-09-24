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
