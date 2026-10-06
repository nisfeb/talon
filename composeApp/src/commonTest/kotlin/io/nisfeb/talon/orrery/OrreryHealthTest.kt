package io.nisfeb.talon.orrery

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which days go up and what each says: the half of health every target runs. */
class OrreryHealthTest {
    private val today = LocalDate(2026, 10, 6)
    private val now = 1_791_300_000_000L
    private val hour = 60 * 60_000L

    private fun days(plan: List<HealthSend>) = plan.map { "${it.day}${if (it.partial) "*" else ""}" }

    @Test
    fun `the first run sends two weeks of finished days, oldest first, then today still filling`() {
        val plan = healthPlan(today, lastFinal = null, lastToday = null, lastTodayAtMs = null, nowMs = now)
        assertEquals(HEALTH_BACKFILL_DAYS + 1, plan.size)
        assertEquals("2026-09-22", plan.first().day.toString())
        assertEquals(listOf("2026-10-05", "2026-10-06*"), days(plan).takeLast(2))
        assertTrue(plan.dropLast(1).none { it.partial }, "only today is partial")
    }

    @Test
    fun `a phone that was off three days sends those three, and never more than two weeks`() {
        assertEquals(
            listOf("2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06*"),
            days(healthPlan(today, LocalDate(2026, 10, 2), LocalDate(2026, 10, 2), now - 80 * hour, now)),
        )
        val longAway = healthPlan(today, LocalDate(2026, 6, 1), null, null, now)
        assertEquals("2026-09-22", longAway.first().day.toString())
    }

    @Test
    fun `today goes up at most every three hours, and again as soon as the day turns`() {
        assertEquals(emptyList(), healthPlan(today, LocalDate(2026, 10, 5), today, now - 2 * hour, now))
        assertEquals(listOf("2026-10-06*"), days(healthPlan(today, LocalDate(2026, 10, 5), today, now - 3 * hour, now)))
        // Sent at 23:30 yesterday: yesterday is now finished, and today is new.
        assertEquals(
            listOf("2026-10-05", "2026-10-06*"),
            days(healthPlan(today, LocalDate(2026, 10, 4), LocalDate(2026, 10, 5), now - 1 * hour, now)),
        )
    }

    @Test
    fun `a last finished day in the future sends only today`() {
        assertEquals(listOf("2026-10-06*"), days(healthPlan(today, LocalDate(2026, 10, 9), null, null, now)))
    }

    @Test
    fun `active minutes count a stretch two workouts share once, and are null where nothing records workouts`() {
        val m = 60_000L
        val w = listOf(
            Workout("running", 0, 30 * m),
            Workout("walking", 20 * m, 50 * m),
            Workout("yoga", 120 * m, 135 * m),
            Workout("other", 200 * m, 190 * m),
        )
        assertEquals(65, activeMinutes(w, recordsWorkouts = true))
        assertEquals(0, activeMinutes(emptyList(), recordsWorkouts = true), "a day without one")
        assertNull(activeMinutes(emptyList(), recordsWorkouts = false), "nothing on the phone records them")
        assertEquals(30, activeMinutes(listOf(Workout("a", 0, 30 * m), Workout("b", 5 * m, 10 * m)), true), "one inside another")
    }

    // Checked against orrery 74's +health-doc (code/lib/orrery.hoon,
    // 7add097): day YYYY-MM-DD, whole numbers or null, spans of ISO
    // instants, type on workouts only, partial a boolean.
    @Test
    fun `the body is what orrery's health route reads`() {
        val day = HealthDay(
            day = LocalDate(2026, 10, 6),
            steps = 4210,
            activeMinutes = 23,
            sleep = listOf(HealthSpan(1_791_262_500_250L, 1_791_285_600_999L)),
            workouts = listOf(Workout("fencing", 1_791_321_600_000L, 1_791_325_200_000L)),
            partial = true,
        )
        assertEquals(
            """{"day":"2026-10-06","steps":4210,"active_minutes":23,""" +
                """"sleep":[{"start":"2026-10-06T04:55:00Z","end":"2026-10-06T11:20:00Z"}],""" +
                """"workouts":[{"type":"fencing","start":"2026-10-06T21:20:00Z","end":"2026-10-06T22:20:00Z"}],"partial":true}""",
            healthBody(day),
        )
        assertEquals(
            """{"day":"2026-10-05","steps":null,"active_minutes":null,"sleep":[],"workouts":[],"partial":false}""",
            healthBody(HealthDay(LocalDate(2026, 10, 5), null, null, emptyList(), emptyList(), partial = false)),
        )
    }
}
