package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who can reach the owner, and when: the rules every alert path reads. */
class ReachTest {
    private val night = QuietHours(on = true, fromMin = 22 * 60, toMin = 7 * 60)

    @Test
    fun `quiet hours over midnight hold from their start to their end, not after`() {
        assertTrue(quietAt(night, 22 * 60))
        assertTrue(quietAt(night, 23 * 60 + 59))
        assertTrue(quietAt(night, 0))
        assertTrue(quietAt(night, 6 * 60 + 59))
        assertFalse(quietAt(night, 7 * 60), "the end is not quiet")
        assertFalse(quietAt(night, 21 * 60 + 59))
        assertFalse(quietAt(night, 12 * 60))
    }

    @Test
    fun `quiet hours within a day, off, or from a time to itself`() {
        val lunch = QuietHours(on = true, fromMin = 12 * 60, toMin = 13 * 60)
        assertTrue(quietAt(lunch, 12 * 60))
        assertFalse(quietAt(lunch, 13 * 60))
        assertFalse(quietAt(lunch, 11 * 60 + 59))
        assertFalse(quietAt(night.copy(on = false), 23 * 60), "off is never quiet")
        assertFalse(quietAt(QuietHours(on = true, fromMin = 600, toMin = 600), 600), "none at all")
    }

    @Test
    fun `in quiet hours only a favourite reaches`() {
        val r = ReachSettings(favourites = setOf("~nec"), quiet = night)
        assertTrue(messageReaches(r, "~nec", 23 * 60))
        assertFalse(messageReaches(r, "~bus", 23 * 60))
        assertTrue(messageReaches(r, "~bus", 12 * 60))
    }

    @Test
    fun `with only people added, a stranger's request waits, and a contact's or favourite's reaches`() {
        val added = ReachSettings(whoCanMessage = WhoCanMessage.Added, favourites = setOf("~nec"))
        assertFalse(requestReaches(added, "~bus", inBook = false, minuteOfDay = 600))
        assertTrue(requestReaches(added, "~wes", inBook = true, minuteOfDay = 600))
        assertTrue(requestReaches(added, "~nec", inBook = false, minuteOfDay = 600))
        assertTrue(requestReaches(ReachSettings(), "~bus", inBook = false, minuteOfDay = 600), "anyone, as a request")
        assertFalse(requestReaches(ReachSettings(quiet = night), "~bus", inBook = true, minuteOfDay = 23 * 60), "quiet hours hold a request too")
    }

    @Test
    fun `a push that names only the chat gets through quiet hours for a favourite's DM alone`() {
        val r = ReachSettings(favourites = setOf("~nec"), quiet = night)
        assertTrue(hintReaches(r, "~nec", 23 * 60))
        assertFalse(hintReaches(r, "chat/~nec/general", 23 * 60), "a channel: who wrote is not known")
        assertFalse(hintReaches(r, "~bus", 23 * 60))
        assertTrue(hintReaches(r, "~bus", 12 * 60))
    }

    @Test
    fun `a minute reads as a clock time`() {
        assertEquals("10:00 PM", minuteLabel(22 * 60))
        assertEquals("12:05 AM", minuteLabel(5))
        assertEquals("12:00 PM", minuteLabel(12 * 60))
        assertEquals("7:30 AM", minuteLabel(7 * 60 + 30))
    }
}
