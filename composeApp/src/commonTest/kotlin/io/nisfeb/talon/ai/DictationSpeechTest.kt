package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictationSpeechTest {
    @Test
    fun `the highlights are said, the details only shown`() {
        val answer = "A busy day: three meetings and Rose's ballet at 4:45.\n\n- **Routing R&D** 10 to 11\n- Core Sync at noon\n- Ballet 4:45 to 7:15"
        assertEquals("A busy day: three meetings and Rose's ballet at 4:45.", DictationSpeech.spoken(answer))
    }

    @Test
    fun `markdown, links and @p sigils are not read out`() {
        assertEquals(
            "Andrea, sampel-palnet, said yes in Tom on Tuesday.",
            DictationSpeech.spoken("**Andrea**, ~sampel-palnet, said yes in [Tom on Tuesday](talon://chat/x)."),
        )
        assertEquals("See it.", DictationSpeech.spoken("See it https://example.com/x."))
    }

    @Test
    fun `a long first paragraph is cut at a sentence end`() {
        val long = "First thing is short. " + "Then a much longer sentence that goes on about the day in more detail than anyone wants to hear. ".repeat(4)
        val said = DictationSpeech.spoken(long)
        assertTrue(said.length <= DictationSpeech.MAX_CHARS, said)
        assertTrue(said.startsWith("First thing is short.") && said.endsWith("."), said)
    }

    @Test
    fun `one huge sentence is cut at a word, not mid-word`() {
        val said = DictationSpeech.spoken("word ".repeat(100).trim())
        assertTrue(said.endsWith("word…"), said)
    }

    @Test
    fun `what waits is asked aloud, ending in what to say back`() {
        assertEquals("Send to you: Locked test? Say send, or cancel.", DictationSpeech.prompt("Text yourself.", sending = true, to = "You", text = "Locked test"))
        assertEquals("Send to Andrea: **Running** late? Say send, or cancel.".replace("**", ""), DictationSpeech.prompt("r", true, "Andrea", "**Running** late"))
        assertEquals("Add a todo to buy milk. Say yes, or cancel.", DictationSpeech.prompt("Add a todo to buy milk.", sending = false, to = null, text = null))
        assertEquals("", DictationSpeech.spoken("   "))
    }
}
