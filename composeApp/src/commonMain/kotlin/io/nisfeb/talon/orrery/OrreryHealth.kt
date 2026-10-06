package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrrerySentDao
import io.nisfeb.talon.data.OrrerySentEntity
import io.nisfeb.talon.util.Log
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A day of health from the phone, for orrery (74): steps, workouts and
 * sleep, summed per local day on the phone. Never raw samples, heart
 * rate or where. The ship keeps it owner-only and learns a baseline from
 * two weeks of it. Reading is the platform's ([HealthSource]); which days
 * go up, and what each body says, is here, so every target tests it.
 */
data class HealthDay(
    val day: LocalDate,
    val steps: Long?,
    /** Workout minutes, overlaps once ([activeMinutes]); null where nothing on the phone records workouts. */
    val activeMinutes: Long?,
    /** The sleep sessions that ended this day: the night that ended this morning, and any nap. */
    val sleep: List<HealthSpan>,
    val workouts: List<Workout>,
    /** Today, still filling. */
    val partial: Boolean,
)

data class HealthSpan(val startMs: Long, val endMs: Long)

/** [type] is the platform's name for it, lowercased: "running", "fencing", or "other". */
data class Workout(val type: String, val startMs: Long, val endMs: Long)

/**
 * The day's workout minutes, a stretch two workouts share counted once;
 * null where nothing on the phone records workouts at all, which is not
 * the same as a day without one.
 */
fun activeMinutes(workouts: List<Workout>, recordsWorkouts: Boolean): Long? {
    if (!recordsWorkouts) return null
    var total = 0L
    var reached = Long.MIN_VALUE
    for (w in workouts.filter { it.endMs > it.startMs }.sortedBy { it.startMs }) {
        val from = maxOf(w.startMs, reached)
        if (w.endMs > from) total += w.endMs - from
        reached = maxOf(reached, w.endMs)
    }
    return total / 60_000
}

/**
 * The body orrery's POST /api/health takes (its +health-doc): `day` as
 * YYYY-MM-DD, whole numbers or null, spans as ISO instants. Times go to
 * the whole second, the form +de-iso reads in /api/position's `at` too.
 */
fun healthBody(d: HealthDay): String = buildJsonObject {
    put("day", d.day.toString())
    put("steps", d.steps)
    put("active_minutes", d.activeMinutes)
    putJsonArray("sleep") {
        d.sleep.forEach { s -> addJsonObject { put("start", isoSecond(s.startMs)); put("end", isoSecond(s.endMs)) } }
    }
    putJsonArray("workouts") {
        d.workouts.forEach { w -> addJsonObject { put("type", w.type); put("start", isoSecond(w.startMs)); put("end", isoSecond(w.endMs)) } }
    }
    put("partial", d.partial)
}.toString()

private fun isoSecond(ms: Long) = isoUtc(ms - ms.mod(1000L))

/** A day to send, and whether it is still filling. */
data class HealthSend(val day: LocalDate, val partial: Boolean)

/**
 * Which days go up now, oldest first. Every finished day after
 * [lastFinal] up to yesterday, at most [HEALTH_BACKFILL_DAYS] back: the
 * first run sends two weeks, and a phone that was off three days sends
 * those three. Then today, still filling, unless today went up less
 * than [HEALTH_TODAY_EVERY_MS] ago. A later post of a day replaces it on
 * the ship, so sending one twice costs nothing but the request.
 */
fun healthPlan(today: LocalDate, lastFinal: LocalDate?, lastToday: LocalDate?, lastTodayAtMs: Long?, nowMs: Long): List<HealthSend> {
    val earliest = today.minus(DatePeriod(days = HEALTH_BACKFILL_DAYS))
    val from = lastFinal?.plus(DatePeriod(days = 1))?.let { maxOf(it, earliest) } ?: earliest
    val finished = generateSequence(from) { it.plus(DatePeriod(days = 1)) }.takeWhile { it < today }.map { HealthSend(it, false) }
    val todayDue = lastToday != today || lastTodayAtMs == null || nowMs - lastTodayAtMs >= HEALTH_TODAY_EVERY_MS
    return finished.toList() + listOfNotNull(HealthSend(today, partial = true).takeIf { todayDue })
}

const val HEALTH_BACKFILL_DAYS = 14
const val HEALTH_TODAY_EVERY_MS = 3 * 60 * 60_000L

/**
 * Where a day's health comes from: Health Connect on Android. Null for
 * a day that cannot be read now (no permission, or the app is in the
 * background without leave to read there), which stops the pass and
 * leaves the day for the next one.
 */
interface HealthSource {
    suspend fun day(day: LocalDate, zone: TimeZone, partial: Boolean): HealthDay?
}

object NoopHealthSource : HealthSource {
    override suspend fun day(day: LocalDate, zone: TimeZone, partial: Boolean): HealthDay? = null
}

/**
 * Send what [healthPlan] says is due, under this install's key, oldest
 * first, and keep per ship what went up: the last finished day, and
 * when today last did. A day that cannot be read, or a refusal, stops
 * the pass with what is left still due. An orrery without the route
 * (before 74) answers 404: remembered a day per ship, as the position
 * is. Answers how many days went up.
 */
suspend fun sendHealth(
    http: HttpClient,
    db: AppDatabase,
    url: String,
    ship: String,
    source: HealthSource,
    zone: TimeZone,
    nowMs: Long,
    /** The key's client, which carries no cookie. */
    bare: HttpClient = keyClient,
): Result<Int> = runCatching {
    val token = db.orreryAccounts().get(ship)?.token ?: return Result.success(0)
    val sent = db.orrerySent()
    sent.get(ship, HEALTH_MISSING)?.let { if (nowMs - it.atMs < DAY_MS) return Result.success(0) }
    val api = OrreryApi(http, bare, url)
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    val lastToday = sent.get(ship, HEALTH_TODAY)
    val plan = healthPlan(today, sent.dayOf(ship, HEALTH_FINAL), sent.dayOf(ship, HEALTH_TODAY), lastToday?.atMs, nowMs)
    var n = 0
    for (s in plan) {
        val day = source.day(s.day, zone, s.partial) ?: break
        try {
            api.postHealth(token, healthBody(day))
        } catch (e: OrreryError.Refused) {
            if (e.status != 404) throw e
            sent.put(OrrerySentEntity(ship, HEALTH_MISSING, "", nowMs))
            Log.i("OrreryHealth", "this orrery takes no health (before 74); asking again in a day")
            return Result.success(n)
        }
        sent.put(OrrerySentEntity(ship, if (s.partial) HEALTH_TODAY else HEALTH_FINAL, s.day.toString(), nowMs))
        n++
    }
    n
}

private suspend fun OrrerySentDao.dayOf(ship: String, key: String): LocalDate? =
    get(ship, key)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private const val HEALTH_FINAL = "health:final"
private const val HEALTH_TODAY = "health:today"
private const val HEALTH_MISSING = "health:missing"
