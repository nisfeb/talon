package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A message becomes a title and a description, and nothing that was said is lost. */
class MessageToCalendarTest {
    @Test
    fun `the first line is the title and the rest is the description`() {
        val said = "Dentist Thursday\nat 3, the one on Main St\nbring the insurance card"
        val title = titleFromMessage(said)
        assertEquals("Dentist Thursday", title)
        assertEquals("at 3, the one on Main St\nbring the insurance card", descriptionFromMessage(said, title))
    }

    @Test
    fun `a title that had to be cut leaves the whole message in the description`() {
        val said = "Can you pick up the cake from the bakery on the way home, the one with the green door near the station"
        val title = titleFromMessage(said)
        assertTrue(title.length <= 73, title)
        assertEquals(said, descriptionFromMessage(said, title), "the part the title had no room for is not lost")
    }

    @Test
    fun `one short line is a title and nothing else`() {
        assertEquals("", descriptionFromMessage("Call mum", titleFromMessage("Call mum")))
    }

    @Test
    fun `a first sentence can be the title, and then the whole message is kept`() {
        val said = "Book the ferry for Friday. The 8am one fills up fast."
        val title = titleFromMessage(said)
        assertEquals("Book the ferry for Friday", title)
        assertEquals(said, descriptionFromMessage(said, title))
    }
}
