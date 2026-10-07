package io.nisfeb.talon.orrery

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Time to leave, as the phone keeps it: orrery 69's travel and travel/last read into a plan, and what the alarm does. */
class LeaveTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val on = obj("""{"enabled": true, "lead_min": 10, "position_at": "2026-10-04T13:00:00Z"}""")
    // As +leave-record writes it.
    private val last = obj(
        """{"at": "2026-10-04T13:05:00Z", "notes": [], "alerted": [],
            "next": {"key": "situation/dentist@1791127800000", "id": "situation/dentist", "name": "Dentist",
                     "starts": "2026-10-04T15:30:00Z", "leave_by": "2026-10-04T15:02:00Z", "alert_at": "2026-10-04T14:52:00Z",
                     "minutes": 23, "from": "position", "verdict": "yes"}}""",
    )
    private val plan = LeavePlan("situation/dentist@1791127800000", "Dentist", 1_791_126_120_000, 1_791_125_520_000, 23, startsMs = 1_791_127_800_000)

    @Test
    fun `the plan is the pass's next, when time to leave is on`() {
        assertEquals(plan, leavePlanOf(on, last))
    }

    @Test
    fun `off is no plan, whatever the last pass left behind`() {
        assertNull(leavePlanOf(obj("""{"enabled": false, "lead_min": 10}"""), last))
        assertNull(leavePlanOf(obj("""{"lead_min": 10}"""), last), "no flag is not on")
    }

    @Test
    fun `nothing ahead, or a next without its times, is no plan`() {
        assertNull(leavePlanOf(on, obj("""{"at": "x", "notes": ["nothing ahead to leave for"], "next": null}""")))
        assertNull(leavePlanOf(on, obj("""{"next": {"key": "k", "name": "n"}}""")))
        assertNull(leavePlanOf(on, obj("""{"next": {"key": "", "leave_by": "2026-10-04T15:02:00Z", "alert_at": "2026-10-04T14:52:00Z"}}""")))
    }

    @Test
    fun `the alarm is a minute behind the push, and says what the push says`() {
        assertEquals(plan.alertAtMs + 60_000, leaveAlarmAtMs(plan))
        assertEquals("Leave in 9 min for Dentist", leaveTitle(plan, leaveAlarmAtMs(plan)))
        assertEquals("Leave now for Dentist", leaveTitle(plan, plan.leaveByMs))
        assertEquals("Leave now for Dentist", leaveTitle(plan, plan.leaveByMs + 120_000), "late is now, not minus")
        assertEquals("23 min with traffic; leave by 15:02", leaveBody(plan, TimeZone.UTC))
    }

    @Test
    fun `only a leave push's tag names an occurrence`() {
        assertEquals("situation/dentist@1791127800000", leaveKeyOfTag("orrery-leave-situation/dentist@1791127800000"))
        assertNull(leaveKeyOfTag("orrery-brief"))
        assertNull(leaveKeyOfTag("orrery-leave-"))
        assertNull(leaveKeyOfTag(null))
    }

    @Test
    fun `when it fires, no answer rings, off drops, moved later moves, the same rings`() {
        val now = leaveAlarmAtMs(plan)
        assertEquals(LeaveDecision.Ring(plan), leaveDecision(plan, null, answered = false, nowMs = now), "the push likely failed for the same reason")
        assertEquals(LeaveDecision.Drop, leaveDecision(plan, null, answered = true, nowMs = now))
        val later = plan.copy(leaveByMs = plan.leaveByMs + 600_000, alertAtMs = plan.alertAtMs + 600_000)
        assertEquals(LeaveDecision.Move(later), leaveDecision(plan, later, answered = true, nowMs = now))
        val other = plan.copy(key = "activity/swim@1791131400000")
        assertEquals(LeaveDecision.Move(other), leaveDecision(plan, other, answered = true, nowMs = now))
        val slightly = plan.copy(alertAtMs = plan.alertAtMs + 20_000)
        assertEquals(LeaveDecision.Ring(slightly), leaveDecision(plan, slightly, answered = true, nowMs = now), "seconds later is not worth waiting for")
    }
}
