package io.nisfeb.talon.ui

import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.urbit.StoryPart
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Only a post that could run past ten lines is measured for a fold; the rest render as they did. */
class MightFoldTest {
    private fun text(s: String) = listOf<StoryPart>(StoryPart.Text(AnnotatedString(s)))

    @Test
    fun `a short post is never measured, a long one is`() {
        assertFalse(mightFold(text("hello there")))
        assertFalse(mightFold(text((1..5).joinToString("\n") { "line $it" })))
        assertTrue(mightFold(text((1..12).joinToString("\n") { "line $it" })), "many lines")
        assertTrue(mightFold(text("word ".repeat(80))), "one long paragraph")
    }
}
