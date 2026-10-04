package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An @da as a scry path carries it, for groups-ui's /changes: hoon's
 * parser takes the month and day unpadded (it refuses `01`) and the
 * time in any width; Talon writes it two digits, to the second, in UTC.
 */
class UrbitTimeTextTest {
    @Test
    fun `a time is written as hoon reads an @da`() {
        // 2026-10-04T13:05:09.750Z
        assertEquals("~2026.10.4..13.05.09", UrbitTime.unixMsToDaText(1_791_119_109_750L))
        // 2027-01-02T03:04:05Z: the month and day unpadded, the time padded.
        assertEquals("~2027.1.2..03.04.05", UrbitTime.unixMsToDaText(1_798_859_045_000L))
    }
}
