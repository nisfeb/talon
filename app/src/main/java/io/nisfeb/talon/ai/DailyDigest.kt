package io.nisfeb.talon.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DailyDigestEntity
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.ui.fetchLastKnownLocation
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Application-level facade for the daily digest. Owns scheduling and
 * the generation pipeline. See spec §Architecture / §Generation pipeline.
 *
 * Single instance per process. Uses `app.db` (the active ship's
 * database) at fire time; `sessionStore.activeShip()` snapshots which
 * ship is "active" for this fire.
 *
 * [receiverClass] is the BroadcastReceiver class for the alarm
 * PendingIntent — `DigestAlarmReceiver::class.java` once Task 12 lands.
 * Threaded as a parameter so this file compiles standalone before the
 * receiver class exists.
 */
class DailyDigest(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val activeShipFlow: StateFlow<String?>,
    private val getDb: () -> AppDatabase,
    private val aiSettings: AiSettings,
    private val aiClient: AiClient,
    private val settings: DailyDigestSettings,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val receiverClass: Class<*>,
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val weather = WeatherClient(http)

    /**
     * Stream the latest digest for the active ship. UI collects this
     * to render `DailyDigestScreen`. `flatMapLatest` re-keys when the
     * active ship changes.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun streamLatestForActiveShip(): Flow<DailyDigestEntity?> =
        activeShipFlow.flatMapLatest { ship ->
            if (ship == null) flowOf(null)
            else getDb().dailyDigests().streamLatestForShip(ship)
        }

    /**
     * Compute next fire time and arm the alarm. No-op when settings are
     * disabled. Cancels any existing alarm first to avoid double-arms.
     */
    fun scheduleNext() {
        val st = settings.state.value
        if (!st.enabled) {
            alarmManager.cancel(buildPendingIntent())
            return
        }
        val fireMs = DailyDigestSchedule.nextFireMs(
            now = Instant.now(),
            hourOfDay = st.hourOfDay,
            minuteOfDay = st.minuteOfDay,
            zone = ZoneId.systemDefault(),
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, fireMs, buildPendingIntent(),
        )
    }

    fun cancel() {
        alarmManager.cancel(buildPendingIntent())
    }

    /**
     * Run the full pipeline: fetch buckets, weather, AI summary; persist
     * and notify. Safe to call from receiver context (uses scope.launch
     * internally for the suspending work).
     *
     * [reason] is logged for diagnostics ("alarm" | "test-now" | "boot").
     */
    fun generateAndNotify(reason: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { runPipeline(reason) }
        }
    }

    private data class Bundle(
        val mentionMsgs: List<io.nisfeb.talon.data.MessageEntity>,
        val mentionPlain: Map<String, String>,
        val hits: List<io.nisfeb.talon.data.WatchwordHitEntity>,
        val uCands: Map<String, List<io.nisfeb.talon.data.MessageEntity>>,
        val uCounts: Map<String, Int>,
        val unreadPlain: Map<String, String>,
    )

    private suspend fun runPipeline(@Suppress("UNUSED_PARAMETER") reason: String) {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val windowEnd = nowMs
        val windowStart = nowMs - 24L * 60 * 60 * 1000
        val dateLocal = LocalDate.now(zone).toString()

        val ship = sessionStore.activeShip() ?: return
        val ourPatp = ship
        val db = getDb()

        // 1. Build buckets in parallel
        val bundle: Bundle = coroutineScope {
            val mentionsAsync = async {
                val candidates = db.messages().candidatesForMentionScan(
                    ourPatp = ourPatp,
                    windowStartMs = windowStart,
                    windowEndMs = windowEnd,
                    limit = 500,
                )
                val plainText = candidates.associate { m ->
                    m.id to StoryCache.textFor(m.id, m.contentJson)
                }
                candidates to plainText
            }
            val hitsAsync = async {
                db.watchwords().hitsInWindow(windowStart, windowEnd)
            }
            val unreadAsync = async {
                val excludes = db.watchwords().excludesAsList().toHashSet()
                val muted = db.notifyPrefs().mutedWhoms().toHashSet()
                val unreads = db.unreads().getAll().filter {
                    it.count > 0 && it.whom !in excludes && it.whom !in muted
                }
                val cands = LinkedHashMap<String, List<io.nisfeb.talon.data.MessageEntity>>()
                val counts = LinkedHashMap<String, Int>()
                val plain = mutableMapOf<String, String>()
                for (u in unreads) {
                    val msgs = db.messages().newestInChatForWindow(
                        whom = u.whom,
                        ourPatp = ourPatp,
                        windowStartMs = windowStart,
                        windowEndMs = windowEnd,
                        limit = u.count,
                    )
                    if (msgs.isNotEmpty()) {
                        cands[u.whom] = msgs
                        counts[u.whom] = u.count
                        for (m in msgs) {
                            plain[m.id] = StoryCache.textFor(m.id, m.contentJson)
                        }
                    }
                }
                Triple(cands, counts, plain)
            }
            val (mentionMsgs, mentionPlain) = mentionsAsync.await()
            val hits = hitsAsync.await()
            val (uCands, uCounts, uPlain) = unreadAsync.await()
            Bundle(mentionMsgs, mentionPlain, hits, uCands, uCounts, uPlain)
        }

        val items = DailyDigestSelector.assemble(
            ourPatp = ourPatp,
            mentionCandidates = bundle.mentionMsgs,
            mentionPlainText = bundle.mentionPlain,
            watchwordHits = bundle.hits,
            unreadCandidates = bundle.uCands,
            unreadCounts = bundle.uCounts,
            unreadPlainText = bundle.unreadPlain,
        )

        // 2. Quiet day check
        if (items.isEmpty()) return

        // 3. Weather (best-effort)
        val weatherToday = withTimeoutOrNull(5_000) {
            val loc = fetchLastKnownLocation(context) ?: return@withTimeoutOrNull null
            weather.fetchToday(loc.latitude, loc.longitude)
        }

        // 4. AI summary (best-effort)
        val aiCfg = aiSettings.state.value
        val summary = if (aiCfg.dailyDigestEnabled && aiCfg.hasKey()) {
            runCatching {
                val transcript = DailyDigestPrompt.format(items) { it }
                val sys = """
                    You are writing a brief morning digest for a chat user.
                    Cover the day in 3-5 bullets or a short paragraph.
                    Group by theme, not by chat. Highlight @mentions and
                    watchword hits as the user's priorities. Do not invent
                    information. Use only the transcript provided.
                """.trimIndent()
                aiClient.complete(sys, "Today's transcript:\n\n$transcript",
                    maxOutputTokens = 512)
            }.onFailure {
                // Don't swallow structured concurrency cancellations.
                if (it is kotlinx.coroutines.CancellationException) throw it
            }.getOrNull()
        } else null

        // 5. Persist
        val itemsJson = JSON.encodeToString(items)
        val weatherJson = weatherToday?.let { JSON.encodeToString(it) }
        db.dailyDigests().upsert(DailyDigestEntity(
            ship = ship,
            dateLocal = dateLocal,
            generatedAtMs = nowMs,
            summaryText = summary,
            itemsJson = itemsJson,
            weatherJson = weatherJson,
        ))
        val yesterday = LocalDate.parse(dateLocal).minusDays(1).toString()
        db.dailyDigests().pruneOlderThan(yesterday)

        // 6. Notify
        val title = "Today's brief: ${countLine(items)}"
        val body = summary?.take(120) ?: countLine(items)
        Notifications.showDailyDigest(
            context = context,
            ship = ship,
            dateLocal = dateLocal,
            title = title,
            body = body,
            generatedAtMs = nowMs,
        )
    }

    private fun countLine(items: List<DigestItem>): String {
        val byBucket = items.groupBy { it.bucket }
        val mentions = byBucket[Bucket.MENTION]?.size ?: 0
        val hits = byBucket[Bucket.WATCHWORD]?.size ?: 0
        val unread = byBucket[Bucket.UNREAD]?.size ?: 0
        fun pluralize(n: Int, singular: String, plural: String) =
            "$n ${if (n == 1) singular else plural}"
        return listOf(
            mentions to ("mention" to "mentions"),
            hits to ("hit" to "hits"),
            unread to ("unread" to "unread"),
        ).filter { it.first > 0 }.joinToString(", ") { (n, words) ->
            pluralize(n, words.first, words.second)
        }
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, receiverClass).apply {
            action = ACTION_DAILY_DIGEST_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_DIGEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Acquire a partial wake lock for [tag] with a 60s timeout. The
     * receiver hands this back to the alarm subsystem in finally.
     */
    fun acquireWakeLock(tag: String): PowerManager.WakeLock {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talon:$tag").apply {
            setReferenceCounted(false)
            acquire(TimeUnit.SECONDS.toMillis(60))
        }
    }

    companion object {
        const val ACTION_DAILY_DIGEST_FIRE = "io.nisfeb.talon.action.DAILY_DIGEST_FIRE"
        private const val REQ_DIGEST = 7700
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
