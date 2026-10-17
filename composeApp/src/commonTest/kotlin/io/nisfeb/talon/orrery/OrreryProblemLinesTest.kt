package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a failed check or read of Orrery says, and how often a failed check is tried again. */
class OrreryProblemLinesTest {
    private val down = OrreryError.Unreachable(ProxyAnswered(502))

    @Test
    fun `a failed read is said by what failed`() {
        assertEquals("Your ship did not answer", readProblemLine(down))
        assertEquals("Orrery is not on your ship now", readProblemLine(OrreryError.Refused(404, "not found")))
        assertEquals("Orrery no longer takes this device's key", readProblemLine(OrreryError.Refused(403, "forbidden")))
        assertEquals("Orrery could not be read: busy", readProblemLine(OrreryError.Refused(500, "busy")))
        assertEquals("Orrery could not be read", readProblemLine(OrreryError.Refused(500, "<html>")), "never an error page")
        assertEquals("Orrery's answer could not be read", readProblemLine(OrreryError.Garbled(RuntimeException("x"))))
    }

    @Test
    fun `only a key refused as the pass reads it is offered a new key`() {
        assertTrue(isKeyRefused(OrreryError.Refused(403, "forbidden")))
        assertFalse(isKeyRefused(OrreryError.Refused(403, "outside this key's scope")))
        assertFalse(isKeyRefused(OrreryError.Refused(404, "forbidden")))
        assertFalse(isKeyRefused(down))
    }

    @Test
    fun `a failed check is said plainly, and that it is asked again`() {
        assertEquals("Your ship did not answer. Talon asks again by itself.", probeProblemLine(down))
        assertEquals("Orrery on your ship could not answer: crash. Talon asks again by itself.", probeProblemLine(OrreryError.Refused(500, "crash")))
        assertEquals("Orrery on your ship could not answer. Talon asks again by itself.", probeProblemLine(OrreryError.Refused(500, "<html></html>")))
        assertEquals("Your ship's answer about Orrery could not be read. Talon asks again by itself.", probeProblemLine(OrreryError.Garbled(RuntimeException())))
    }

    @Test
    fun `a failed check waits half a minute, then twice as long each time, to a quarter hour`() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L), (1..7).map { probeRetryMs(it) })
        assertEquals(900_000L, probeRetryMs(1_000), "never past the cap, however many")
        assertEquals(50L, probeRetryMs(1, first = 50))
    }
}
