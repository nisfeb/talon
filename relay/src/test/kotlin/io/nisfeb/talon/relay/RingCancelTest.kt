package io.nisfeb.talon.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RingCancelTest {

    private fun fact(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `ring fact parses to Ring`() {
        val fact = parseCallFact(fact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}"""))
        assertEquals(CallFact.Ring("~zod", "c1"), fact)
    }

    @Test
    fun `hangup sig parses to Settled`() {
        val fact = parseCallFact(fact("""{"recv":{"from":"~zod","sig":{"hangup":{"id":"c1"}}}}"""))
        assertEquals(CallFact.Settled("c1"), fact)
    }

    @Test
    fun `handled fact parses to Settled`() {
        assertEquals(CallFact.Settled("c1"), parseCallFact(fact("""{"handled":"c1"}""")))
    }

    @Test
    fun `other trunk facts parse to null`() {
        // offer/accept/reject are for an awake client; open is a room fact.
        assertNull(parseCallFact(fact("""{"recv":{"from":"~zod","sig":{"offer":{"id":"c1","sdp":"s","fpr":"f"}}}}""")))
        assertNull(parseCallFact(fact("""{"open":{"from":"~zod","name":"r"}}""")))
        assertNull(parseCallFact(fact("""{"handled":{"id":"c1"}}"""))) // wrong shape
    }

    private val now = 1_700_000_000_000L

    @Test
    fun `settle only fires for a rung id, once`() {
        val rung = RungCalls()
        rung.rang("c1", "https://push/e1", platform = "unifiedpush", nowMs = now)
        assertNull(rung.settle("c2", nowMs = now)) // never rung
        assertEquals("https://push/e1", rung.settle("c1", nowMs = now)?.endpoint)
        assertNull(rung.settle("c1", nowMs = now)) // one-shot
    }

    @Test
    fun `a settle past ring-timeout age is dropped`() {
        val rung = RungCalls(maxAgeMs = 60_000L)
        rung.rang("c1", "https://push/e1", platform = "unifiedpush", nowMs = now)
        assertNull(rung.settle("c1", nowMs = now + 61_000L))
    }

    @Test
    fun `stale rings are pruned on the next ring`() {
        val rung = RungCalls(maxAgeMs = 60_000L)
        rung.rang("c1", "https://push/e1", platform = "unifiedpush", nowMs = now)
        rung.rang("c2", "https://push/e1", platform = "unifiedpush", nowMs = now + 61_000L)
        // c1 aged out at the c2 write; c2 itself is live.
        assertNull(rung.settle("c1", nowMs = now + 61_000L))
        assertEquals("https://push/e1", rung.settle("c2", nowMs = now + 61_000L)?.endpoint)
    }
}
