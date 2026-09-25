package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When a colon in the composer is the start of an emoji name, and when it is only a colon. */
class EmojiQueryTest {
    @Test
    fun aNameAfterAColonAtAWordStartIsAQuery() {
        assertEquals("ta" to 6, detectEmojiQuery("lunch :ta", 9))
        assertEquals("+1" to 0, detectEmojiQuery(":+1", 3))
        assertEquals("ta" to 3, detectEmojiQuery("hi\n:ta", 6), "after a line break")
    }

    @Test
    fun aColonThatIsNotTheStartOfANameIsNot() {
        assertNull(detectEmojiQuery("time 10:30", 10), "a colon inside a word")
        assertNull(detectEmojiQuery("hi :", 4), "nothing after it yet")
        assertNull(detectEmojiQuery(":ta b", 5), "the caret is past a space")
        assertNull(detectEmojiQuery(":ta!", 4), "a character no name has")
        assertNull(detectEmojiQuery("", 0))
    }

    @Test
    fun theCaretDecidesNotTheEndOfTheText() {
        assertEquals("ta" to 0, detectEmojiQuery(":taco and more", 3))
    }
}
