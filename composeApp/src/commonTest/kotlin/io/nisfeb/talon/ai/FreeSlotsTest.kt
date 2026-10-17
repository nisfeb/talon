package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** find_free_time's arithmetic ([freeSlots]), in minutes for reading: "when am I free?" */
class FreeSlotsTest {
    private val m = 60_000L
    private fun free(busy: List<Pair<Int, Int>>, from: Int = 540, to: Int = 1020, min: Int = 30, now: Int = 0) =
        freeSlots(busy.map { (s, e) -> s * m to e * m }, from * m to to * m, min * m, now * m)
            .map { (s, e) -> (s / m).toInt() to (e / m).toInt() }

    @Test
    fun theGapsAroundEventsAreFree() =
        assertEquals(listOf(540 to 600, 660 to 1020), free(listOf(600 to 660)))

    @Test
    fun overlappingEventsAreOneBusyStretch() =
        assertEquals(listOf(540 to 600, 720 to 1020), free(listOf(630 to 720, 600 to 660, 640 to 650)))

    @Test
    fun aGapShorterThanAskedIsNotSaid() =
        assertEquals(listOf(540 to 600, 720 to 1020), free(listOf(600 to 660, 680 to 720)))

    @Test
    fun aGapOfExactlyTheLengthAskedIsSaid() =
        assertEquals(listOf(540 to 600, 660 to 690, 990 to 1020), free(listOf(600 to 660, 690 to 990)))

    @Test
    fun eventsOverTheWindowsEdgesCutItAndNoMore() {
        assertEquals(listOf(600 to 900), free(listOf(480 to 600, 900 to 1200)))
        assertEquals(emptyList(), free(listOf(0 to 1440)), "busy all day")
        assertEquals(listOf(540 to 1020), free(listOf(0 to 300, 1100 to 1200)), "outside the window, not in it")
    }

    @Test
    fun nothingBeforeNow() {
        assertEquals(listOf(700 to 1020), free(listOf(600 to 660), now = 700))
        assertEquals(listOf(660 to 1020), free(listOf(600 to 660), now = 630), "in an event now: free when it ends")
        assertEquals(emptyList(), free(emptyList(), now = 1100), "the window is over")
    }

    @Test
    fun aFreeDayIsTheWholeWindow() = assertEquals(listOf(540 to 1020), free(emptyList()))
}
