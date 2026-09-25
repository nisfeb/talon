package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.usageLine
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pipe's small pure pieces: when an action is due, a key's scope, a model call's cost, a note's size. */
class OrreryHelpersTest {
    // Every screen says when an action is due in the owner's zone. The
    // list read the UTC date, a day late for anything due in the evening
    // west of Greenwich.
    @Test
    fun `when is said in the owner's zone`() {
        assertEquals(
            "Thu 24 Sep 22:00",
            OrreryText.whenText(io.nisfeb.talon.ui.parseIsoUtc("2026-09-25T02:00:00Z")!!, TimeZone.of("America/New_York")),
        )
        // The owner's own clock, and a due with no time still said.
        assertEquals("Thu 24 Sep 10:00 PM", OrreryText.dueText("2026-09-25T02:00:00Z", TimeZone.of("America/New_York"), twentyFourHour = false))
        assertEquals("Thu 24 Sep", OrreryText.dueText("2026-09-24", TimeZone.of("America/New_York")))
        assertEquals(null, OrreryText.dueText("soon", TimeZone.UTC))
    }

    @Test
    fun `a key covers the schema only with every kind, attribute and action kind`() {
        val full = Json.parseToJsonElement(
            """{"kinds": {"person": {"attrs": ["status", "health"]}, "note": {"attrs": []}}, "actions": ["task", "home"]}""",
        ).jsonObject
        val noHealth = Json.parseToJsonElement(
            """{"kinds": {"person": {"attrs": ["status"]}, "note": {"attrs": []}}, "actions": ["task", "home"]}""",
        ).jsonObject
        val noHome = Json.parseToJsonElement(
            """{"kinds": {"person": {"attrs": ["status", "health"]}, "note": {"attrs": []}}, "actions": ["task"]}""",
        ).jsonObject
        assertTrue(scopeCovers(full, full))
        assertFalse(scopeCovers(noHealth, full), "a key that may not write health")
        assertFalse(scopeCovers(noHome, full))
        val noNote = Json.parseToJsonElement(
            """{"kinds": {"person": {"attrs": ["status", "health"]}}, "actions": ["task", "home"]}""",
        ).jsonObject
        assertFalse(scopeCovers(noNote, full), "a key without a whole kind")
        assertFalse(scopeCovers(null, full))
    }

    @Test
    fun `each model call logs its tokens and cost`() {
        val body = Json.parseToJsonElement("""{"usage": {"input_tokens": 8556, "output_tokens": 2125}}""").jsonObject
        assertEquals("model claude-opus-5: 8556 in, 2125 out, $0.0959", usageLine(AiSettings.Provider.Anthropic, "claude-opus-5", body))
        val routed = Json.parseToJsonElement("""{"usage": {"prompt_tokens": 100, "completion_tokens": 20, "cost": 0.0012}}""").jsonObject
        assertEquals("model anthropic/claude-opus-5: 100 in, 20 out, $0.0012", usageLine(AiSettings.Provider.OpenRouter, "anthropic/claude-opus-5", routed))
    }

    @Test
    fun `a reason is cut at five hundred bytes, never inside a character`() {
        assertEquals("short", clipBytes("short", 500))
        val cut = clipBytes("é".repeat(400), 500) // two bytes each
        assertEquals(250, cut.length)
        val emoji = clipBytes("🦐".repeat(200), 500) // four bytes, two chars each
        assertTrue(emoji.encodeToByteArray().size <= 500)
        assertTrue(!emoji.last().isHighSurrogate(), "no half of a pair left at the end")
        assertEquals(125, emoji.length / 2)
        // A byte short, the cut lands inside the last pair: its first half goes too.
        assertEquals(248, clipBytes("🦐".repeat(200), 499).length)
    }
}
