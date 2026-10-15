package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AudioStatsLogTest {

    @Test
    fun `one line per inbound stream, carrying the loss and clock-drift fields`() {
        val lines = audioStatsLines(
            listOf(
                mapOf(
                    "ssrc" to 123L, "packetsReceived" to 1000L, "packetsLost" to 5L,
                    "jitter" to 0.01, "jitterBufferDelay" to 2.5, "concealmentEvents" to 2L,
                    "removedSamplesForAcceleration" to 0L, "insertedSamplesForDeceleration" to 480L,
                    "clockRate" to 48000L, "audioLevel" to 0.3,
                ),
                mapOf("ssrc" to 456L, "packetsReceived" to 50L, "audioLevel" to 0.0),
            ),
        )
        assertEquals(2, lines.size)
        val first = lines.first()
        assertContains(first, "ssrc=123")
        assertContains(first, "lost=5")
        assertContains(first, "conceal=2")
        assertContains(first, "decel=480")   // the slow/pitch signal
        assertContains(first, "rate=48000")
    }

    @Test
    fun `a field the report omits prints as unknown, never crashes`() {
        val line = audioStatsLines(listOf(mapOf("ssrc" to 7L, "audioLevel" to 0.1))).single()
        assertContains(line, "recv=?")
        assertContains(line, "conceal=?")
    }

    @Test
    fun `no inbound streams yields no lines`() {
        assertEquals(emptyList(), audioStatsLines(emptyList()))
    }
}
