package io.nisfeb.talon.orrery

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.ExerciseSessionRecord.Companion as E
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.runSuspendCatching
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.toJavaInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * The phone sends orrery each day's health from Health Connect: steps,
 * workouts and sleep, as [healthPlan] says and [healthBody] writes.
 *
 * Android-only for now. iOS port pending: HealthKit's statistics and
 * sample queries give the same days. Desktop has no analog: a computer
 * holds no health data.
 *
 * A worker sends what is due when the process starts (opening the app,
 * when Health Connect lets Talon read) and every [EVERY_HOURS] hours,
 * which reads only where the owner also allowed reading in the
 * background; without it, today goes up when the app is opened.
 * Health Connect is part of Android from 14 (GrapheneOS included) and an
 * app before it.
 */
object HealthWatch {
    private const val PREFS = "talon_orrery_health"
    private const val KEY_ON = "on"
    private const val NOW = "talon-orrery-health-now"
    private const val EVERY = "talon-orrery-health"
    private const val EVERY_HOURS = 3L

    /** What is read, and nothing else: no heart rate, no route, no raw samples leave the phone. */
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )
    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    private var flow: MutableStateFlow<Boolean>? = null

    @Synchronized
    fun on(ctx: Context): StateFlow<Boolean> = flow ?: MutableStateFlow(isOn(ctx)).also { flow = it }

    private fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun available(ctx: Context): Boolean = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun client(ctx: Context): HealthConnectClient = HealthConnectClient.getOrCreate(ctx)

    /** Whether this Health Connect can grant reading in the background at all. */
    fun backgroundAvailable(ctx: Context): Boolean =
        client(ctx).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun granted(ctx: Context): Set<String> = client(ctx).permissionController.getGrantedPermissions()

    fun set(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        (on(ctx) as MutableStateFlow).value = on
        if (on) {
            resume(ctx)
        } else {
            WorkManager.getInstance(ctx).cancelUniqueWork(EVERY)
            WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
        }
    }

    /** On every start of the process: the timer kept (KEEP), and one pass now. */
    fun resume(ctx: Context) {
        if (!isOn(ctx) || !available(ctx)) return
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            EVERY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HealthWorker>(EVERY_HOURS, TimeUnit.HOURS).setConstraints(net).build(),
        )
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<HealthWorker>().setConstraints(net).build())
    }
}

/** One pass: whatever days are due, for the active ship. */
class HealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TalonApplication ?: return Result.success()
        val session = app.sessionStore.active() ?: return Result.success()
        if (!HealthWatch.available(app)) return Result.success()
        return sendHealth(
            app.session.http, app.db, session.shipUrl, session.ship,
            HealthConnectSource(app), TimeZone.currentSystemDefault(), System.currentTimeMillis(),
        ).fold(
            onSuccess = { n ->
                if (n > 0) Log.i("HealthWorker", "health sent: $n day(s)")
                Result.success()
            },
            onFailure = {
                Log.w("HealthWorker", "health not sent: ${it.message}")
                if (runAttemptCount < 3) Result.retry() else Result.success()
            },
        )
    }
}

/**
 * A local day from Health Connect. Steps are its own per-day total,
 * which counts once what a phone and a watch both recorded. A workout
 * belongs to the day it began; sleep to the day it ended.
 */
class HealthConnectSource(private val ctx: Context) : HealthSource {
    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }
    private var recordsWorkouts: Boolean? = null

    override suspend fun day(day: LocalDate, zone: TimeZone, partial: Boolean): HealthDay? = runSuspendCatching {
        val start = day.atStartOfDayIn(zone).toJavaInstant()
        val end = day.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toJavaInstant()
        val inDay = TimeRangeFilter.between(start, end)
        val steps = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), inDay))[StepsRecord.COUNT_TOTAL]
        val workouts = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, inDay)).records
            .filter { !it.startTime.isBefore(start) && it.startTime.isBefore(end) }
            .map { Workout(exerciseName(it.exerciseType), it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
        val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.minus(Duration.ofDays(1)), end))).records
            .filter { !it.endTime.isBefore(start) && it.endTime.isBefore(end) }
            .map { HealthSpan(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
        HealthDay(day, steps, activeMinutes(workouts, workouts.isNotEmpty() || recordsWorkouts()), sleep, workouts, partial)
    }.onFailure {
        // Not granted, or the app is in the background without leave to read there.
        Log.i("HealthConnectSource", "health for $day not read: ${it.message}")
    }.getOrNull()

    /** Whether anything on the phone records workouts: one in the last two weeks. */
    private suspend fun recordsWorkouts(): Boolean = recordsWorkouts ?: client.readRecords(
        ReadRecordsRequest(
            ExerciseSessionRecord::class,
            TimeRangeFilter.after(java.time.Instant.now().minus(Duration.ofDays(HEALTH_BACKFILL_DAYS.toLong()))),
            pageSize = 1,
        ),
    ).records.isNotEmpty().also { recordsWorkouts = it }
}

/** Health Connect's exercise type by its constant's name, lowercased without the prefix; anything else is "other". */
internal fun exerciseName(type: Int): String = EXERCISE_NAMES[type] ?: "other"

private val EXERCISE_NAMES = mapOf(
    E.EXERCISE_TYPE_BADMINTON to "badminton", E.EXERCISE_TYPE_BASEBALL to "baseball", E.EXERCISE_TYPE_BASKETBALL to "basketball",
    E.EXERCISE_TYPE_BIKING to "biking", E.EXERCISE_TYPE_BIKING_STATIONARY to "biking_stationary", E.EXERCISE_TYPE_BOOT_CAMP to "boot_camp",
    E.EXERCISE_TYPE_BOXING to "boxing", E.EXERCISE_TYPE_CALISTHENICS to "calisthenics", E.EXERCISE_TYPE_CRICKET to "cricket",
    E.EXERCISE_TYPE_DANCING to "dancing", E.EXERCISE_TYPE_ELLIPTICAL to "elliptical", E.EXERCISE_TYPE_EXERCISE_CLASS to "exercise_class",
    E.EXERCISE_TYPE_FENCING to "fencing", E.EXERCISE_TYPE_FOOTBALL_AMERICAN to "football_american",
    E.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "football_australian", E.EXERCISE_TYPE_FRISBEE_DISC to "frisbee_disc", E.EXERCISE_TYPE_GOLF to "golf",
    E.EXERCISE_TYPE_GUIDED_BREATHING to "guided_breathing", E.EXERCISE_TYPE_GYMNASTICS to "gymnastics", E.EXERCISE_TYPE_HANDBALL to "handball",
    E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "high_intensity_interval_training", E.EXERCISE_TYPE_HIKING to "hiking",
    E.EXERCISE_TYPE_ICE_HOCKEY to "ice_hockey", E.EXERCISE_TYPE_ICE_SKATING to "ice_skating", E.EXERCISE_TYPE_MARTIAL_ARTS to "martial_arts",
    E.EXERCISE_TYPE_PADDLING to "paddling", E.EXERCISE_TYPE_PARAGLIDING to "paragliding", E.EXERCISE_TYPE_PILATES to "pilates",
    E.EXERCISE_TYPE_RACQUETBALL to "racquetball", E.EXERCISE_TYPE_ROCK_CLIMBING to "rock_climbing", E.EXERCISE_TYPE_ROLLER_HOCKEY to "roller_hockey",
    E.EXERCISE_TYPE_ROWING to "rowing", E.EXERCISE_TYPE_ROWING_MACHINE to "rowing_machine", E.EXERCISE_TYPE_RUGBY to "rugby",
    E.EXERCISE_TYPE_RUNNING to "running", E.EXERCISE_TYPE_RUNNING_TREADMILL to "running_treadmill", E.EXERCISE_TYPE_SAILING to "sailing",
    E.EXERCISE_TYPE_SCUBA_DIVING to "scuba_diving", E.EXERCISE_TYPE_SKATING to "skating", E.EXERCISE_TYPE_SKIING to "skiing",
    E.EXERCISE_TYPE_SNOWBOARDING to "snowboarding", E.EXERCISE_TYPE_SNOWSHOEING to "snowshoeing", E.EXERCISE_TYPE_SOCCER to "soccer",
    E.EXERCISE_TYPE_SOFTBALL to "softball", E.EXERCISE_TYPE_SQUASH to "squash", E.EXERCISE_TYPE_STAIR_CLIMBING to "stair_climbing",
    E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "stair_climbing_machine", E.EXERCISE_TYPE_STRENGTH_TRAINING to "strength_training",
    E.EXERCISE_TYPE_STRETCHING to "stretching", E.EXERCISE_TYPE_SURFING to "surfing", E.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "swimming_open_water",
    E.EXERCISE_TYPE_SWIMMING_POOL to "swimming_pool", E.EXERCISE_TYPE_TABLE_TENNIS to "table_tennis", E.EXERCISE_TYPE_TENNIS to "tennis",
    E.EXERCISE_TYPE_VOLLEYBALL to "volleyball", E.EXERCISE_TYPE_WALKING to "walking", E.EXERCISE_TYPE_WATER_POLO to "water_polo",
    E.EXERCISE_TYPE_WEIGHTLIFTING to "weightlifting", E.EXERCISE_TYPE_WHEELCHAIR to "wheelchair", E.EXERCISE_TYPE_YOGA to "yoga",
)
