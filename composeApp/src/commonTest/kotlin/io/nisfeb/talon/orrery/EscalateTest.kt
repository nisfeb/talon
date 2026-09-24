package io.nisfeb.talon.orrery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 16: the generator runs under a cooldown, so a breakdown at five
 * past ten would wait for eleven. The reader has the words in front of
 * it, so the reader is what asks the ship for a pass now.
 */
class EscalateTest {
    private val bodies = listOf(
        KnownBody("person/rose", "Rose", emptyList(), "~sampel-palnet"),
        KnownBody("situation/2026-09-21-breakdown", "Breakdown", emptyList(), null),
    )

    private fun fact(subject: String, attr: String = "status") =
        Noticed(subject, attr, JsonPrimitive("stranded"), 1_800_000_000_000L, null, 80)

    private class Says(private val p: Double?) : Decider {
        var asked: JsonObject? = null
        override suspend fun ask(state: JsonObject, questions: JsonObject): Decision {
            asked = state
            if (p == null) error("the decider did not answer")
            return Decision(buildJsonObject { put("needs_help_now", buildJsonObject { put("noul", p) }) })
        }
    }

    @Test
    fun `the facts just kept are part of what is asked`() = runTest {
        val d = Says(0.91)
        val p = Escalate.sure(d, "car died on route 9", "person/rose", listOf("where are you"), bodies, listOf(fact("person/rose")))
        assertEquals(0.91, p)
        val state = d.asked!!
        assertEquals("car died on route 9", state["message"]!!.jsonPrimitive.content)
        assertEquals(1, state["facts"]!!.jsonArray.size)
        assertEquals("person/rose", state["facts"]!!.jsonArray[0].jsonObject["subject"]!!.jsonPrimitive.content)
        assertTrue("act now" in state["rule"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a decider that cannot answer escalates nothing`() = runTest {
        assertEquals(0.0, Escalate.sure(Says(null), "car died", "person/rose", emptyList(), bodies, listOf(fact("person/rose"))))
    }

    @Test
    fun `the pass is pointed at the situations, else what else was named`() {
        assertEquals(
            listOf("situation/2026-09-21-breakdown"),
            Escalate.about(listOf(fact("person/rose"), fact("situation/2026-09-21-breakdown"), fact("thing/subaru"))),
            "a situation outranks the rest",
        )
        assertEquals(listOf("thing/subaru"), Escalate.about(listOf(fact("person/rose"), fact("thing/subaru"))))
        // Only about the owner: the pass runs anyway, pointed at nothing.
        assertEquals(emptyList(), Escalate.about(listOf(fact("person/me"))))
        assertEquals(5, Escalate.about((1..9).map { fact("situation/s$it") }).size, "at most five")
    }
}
