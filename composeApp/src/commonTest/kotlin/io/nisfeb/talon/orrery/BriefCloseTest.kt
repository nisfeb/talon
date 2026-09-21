package io.nisfeb.talon.orrery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closing something from a reply to the brief.
 *
 * The ship closes what is over on its own now, twice a day: a
 * situation whose end has passed, a scheduled one six hours after it
 * starts, a trip a week on, a delivery presumed after its grace. Talon
 * decides none of that. What the owner says in a reply is different,
 * and it has one way to land: a status row through POST /observe. A
 * move is for an action and would never reach a body.
 */
class BriefCloseTest {
    private val attrs = mapOf(
        "situation" to listOf("status", "starts", "ends", "participants"),
        "person" to listOf("status", "location"),
    )

    private fun facts(answer: String) = Brief.replyFacts(
        Json.parseToJsonElement(answer).jsonObject,
        known = setOf("situation/2026-09-16-breakdown", "person/rose"),
        attrs = attrs,
        resolved = emptyMap(),
        replyId = "0vreply",
        atMs = 1_800_000_000_000L,
    )

    @Test
    fun `a close in a reply goes up as a status observation`() {
        val f = facts(
            """{"moves":[],"bodies":[],"actions":[],
                "observations":[{"subject":"situation/2026-09-16-breakdown","attr":"status","value":"closed"}]}""",
        )
        val o = f.observations.single()
        assertEquals("situation/2026-09-16-breakdown", o.subject)
        assertEquals("status", o.attr)
        assertEquals(JsonPrimitive("closed"), o.value)
        assertEquals(100, o.conf, "the owner's own word about their own world")
        assertEquals("mail", o.sourceKind)
        assertEquals("0vreply", o.sourceId)
    }

    @Test
    fun `a close on something the ship does not have is not invented`() {
        val f = facts(
            """{"moves":[],"bodies":[],"actions":[],
                "observations":[{"subject":"situation/never-happened","attr":"status","value":"closed"}]}""",
        )
        assertTrue(f.observations.isEmpty(), "a body the ship lacks is not closed into existence")
    }

    @Test
    fun `an attribute the schema does not list is dropped`() {
        // The ship's own closes are signed retire and come from the
        // reconcile pass; a client writing a status the schema has no
        // room for would simply be refused, so it is dropped here.
        val f = facts(
            """{"moves":[],"bodies":[],"actions":[],
                "observations":[{"subject":"situation/2026-09-16-breakdown","attr":"retired","value":"closed"}]}""",
        )
        assertTrue(f.observations.isEmpty())
    }
}
