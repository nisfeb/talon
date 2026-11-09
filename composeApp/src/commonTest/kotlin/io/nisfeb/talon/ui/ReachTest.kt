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

    // The ship's own pushes (%trunk) read quiet hours by the offset: a
    // ship has no zone table. Body shape checked against trunk's
    // lib/trunk-push.hoon +reach-of.
    @Test
    fun `quiet hours set here carry this clock's zone and its minutes from UTC`() {
        val q = night.onThisClock("America/New_York" to -300)
        assertEquals("America/New_York" to -300, q.zone to q.offsetMin)
        assertEquals(
            """{"whoCanMessage":"Anyone","favourites":[],"quiet":{"on":true,"fromMin":1320,"toMin":420,"zone":"America/New_York","offsetMin":-300}}""",
            ReachSettings(quiet = q).toJson(),
        )
        assertEquals(null, reachFromJson("""{"quiet":{"on":true,"fromMin":1320,"toMin":420,"zone":"UTC"}}""")?.quiet?.offsetMin, "from a build before it")
    }

    @Test
    fun `the offset is put right for this device's own zone, and another zone's is left alone`() {
        val ny = "America/New_York" to -300
        val set = ReachSettings(quiet = night.onThisClock(ny))
        assertEquals(null, quietClockDue(set, ny), "already right: nothing written")
        // Summer time: the same zone, an hour on.
        assertEquals(-240, quietClockDue(set, "America/New_York" to -240)?.quiet?.offsetMin)
        // Written by a build before the offset, in this zone or in none.
        assertEquals(-300, quietClockDue(ReachSettings(quiet = night.copy(zone = "America/New_York")), ny)?.quiet?.offsetMin)
        assertEquals(ny, quietClockDue(ReachSettings(quiet = night), ny)?.quiet?.let { it.zone to it.offsetMin })
        // Another device's zone: its to keep, or the two would write over each other at every start.
        assertEquals(null, quietClockDue(set, "America/Los_Angeles" to -480))
        assertEquals(null, quietClockDue(ReachSettings(quiet = night.copy(on = false)), ny), "off: nothing to keep")
    }

    @Test
    fun `a minute reads as a clock time`() {
        assertEquals("10:00 PM", minuteLabel(22 * 60))
        assertEquals("12:05 AM", minuteLabel(5))
        assertEquals("12:00 PM", minuteLabel(12 * 60))
        assertEquals("7:30 AM", minuteLabel(7 * 60 + 30))
    }
}
