package io.nisfeb.talon.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * sneagan, 2026-10-06: "fix the relay read pushes too". Tlon sends an
 * ordinary read only to %activity /v4/reads; the relay watched /v4, so a
 * read push never went. Reads now come from their own subscription.
 */
class ReadStreamTest {
    @Test
    fun `the channel watches reads on their own path`() {
        val subs = Json.parseToJsonElement(subscribePayload("~zod")).jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("1 activity /v4", "3 activity /v4/reads", "2 trunk /calls"),
            subs.map { s -> listOf("id", "app", "path").joinToString(" ") { s[it]!!.jsonPrimitive.content } },
        )
        subs.forEach {
            assertEquals("zod", it["ship"]!!.jsonPrimitive.content)
            assertEquals("subscribe", it["action"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `each event is routed by the subscription it came on`() {
        assertEquals(Stream.READS, streamOf("3"))
        assertEquals(Stream.CALLS, streamOf("2"))
        assertEquals(Stream.ACTIVITY, streamOf("1"))
        assertEquals(Stream.ACTIVITY, streamOf(null))
    }

    // The fact as trunk-5f saw it on /v4/reads from a real %activity.
    @Test
    fun `a read from v4 reads names its chat`() {
        val fact = Json.parseToJsonElement(
            """{"read":{"source":{"dm":{"ship":"~tuc"}},"activity":{"count":0,"notify-count":0,"notify":false,"unread":null,"children":{},"recency":1}}}""",
        ).jsonObject
        assertEquals("~tuc", readPushWhom(fact, setOf("read")))
    }
}
