package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.WatchwordChatExcludeEntity
import io.nisfeb.talon.data.WatchwordEntity
import io.nisfeb.talon.data.WatchwordHitEntity
import io.nisfeb.talon.data.escapeLikeNeedle
import io.nisfeb.talon.urbit.SettingsSync
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** What changed, for mirroring to %settings. */
sealed class WatchwordChange {
    data class Upsert(val term: WatchwordEntity) : WatchwordChange()
    data class Remove(val termText: String) : WatchwordChange()
    data class Exclude(val whom: String) : WatchwordChange()
    data class Unexclude(val whom: String) : WatchwordChange()
    /** Sync switched on (push everything) or off (clear the ship's copy). */
    data class SyncToggled(val on: Boolean) : WatchwordChange()
}

/** A live message that matched terms set to notify, and its text. */
data class WatchwordNotice(val terms: List<String>, val text: String)

/**
 * Watchwords for one ship, on every platform: terms, per-chat excludes,
 * matching live messages, the backfill scan of history when a term is
 * added, and mirroring each change to %settings while [syncEnabled].
 *
 * [io.nisfeb.talon.urbit.TlonChatRepo] owns one and feeds it every live
 * message; a shell only says how to show a notice. The term screen and
 * the exclude switches go through here, not the DAO, or the change
 * neither syncs nor backfills.
 */
class Watchwords(
    private val db: AppDatabase,
    private val ourPatp: () -> String,
    private val scope: CoroutineScope,
    private val settingsSync: SettingsSync?,
    private val syncEnabled: StateFlow<Boolean>,
) {
    /** Running backfills by term id, so removing a term stops its scan. */
    private val backfills = MutableStateFlow<Map<Long, Job>>(emptyMap())

    init {
        // Subscribed now, not whenever the scope gets to it: drop(1) skips
        // the value it starts from, and a switch flipped in between was
        // that value, so it was dropped and never mirrored.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            syncEnabled.drop(1).collect { on ->
                runCatching { settingsSync?.mirrorWatchword(WatchwordChange.SyncToggled(on)) }
            }
        }
    }

    private fun mirror(change: WatchwordChange) {
        val sync = settingsSync ?: return
        if (!syncEnabled.value) return
        scope.launch { runCatching { sync.mirrorWatchword(change) } }
    }

    suspend fun add(term: String, notify: Boolean): Long {
        val trimmed = term.trim()
        require(trimmed.isNotEmpty()) { "watchword cannot be empty" }
        val id = db.watchwords().upsertTerm(WatchwordEntity(term = trimmed, notify = notify, createdMs = nowMs()))
        val saved = db.watchwords().getTerm(id) ?: return id
        mirror(WatchwordChange.Upsert(saved))
        val job = scope.launch(Dispatchers.Default) { backfill(saved) }
        backfills.update { it[id]?.cancel(); it + (id to job) }
        return id
    }

    suspend fun remove(termId: Long) {
        val term = db.watchwords().getTerm(termId) ?: return
        backfills.update { it[termId]?.cancel(); it - termId }
        db.watchwords().clearHitsForTerm(term.term)
        db.watchwords().deleteTermById(termId)
        mirror(WatchwordChange.Remove(term.term))
    }

    suspend fun setNotify(termId: Long, notify: Boolean) {
        db.watchwords().setNotify(termId, notify)
        db.watchwords().getTerm(termId)?.let { mirror(WatchwordChange.Upsert(it)) }
    }

    suspend fun excludeChat(whom: String, excluded: Boolean) {
        if (excluded) {
            db.watchwords().upsertExclude(WatchwordChatExcludeEntity(whom))
            mirror(WatchwordChange.Exclude(whom))
        } else {
            db.watchwords().deleteExclude(whom)
            mirror(WatchwordChange.Unexclude(whom))
        }
    }

    /**
     * A live message just landed. Keeps a hit for every term it matches,
     * unless it is ours, from a muted or excluded chat; returns what to
     * notify about, or null when no matched term asks to.
     */
    suspend fun heard(msg: MessageEntity): WatchwordNotice? {
        if (msg.author == ourPatp()) return null
        val terms = db.watchwords().streamTerms().first()
        if (terms.isEmpty()) return null
        if (db.watchwords().streamExcludes().first().any { it.whom == msg.whom }) return null
        if (db.notifyPrefs().levelFor(msg.whom) == NotifyLevel.NONE) return null
        val text = StoryCache.textFor(msg.id, msg.contentJson)
        val matched = terms.filter { matchesWordBoundary(text, it.term) }
        for (t in matched) {
            db.watchwords().upsertHit(WatchwordHitEntity(t.term, msg.whom, msg.id, msg.sentMs, text.take(200)))
            pruneIfOver(t.term)
        }
        return matched.filter { it.notify }.map { it.term }.takeIf { it.isNotEmpty() }?.let { WatchwordNotice(it, text) }
    }

    /** One pass over history for a new term. Excludes and mutes as of now. */
    private suspend fun backfill(term: WatchwordEntity) {
        val excluded = db.watchwords().streamExcludes().first().mapTo(HashSet()) { it.whom }
        val muted = db.notifyPrefs().mutedWhoms().toHashSet()
        val hits = ArrayList<WatchwordHitEntity>(64)
        for (m in db.messages().candidatesForBackfill(escapeLikeNeedle(term.term), ourPatp())) {
            if (hits.size >= MAX_HITS_PER_TERM) break
            if (m.whom in excluded || m.whom in muted) continue
            val text = StoryCache.textFor(m.id, m.contentJson)
            if (!matchesWordBoundary(text, term.term)) continue
            hits.add(WatchwordHitEntity(term.term, m.whom, m.id, m.sentMs, text.take(200)))
        }
        if (hits.isNotEmpty()) {
            db.watchwords().upsertHits(hits)
            pruneIfOver(term.term)
        }
    }

    /**
     * Only runs the DELETE when count exceeds the cap by 100, so a quiet
     * term never pays and a noisy term pays ~1% of the time.
     */
    private suspend fun pruneIfOver(term: String) {
        if (db.watchwords().countForTerm(term) > MAX_HITS_PER_TERM + 100) {
            db.watchwords().pruneToNewest(term, MAX_HITS_PER_TERM)
        }
    }
}
