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
        assertEquals("Send to you: Locked test? Say send, or cancel.", DictationSpeech.prompt("Text yourself.", sending = true, listOf(message("You", "Locked test"))))
        assertEquals("Send to Andrea: Running late? Say send, or cancel.", DictationSpeech.prompt("r", true, listOf(message("Andrea", "**Running** late"))))
        assertEquals("Add a todo to buy milk. Say yes, or cancel.", DictationSpeech.prompt("Add a todo to buy milk.", sending = false, listOf(CardProposal("task", "Buy milk"))))
        assertEquals("Text her. Say send, or cancel.", DictationSpeech.prompt("Text her.", sending = true, listOf(CardProposal("message", "Text Andrea"))), "a message with no words to read")
        assertEquals("", DictationSpeech.spoken("   "))
    }

    private fun message(to: String, text: String) = CardProposal("message", "Text $to", to = to, text = text)

    // One "send" approves all of them: the second message went unread.
    @Test
    fun `every waiting proposal is read before one answer approves them all`() {
        assertEquals(
            "2 things wait. Send to Andrea: Running late. Send to Nec: On my way. Say send, or cancel.",
            DictationSpeech.prompt("Two texts.", sending = true, listOf(message("Andrea", "Running late."), message("Nec", "On my way"))),
        )
        assertEquals(
            "2 things wait. Send to you: Milk. Add task: buy eggs. Say send, or cancel.",
            DictationSpeech.prompt("r", sending = true, listOf(message("You", "Milk"), CardProposal("task", "Add task: buy **eggs**."))),
        )
        assertEquals(
            "2 things wait. Buy milk. Call the vet. Say yes, or cancel.",
            DictationSpeech.prompt("Two tasks.", sending = false, listOf(CardProposal("task", "Buy milk"), CardProposal("task", "Call the vet"))),
        )
    }

    @Test
    fun `times are said as times, never as colons`() {
        assertEquals("Ballet from 4 45 PM to 7 15 PM.", DictationSpeech.spoken("Ballet from 4:45 PM \u2013 7:15 PM."))
        assertEquals("Core Sync at noon, then 1 PM.", DictationSpeech.spoken("Core Sync at 12:00 PM, then 1:00 PM."))
        assertEquals("Up at 6 oh 5 AM.", DictationSpeech.spoken("Up at 6:05 AM."))
        assertEquals("Mass at 9 AM, game at 4 30 PM, home by midnight.", DictationSpeech.spoken("Mass at 09:00, game at 16:30, home by 00:00."))
        assertEquals("Next is Ballet today at 4 45 PM.", DictationSpeech.spoken("Next is Ballet today at 4:45 PM."))
        assertEquals("Lunch at 12 30 PM, coffee at 6 10 AM, mail at 12 15 PM.", DictationSpeech.spoken("Lunch at 12:30, coffee at 6:10 AM, mail at 12:15 PM."))
        // Not a time: a ratio, a verse, a list label.
        assertEquals("The score was 3:1 and John 3:16 says.", DictationSpeech.spoken("The score was 3:1 and John 3:16 says."))
    }
}
