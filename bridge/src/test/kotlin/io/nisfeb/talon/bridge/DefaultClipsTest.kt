package io.nisfeb.talon.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultClipsTest {
    @Test
    fun clipsAreAudibleAndWrittenOnce() {
        for (n in DefaultClips.names) {
            val s = DefaultClips.synth(n)
            assertTrue(s.size > 4800, "$n is too short")
            assertTrue(s.maxOf { kotlin.math.abs(it) } in 0.3..1.0, "$n peak out of range")
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "talon-clips-${System.nanoTime()}")
        try {
            assertEquals(DefaultClips.names.size, DefaultClips.ensure(dir).size)
            assertEquals(0, DefaultClips.ensure(dir).size)
            WavPcmSource(File(dir, "ding.wav"), loop = false) // throws unless it is a real WAV
        } finally {
            dir.deleteRecursively()
        }
    }
}
