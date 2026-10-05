package io.nisfeb.talon.orrery

import io.nisfeb.talon.ui.parseIsoUtc
import io.nisfeb.talon.urbit.asText
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Time to leave (orrery 69): the ship routes the owner's next appointment
 * from where they are, writes `leave-by`, and pushes "Leave in 10 min for
 * X" once, tagged [LEAVE_TAG_PREFIX] + [LeavePlan.key]. The phone keeps an
 * alarm a minute behind that push, and the push, when it comes, cancels it.
 * This is the part every platform shares; the alarm is the phone's.
 */
data class LeavePlan(
    /** `<body id>@<start in epoch ms>`: one occurrence. */
    val key: String,
    val name: String,
    val leaveByMs: Long,
    /** When the ship pushes: leave-by less the owner's lead. */
    val alertAtMs: Long,
    /** The drive, with traffic. */
    val minutes: Int,
    /** The time to be there (for a pick-up, the appointment's end); null from a ship before orrery 73. */
    val startsMs: Long? = null,
)

const val LEAVE_TAG_PREFIX = "orrery-leave-"

/** The occurrence a ship push is about, when it is a leave push. */
fun leaveKeyOfTag(tag: String?): String? =
    tag?.takeIf { it.startsWith(LEAVE_TAG_PREFIX) }?.removePrefix(LEAVE_TAG_PREFIX)?.takeIf { it.isNotBlank() }

/**
 * The plan to keep an alarm for: [last]'s `next` (GET /api/travel/last),
 * when [travel] (GET /api/travel) is enabled. Off, the pass stops writing
 * and its last plan goes stale, so off is no plan whatever `next` says.
 */
fun leavePlanOf(travel: JsonObject, last: JsonObject): LeavePlan? {
    if (travel["enabled"]?.jsonPrimitive?.booleanOrNull != true) return null
    val next = last["next"] as? JsonObject ?: return null
    fun ms(k: String) = next[k].asText()?.let(::parseIsoUtc)
    return LeavePlan(
        key = next["key"].asText()?.takeIf { it.isNotBlank() } ?: return null,
        name = next["name"].asText().orEmpty(),
        leaveByMs = ms("leave_by") ?: return null,
        alertAtMs = ms("alert_at") ?: return null,
        minutes = next["minutes"]?.jsonPrimitive?.intOrNull ?: 0,
        startsMs = ms("starts"),
    )
}

/** A minute after the ship's push is due: the push has had its chance. */
fun leaveAlarmAtMs(plan: LeavePlan): Long = plan.alertAtMs + 60_000

/** As the ship's push says it: "Leave in 9 min for X", or "Leave now for X". */
fun leaveTitle(plan: LeavePlan, nowMs: Long): String {
    val left = ((plan.leaveByMs - nowMs) / 60_000).coerceAtLeast(0)
    return if (left == 0L) "Leave now for ${plan.name}" else "Leave in $left min for ${plan.name}"
}

/** "25 min with traffic; leave by 14:35", in [zone]. */
fun leaveBody(plan: LeavePlan, zone: TimeZone): String =
    "${plan.minutes} min with traffic; leave by ${OrreryText.clock(plan.leaveByMs, zone)}"

/**
 * The plan from the ship, or null when time to leave is off or there is
 * nothing ahead. Two reads: the settings under the key, then, only when
 * on, the pass's record under the owner's session.
 */
suspend fun readLeavePlan(api: OrreryApi, token: String): LeavePlan? {
    val travel = api.travel(token)
    if (travel["enabled"]?.jsonPrimitive?.booleanOrNull != true) return null
    return leavePlanOf(travel, api.travelLast())
}

/** What the alarm does when it fires. */
sealed interface LeaveDecision {
    data class Ring(val plan: LeavePlan) : LeaveDecision
    data class Move(val plan: LeavePlan) : LeaveDecision
    data object Drop : LeaveDecision
}

/**
 * The alarm for [stored] has fired and the ship was asked again: [fresh]
 * is its plan now, [answered] whether it said anything at all. No answer
 * rings, since a phone that cannot reach the ship is the likeliest reason
 * the push did not come. Off or nothing ahead drops it. Another
 * occurrence, or this one moved later, moves the alarm.
 */
fun leaveDecision(stored: LeavePlan, fresh: LeavePlan?, answered: Boolean, nowMs: Long): LeaveDecision = when {
    !answered -> LeaveDecision.Ring(stored)
    fresh == null -> LeaveDecision.Drop
    fresh.key != stored.key -> LeaveDecision.Move(fresh)
    leaveAlarmAtMs(fresh) > nowMs + 30_000 -> LeaveDecision.Move(fresh)
    else -> LeaveDecision.Ring(fresh)
}
