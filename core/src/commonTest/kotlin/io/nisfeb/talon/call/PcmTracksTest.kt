package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertContentEquals

class PcmTracksTest {
    @Test
    fun interleavesOneChannelPerTrackAndPadsTheShortOne() {
        val a = byteArrayOf(1, 0, 2, 0, 3, 0)       // three samples
        val b = byteArrayOf(9, 0)                   // one sample
        val out = PcmTracks.interleave(listOf(a, b))
        // frame 1: a=1 b=9, frame 2: a=2 b=0, frame 3: a=3 b=0
        assertContentEquals(byteArrayOf(1, 0, 9, 0, 2, 0, 0, 0, 3, 0, 0, 0), out)
    }
}
