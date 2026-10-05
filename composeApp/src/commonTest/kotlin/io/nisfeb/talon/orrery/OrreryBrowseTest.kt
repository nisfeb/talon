package io.nisfeb.talon.orrery

import io.nisfeb.talon.ui.parseIsoUtc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Orrery section's reading of orrery's state: what each thing is, what
 * is coming up, what a search finds, and which thing a leave alert is about.
 */
class OrreryBrowseTest {
    private fun at(iso: String) = parseIsoUtc(iso)!!
    private val now = at("2026-10-05T18:00:00Z")

    /** The shape GET /api/state answers with (orrery 71), names made up. */
    private val state = Json.parseToJsonElement(
        """{"me":"person/me","bodies":[
        {"id":"activity/fencing-lesson","kind":"activity","name":"Fencing lesson","aliases":["fencing"],"attrs":{
            "next":{"value":"2026-10-05T20:00:00Z","conf":90},"location":{"value":"Fencing Club, 1 Main St"},
            "participants":[{"value":{"ref":"person/me"}},{"value":{"ref":"person/kid"}}],
            "pick-up":{"value":{"ref":"person/me"}},"until":{"value":null}}},
        {"id":"activity/old-class","kind":"activity","name":"Old class","attrs":{"next":{"value":"2026-10-05T10:00:00Z"}}},
        {"id":"activity/stopped","kind":"activity","name":"Stopped","attrs":{"next":{"value":"2026-10-06T10:00:00Z"},"status":{"value":"ended"}}},
        {"id":"situation/visit","kind":"situation","name":"Visit","attrs":{"starts":{"value":"2026-10-07T14:00:00Z"},"ends":{"value":"2026-10-07T16:00:00Z"}}},
        {"id":"situation/under-way","kind":"situation","name":"Under way","attrs":{"starts":{"value":"2026-10-05T17:00:00Z"},"ends":{"value":"2026-10-05T19:00:00Z"}}},
        {"id":"situation/closed","kind":"situation","name":"Closed","attrs":{"starts":{"value":"2026-10-08T14:00:00Z"},"status":{"value":"closed"}}},
        {"id":"person/kid","kind":"person","name":"Kid","aliases":["the boy"],"attrs":{}},
        {"id":"widget/x","kind":"widget","attrs":{}}
        ]}""",
    ).jsonObject
    private val items = orreryItems(state)

    @Test
    fun `each thing is read with its current values, a list as a list and an empty one left out`() {
        val lesson = items.first { it.id == "activity/fencing-lesson" }
        assertEquals("Fencing lesson", lesson.name)
        assertEquals("Fencing Club, 1 Main St", lesson.where)
        assertEquals(listOf("person/me", "person/kid"), lesson.refs("participants"))
        assertEquals(listOf("person/me"), lesson.refs("pick-up"))
        assertNull(lesson.values["until"], "a null value is no value")
        assertEquals("x", items.first { it.id == "widget/x" }.name, "a thing with no name goes by its slug")
    }

    @Test
    fun `coming up is what is ahead or under way, soonest first`() {
        assertEquals(
            listOf("situation/under-way", "activity/fencing-lesson", "situation/visit"),
            comingUp(items, now).map { it.item.id },
            "past, ended and closed ones are left out",
        )
        val lesson = comingUp(items, now).first { it.item.id == "activity/fencing-lesson" }
        assertEquals(at("2026-10-05T20:00:00Z") to null, lesson.startMs to lesson.endMs)
        assertEquals(listOf("activity/fencing-lesson"), comingUp(items, at("2026-10-05T20:59:00Z")).map { it.item.id }.filter { it.startsWith("activity") }, "an hour under way")
        assertEquals(emptyList(), comingUp(items, at("2026-10-05T21:01:00Z")).map { it.item.id }.filter { it.startsWith("activity") })
    }

    @Test
    fun `browse finds by name or alias, by kind in order, unknown kinds last`() {
        assertEquals(
            listOf("Situations", "Activities", "People", "Widget"),
            browse(items, "").map { it.first },
        )
        assertEquals(listOf("Kid"), browse(items, "BOY").flatMap { it.second }.map { it.name })
        assertEquals(listOf("Fencing lesson"), browse(items, "fenc").flatMap { it.second }.map { it.name })
        assertEquals(emptyList(), browse(items, "nothing like it"))
    }

    @Test
    fun `a leave alert names its thing, and the ship's plan says when to leave for which`() {
        assertEquals("activity/fencing-lesson", leaveItemOf("orrery-leave-activity/fencing-lesson@1791230400000"))
        assertEquals("situation/visit", leaveItemOf("orrery-leave-situation/visit"))
        assertNull(leaveItemOf("orrery-other"))
        assertNull(leaveItemOf(null))
        val last = Json.parseToJsonElement(
            """{"next":{"key":"activity/fencing-lesson@1791230400000","name":"Fencing lesson","leave_by":"2026-10-05T19:32:37Z","alert_at":"2026-10-05T19:22:37Z","minutes":23}}""",
        ).jsonObject
        assertEquals(LeaveBy("activity/fencing-lesson", at("2026-10-05T19:32:37Z"), 23), leaveByOf(last))
        assertNull(leaveByOf(Json.parseToJsonElement("""{"next":null}""").jsonObject))
        assertNull(leaveByOf(null))
    }
}
