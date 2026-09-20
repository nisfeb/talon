package io.nisfeb.talon.orrery

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The brief is about today.
 *
 * The state it reads used to be every body the ship has, with every
 * attribute: a situation three weeks out and every activity's next
 * whenever it fell. The owner said the brief reached a month ahead to
 * have something to say. What is further off than the next two days is
 * a title and a time under Ahead, or it is not in the prompt at all.
 */
class BriefHorizonTest {
    private val now = "2026-09-20T13:00:00Z"

    private fun state(vararg bodies: String) = Json.parseToJsonElement(
        """{"me":"person/me","bodies":[${bodies.joinToString(",")}]}""",
    ).jsonObject

    private fun situation(id: String, name: String, starts: String) =
        """{"id":"$id","kind":"situation","name":"$name","attrs":{
            "starts":{"value":"$starts"},"location":{"value":"the hall"},"status":{"value":"open"}}}"""

    private fun prompt(vararg bodies: String, said: String = "") = Brief.statePrompt(
        state(*bodies), emptyList(), now, TimeZone.UTC, listOf("nothing"), listOf("nothing"), said,
    )

    @Test
    fun `a situation weeks out is not in the prompt at all`() {
        val p = prompt(situation("situation/2026-10-10-recital", "Recital", "2026-10-10T18:00:00Z"))
        assertFalse("Recital" in p, "twenty days out is not today's business:\n$p")
        assertFalse("the hall" in p, "and neither are its attributes")
    }

    @Test
    fun `one three days out is under Ahead and nowhere else`() {
        val p = prompt(situation("situation/2026-09-23-dentist", "Dentist", "2026-09-23T14:00:00Z"))
        assertTrue("Ahead this week:" in p, "there is an Ahead section:\n$p")
        assertEquals(1, p.lines().count { "Dentist" in it }, "named once, under Ahead:\n$p")
        assertTrue(p.lines().first { "Dentist" in it }.trim().startsWith("Dentist | 2026-09-23"), "a title and a time, no attributes")
        assertFalse("the hall" in p, "no attributes came with it")
    }

    @Test
    fun `tomorrow's situation is in the state with what it says`() {
        val p = prompt(situation("situation/2026-09-21-recital", "Recital", "2026-09-21T18:00:00Z"))
        assertTrue("the hall" in p, "what is at hand keeps its attributes:\n$p")
        assertFalse("Ahead this week:" in p, "and is not repeated under Ahead")
    }

    @Test
    fun `an activity's next is shown only while it is near`() {
        val soon = """{"id":"activity/swim","kind":"activity","name":"Swimming","attrs":{"next":{"value":"2026-09-21T09:00:00Z"}}}"""
        val later = """{"id":"activity/tap","kind":"activity","name":"Tap","attrs":{"next":{"value":"2026-09-24T09:00:00Z"}}}"""
        val far = """{"id":"activity/ski","kind":"activity","name":"Ski","attrs":{"next":{"value":"2026-11-01T09:00:00Z"}}}"""
        val p = prompt(soon, later, far)
        assertTrue(p.lines().any { it.contains("activity/swim") && it.contains("next=") }, "tomorrow's is on the body:\n$p")
        assertFalse(p.lines().any { it.contains("activity/tap") && it.contains("next=") }, "Thursday's is not")
        assertTrue(p.lines().any { it.trim().startsWith("Tap | 2026-09-24") }, "it is under Ahead instead:\n$p")
        assertFalse("2026-11-01" in p, "November is nowhere")
    }

    @Test
    fun `what yesterday said is given to the model, and nothing when there was none`() {
        val said = "Two things overlap at 4.\nThe car's insurance looks stale."
        val p = prompt(situation("situation/2026-09-20-x", "X", "2026-09-20T18:00:00Z"), said = said)
        assertTrue("Yesterday's brief said:" in p)
        assertTrue("  Two things overlap at 4." in p, "indented under its heading:\n$p")
        assertFalse("Yesterday's brief said:" in prompt(situation("situation/2026-09-20-x", "X", "2026-09-20T18:00:00Z")))
    }
}
