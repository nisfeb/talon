package io.nisfeb.talon.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.nisfeb.talon.urbit.chatTextToStory
import io.nisfeb.talon.urbit.mentionRanges
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A picked comet showed in the box as its @p. The box shows a mention as
 * it will read once sent, by the sender's naming, and holds the @p.
 */
class MentionTextTest {
    private val names = mapOf("~zod" to "Zed", "~nec" to "Neck")
    private fun name(p: String) = names[p] ?: p

    @Test
    fun `only what goes as a mention is one`() {
        val text = "hi ~zod, `~nec` and https://x.test/~nec and ~wisdom and ~nec"
        val found = mentionRanges(text).map { text.substring(it.first, it.last + 1) to it.first }
        assertEquals(listOf("~zod" to 3, "~nec" to text.lastIndexOf("~nec")), found)
        // The same ships the sent story carries, in its order.
        val sent = Regex("\"ship\":\"(~[a-z-]+)\"").findAll(chatTextToStory(text).toString()).map { it.groupValues[1] }.toList()
        assertEquals(sent, found.map { it.first })
    }

    @Test
    fun `the box shows names where the text holds the @p, and offsets meet at the edges`() {
        val t = mentionTransformation(::name).filter(AnnotatedString("hi ~zod and ~nec"))
        assertEquals("hi Zed and Neck", t.text.text)
        val m = t.offsetMapping
        assertEquals(3, m.originalToTransformed(3))
        assertEquals(6, m.originalToTransformed(7)) // after ~zod: after Zed
        assertEquals(11, m.originalToTransformed(12)) // before ~nec
        assertEquals(15, m.originalToTransformed(16))
        assertEquals(7, m.transformedToOriginal(6))
        assertEquals(16, m.transformedToOriginal(15))
    }

    @Test
    fun `one backspace after a name takes the whole mention`() {
        val before = TextFieldValue("hi ~zod ", TextRange(7))
        val typed = TextFieldValue("hi ~zo ", TextRange(6))
        assertEquals(TextFieldValue("hi  ", TextRange(3)), keepMentionsWhole(before, typed))
    }

    @Test
    fun `a caret landing inside a mention goes to the edge it was heading for`() {
        val text = "hi ~zod ok"
        assertEquals(TextRange(7), keepMentionsWhole(TextFieldValue(text, TextRange(3)), TextFieldValue(text, TextRange(4))).selection)
        assertEquals(TextRange(3), keepMentionsWhole(TextFieldValue(text, TextRange(7)), TextFieldValue(text, TextRange(6))).selection)
    }

    @Test
    fun `ordinary typing is left alone`() {
        val before = TextFieldValue("hi ~zod ", TextRange(8))
        val typed = TextFieldValue("hi ~zod o", TextRange(9))
        assertEquals(typed, keepMentionsWhole(before, typed))
        val cut = TextFieldValue("hi ~zod", TextRange(7))
        assertEquals(cut, keepMentionsWhole(before, cut), "a space after it is not part of it")
    }
}
