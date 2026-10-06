package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which messages are nothing but emoji, and how many: what draws one as one. */
class EmojiOnlyTest {
    @Test
    fun `each emoji that draws as one counts once`() {
        assertEquals(1, emojiOnlyCount("👍"))
        assertEquals(3, emojiOnlyCount("🔥🔥🔥"))
        assertEquals(1, emojiOnlyCount("👨‍👩‍👧"), "a family joined by ZWJ")
        assertEquals(1, emojiOnlyCount("👍🏽"), "a skin tone")
        assertEquals(1, emojiOnlyCount("❤️"), "a variation selector")
        assertEquals(1, emojiOnlyCount("🇺🇸"), "a flag's pair")
        assertEquals(2, emojiOnlyCount("🇺🇸🇨🇦"))
        assertEquals(1, emojiOnlyCount("1️⃣"), "a keycap")
        assertEquals(1, emojiOnlyCount("🏴󠁧󠁢󠁥󠁮󠁧󠁿"), "a subdivision flag")
        assertEquals(2, emojiOnlyCount(" 🎉 🎂\n"), "spaces aside")
    }

    @Test
    fun `anything else in the message makes it text`() {
        assertEquals(0, emojiOnlyCount("nice 👍"))
        assertEquals(0, emojiOnlyCount("👍!"))
        assertEquals(0, emojiOnlyCount("1"), "a digit alone is not a keycap")
        assertEquals(0, emojiOnlyCount("#"))
        assertEquals(0, emojiOnlyCount(""))
        assertEquals(0, emojiOnlyCount("   "))
    }

    @Test
    fun `one to three emoji draw large, four do not`() {
        assertTrue(isJumboEmoji("👍"))
        assertTrue(isJumboEmoji("😂😂😂"))
        assertFalse(isJumboEmoji("😂😂😂😂"))
        assertFalse(isJumboEmoji("ok"))
    }
}
