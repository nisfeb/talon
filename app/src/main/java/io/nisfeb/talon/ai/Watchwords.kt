package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.WatchwordChatExcludeEntity
import io.nisfeb.talon.data.WatchwordEntity
import io.nisfeb.talon.data.WatchwordHitEntity
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Hard cap on hit rows kept per term. See spec §Decisions / §Performance. */
const val MAX_HITS_PER_TERM = 1000

/**
 * Word-boundary substring match. Case-insensitive. Punctuation-tolerant.
 *
 * "Mars" matches "Mars Society" / "(Mars)" / "Mars!" but not "Marshmallow".
 * "C++" matches "I love C++" because both sides are non-letters.
 * Multi-word phrases match the literal substring; internal whitespace is
 * matched as-is (so "Mars Society" does NOT match "Mars\nSociety").
 */
internal fun matchesWordBoundary(haystack: String, needle: String): Boolean {
    if (needle.isEmpty()) return false
    val h = haystack.lowercase()
    val n = needle.lowercase()
    var i = 0
    while (true) {
        val found = h.indexOf(n, startIndex = i)
        if (found < 0) return false
        val before = if (found == 0) ' ' else h[found - 1]
        val end = found + n.length
        val after = if (end >= h.length) ' ' else h[end]
        if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
        i = found + 1
    }
}

/**
 * Stable, lower-cased, alphanumerics-only key for a term — used as the
 * %settings entry key when sync is on. Two terms that produce the same
 * key collide on the ship side; the manage UI surfaces a warning.
 */
internal fun sanitizeTerm(term: String): String =
    term.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** A live match against an incoming message. */
data class MatchedTerm(val term: WatchwordEntity, val snippet: String)

/** Events emitted to [Watchwords.onChange] so TalonApplication can mirror to %settings. */
sealed class WatchwordChange {
    data class Upsert(val term: WatchwordEntity) : WatchwordChange()
    data class Remove(val termText: String) : WatchwordChange()
    data class Exclude(val whom: String) : WatchwordChange()
    data class Unexclude(val whom: String) : WatchwordChange()
    /** Sync just toggled on — push the entire local state so the ship matches. */
    data object SyncToggled : WatchwordChange()
}

/**
 * App-level facade for watchwords. Mirrors [io.nisfeb.talon.ai.AiSettings]'s
 * shape — a hot StateFlow of the live state, suspend mutators, and an
 * [onChange] hook wired by TalonApplication after SettingsSync exists.
 *
 * Each ship has its own Watchwords instance because terms / excludes
 * live in the per-ship [AppDatabase].
 */
class Watchwords(
    private val db: AppDatabase,
    private val ourPatpProvider: () -> String,
    private val scope: CoroutineScope,
    /** Backed by SharedPreferences in TalonApplication; just a flag for
     *  whether to mirror to %settings. Decoupled from this class so the
     *  prefs file can stay alongside the rest of the app's settings. */
    private val syncEnabledProvider: () -> Boolean,
) {

    val terms: StateFlow<List<WatchwordEntity>> =
        db.watchwords().streamTerms()
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val excludes: StateFlow<Set<String>> =
        db.watchwords().streamExcludes()
            .map { rows -> rows.map { it.whom }.toHashSet() as Set<String> }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _backfilling = MutableStateFlow<Set<Long>>(emptySet())
    /** Term ids currently running a backfill scan. UI shows a spinner per id. */
    val backfilling: StateFlow<Set<Long>> = _backfilling.asStateFlow()

    /** Tracks active backfill jobs so [remove] can cancel them mid-flight. */
    private val backfillJobs = ConcurrentHashMap<Long, Job>()

    /**
     * Wired by TalonApplication once SettingsSync exists. Receives
     * [WatchwordChange] events and (if syncEnabled) mirrors them to %settings.
     * The [transitionedOffSync] flag fires a one-time `clearWatchwordsOnShip`
     * — same idiom AiSettings uses.
     */
    @Volatile var onChange: ((WatchwordChange, transitionedOffSync: Boolean) -> Unit)? = null

    suspend fun add(term: String, notify: Boolean): Long {
        val trimmed = term.trim()
        require(trimmed.isNotEmpty()) { "watchword cannot be empty" }
        val id = db.watchwords().upsertTerm(
            WatchwordEntity(
                term = trimmed,
                notify = notify,
                createdMs = System.currentTimeMillis(),
            )
        )
        val saved = db.watchwords().getTerm(id) ?: return id
        onChange?.invoke(WatchwordChange.Upsert(saved), false)
        // Kick off backfill in the background; tracked in [_backfilling].
        startBackfill(saved)
        return id
    }

    suspend fun remove(termId: Long) {
        val term = db.watchwords().getTerm(termId) ?: return
        backfillJobs.remove(termId)?.cancel()
        _backfilling.value = _backfilling.value - termId
        db.watchwords().clearHitsForTerm(term.term)
        db.watchwords().deleteTermById(termId)
        onChange?.invoke(WatchwordChange.Remove(term.term), false)
    }

    suspend fun setNotify(termId: Long, notify: Boolean) {
        db.watchwords().setNotify(termId, notify)
        val updated = db.watchwords().getTerm(termId) ?: return
        onChange?.invoke(WatchwordChange.Upsert(updated), false)
    }

    suspend fun clearHits(termId: Long) {
        val term = db.watchwords().getTerm(termId) ?: return
        db.watchwords().clearHitsForTerm(term.term)
    }

    suspend fun excludeChat(whom: String, excluded: Boolean) {
        if (excluded) {
            db.watchwords().upsertExclude(WatchwordChatExcludeEntity(whom))
            onChange?.invoke(WatchwordChange.Exclude(whom), false)
        } else {
            db.watchwords().deleteExclude(whom)
            onChange?.invoke(WatchwordChange.Unexclude(whom), false)
        }
    }

    /**
     * Per-message live evaluation. Caller (TalonApp.kt's messageListener)
     * has already filtered: not self, not muted, not in excludes.
     * This walks current terms, tests each, persists hits with the lazy
     * prune rule, and returns the matches so the caller can fire
     * notifications for the notify=ON ones.
     */
    suspend fun evaluateLive(msg: MessageEntity, plainText: String): List<MatchedTerm> {
        val current = terms.value
        if (current.isEmpty()) return emptyList()
        val out = ArrayList<MatchedTerm>(2)
        for (t in current) {
            if (matchesWordBoundary(plainText, t.term)) {
                val snippet = plainText.take(200)
                out.add(MatchedTerm(t, snippet))
                db.watchwords().upsertHit(
                    WatchwordHitEntity(
                        term = t.term,
                        whom = msg.whom,
                        postId = msg.id,
                        sentMs = msg.sentMs,
                        snippet = snippet,
                    )
                )
                // Lazy prune is cheap (uses (term, sentMs) index for COUNT) and
                // running per-match is simpler than tracking matched-term-set.
                pruneIfOver(t.term)
            }
        }
        return out
    }

    /**
     * Fire-and-forget backfill scan for [term]. Adds [term.id] to
     * [_backfilling] for the duration. Cancellable from [remove].
     */
    private fun startBackfill(term: WatchwordEntity) {
        val job = scope.launch(Dispatchers.Default) {
            _backfilling.update { it + term.id }
            try {
                runBackfill(term)
            } finally {
                _backfilling.update { it - term.id }
                backfillJobs.remove(term.id)
            }
        }
        backfillJobs[term.id] = job
    }

    private suspend fun runBackfill(term: WatchwordEntity) {
        val excludesSet = excludes.value
        // Snapshot at start — mutes added DURING backfill aren't honored
        // until next scan. Per spec §UI: backfill is a one-shot historical
        // scan; the live runtime path picks up future changes immediately.
        val muted = db.notifyPrefs().mutedWhoms().toHashSet()
        val ourPatp = ourPatpProvider()
        val hits = ArrayList<WatchwordHitEntity>(64)
        for (m in db.messages().candidatesForBackfill(term.term, ourPatp)) {
            if (hits.size >= MAX_HITS_PER_TERM) break
            if (m.whom in excludesSet) continue
            if (m.whom in muted) continue
            val plainText = StoryCache.textFor(m.id, m.contentJson)
            if (!matchesWordBoundary(plainText, term.term)) continue
            hits.add(
                WatchwordHitEntity(
                    term = term.term,
                    whom = m.whom,
                    postId = m.id,
                    sentMs = m.sentMs,
                    snippet = plainText.take(200),
                )
            )
        }
        if (hits.isNotEmpty()) {
            db.watchwords().upsertHits(hits)
            pruneIfOver(term.term)
        }
    }

    /**
     * Hot-path prune. Only runs the DELETE when count exceeds the cap
     * by 100, so a quiet term never pays and a noisy term pays ~1% of
     * the time (every 100 inserts). See spec §Performance.
     */
    private suspend fun pruneIfOver(term: String) {
        val count = db.watchwords().countForTerm(term)
        if (count > MAX_HITS_PER_TERM + 100) {
            db.watchwords().pruneToNewest(term, MAX_HITS_PER_TERM)
        }
    }

    /**
     * Sync-toggle flush: the caller (TalonApplication.setWatchwordsSyncEnabled)
     * has already flipped the prefs flag BEFORE calling this. So the current
     * value of `syncEnabledProvider()` reflects the post-toggle state, and
     * `!syncEnabledProvider()` is exactly `transitionedOffSync` — true when
     * sync was just turned OFF, false when it was just turned ON.
     */
    fun emitSyncToggled() {
        onChange?.invoke(WatchwordChange.SyncToggled, !syncEnabledProvider())
    }
}
