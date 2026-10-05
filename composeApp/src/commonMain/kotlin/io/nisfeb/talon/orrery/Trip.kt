package io.nisfeb.talon.orrery

/**
 * Trip mode: while the owner is on the way to an appointment the phone
 * says where it is every minute rather than every ten, so the ship sees
 * them running late, and arriving, when it happens (orrery 73 learns how
 * long each place takes from these fixes). A trip runs from the leave
 * alert ([LeavePlan.alertAtMs]) until [TRIP_AFTER_MS] past the time to be
 * there. This is the part every platform shares; the listening is the
 * phone's.
 */
data class Trip(
    /** The occurrence, as the plan names it: `<kind>/<slug>@<start ms>`, with `/drop` or `/pick` for a leg. */
    val key: String,
    val name: String,
    val fromMs: Long,
    val untilMs: Long,
) {
    /** From a little before the alert time, which moves by seconds as the ship refreshes the plan and the push can beat. */
    fun live(nowMs: Long): Boolean = nowMs in (fromMs - TRIP_LEAD_MS) until untilMs
}

/** How early a trip may begin: the ship pushed the first leave alert 8 s before its own alert_at. */
const val TRIP_LEAD_MS = 5 * 60_000L

/** How long a trip outlasts the time to be there: arriving late is what it is for. */
const val TRIP_AFTER_MS = 15 * 60_000L

/** A fix at least this often on a trip, against ten minutes otherwise. */
const val TRIP_GAP_MS = 60_000L

/** And on a move this far, against 400 m otherwise. */
const val TRIP_MOVE_M = 100f

/** The trip for [plan], or null from a ship too old to say when to be there. */
fun tripOf(plan: LeavePlan): Trip? = plan.startsMs?.let { Trip(plan.key, plan.name, plan.alertAtMs, it + TRIP_AFTER_MS) }
