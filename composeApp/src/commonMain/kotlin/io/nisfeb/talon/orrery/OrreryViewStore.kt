package io.nisfeb.talon.orrery

import io.nisfeb.talon.data.OrreryCacheDao
import io.nisfeb.talon.data.OrreryCacheEntity
import io.nisfeb.talon.util.nowMs
import io.nisfeb.talon.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Orrery's state and the ship's leave plan, as the Orrery section shows
 * them. [atMs] is when the view was last known current; [stateAtMs] when
 * the state itself was last read whole; [beacon] orrery's change beacon
 * as it stood when it was.
 */
data class OrreryView(
    val state: JsonObject,
    val plan: JsonObject?,
    val atMs: Long,
    val beacon: Long? = null,
    val stateAtMs: Long = atMs,
)

/**
 * The Orrery section's data, kept. Opening shows the last answer at once
 * (from memory, or from the database after a restart) and asks the ship
 * again behind it: one ask at a time however many opens come, and none
 * within [FRESH_MS] of the last answer unless the owner asks. An ask
 * reads orrery's change beacon first (a scry, ~0.15 s); when it has not
 * moved since the kept state, the state stands and only the leave plan is
 * read again, so the 3.5 s state read is skipped. A Refresh, or a state
 * read whole more than [FULL_EVERY_MS] ago, reads it whole regardless.
 */
class OrreryViewStore(
    private val cache: OrreryCacheDao,
    private val scope: CoroutineScope,
    private val readState: suspend () -> JsonObject,
    /** GET /api/travel/last (by scry where it can be), or null when it did not answer. */
    private val readPlan: suspend () -> JsonObject?,
    /** Orrery's change beacon by scry, or null where it cannot be read. */
    private val readBeacon: suspend () -> Long? = { null },
    private val now: () -> Long = { nowMs() },
) {
    private val _view = MutableStateFlow<OrreryView?>(null)
    val view: StateFlow<OrreryView?> = _view.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    /** Why the last ask got nothing; the kept answer is what shows meanwhile. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()
    private var asking: Job? = null
    private var restored = false

    /** The section opened, or [force] (Refresh): show what is kept, ask the ship if it is due. */
    fun open(force: Boolean = false) {
        if (asking?.isActive == true) return
        asking = scope.launch {
            restore()
            val last = _view.value?.atMs
            if (force || last == null || now() - last >= FRESH_MS) ask(force)
        }
    }

    private suspend fun restore() {
        if (restored) return
        restored = true
        if (_view.value != null) return
        val kept = runSuspendCatching {
            withContext(Dispatchers.Default) {
                val state = cache.get(STATE) ?: return@withContext null
                val beacon = cache.get(BEACON)
                OrreryView(
                    state = Json.parseToJsonElement(state.json) as? JsonObject ?: return@withContext null,
                    plan = cache.get(PLAN)?.let { Json.parseToJsonElement(it.json) as? JsonObject },
                    atMs = maxOf(state.atMs, beacon?.atMs ?: 0),
                    beacon = beacon?.json?.toLongOrNull(),
                    stateAtMs = state.atMs,
                )
            }
        }.getOrNull()
        if (kept != null && _view.value == null) _view.value = kept
    }

    private suspend fun ask(force: Boolean) {
        _refreshing.value = true
        try {
            coroutineScope {
                val plan = async { runSuspendCatching { readPlan() }.getOrNull() }
                // Read before the state, so the beacon kept with a state is never newer than it.
                val beacon = runSuspendCatching { readBeacon() }.getOrNull()
                val kept = _view.value
                if (!force && kept != null && beacon != null && beacon == kept.beacon &&
                    now() - kept.stateAtMs < FULL_EVERY_MS
                ) {
                    // Orrery has written nothing since: the state stands, the plan is read again.
                    val v = kept.copy(plan = plan.await() ?: kept.plan, atMs = now())
                    _view.value = v
                    _problem.value = null
                    keep(v, state = false)
                    return@coroutineScope
                }
                runSuspendCatching { readState() }
                    .onSuccess { state ->
                        // A plan that did not answer leaves the last one up.
                        val at = now()
                        val v = OrreryView(state, plan.await() ?: _view.value?.plan, at, beacon, stateAtMs = at)
                        _view.value = v
                        _problem.value = null
                        keep(v, state = true)
                    }
                    .onFailure { _problem.value = it.message ?: "no answer" }
            }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun keep(v: OrreryView, state: Boolean) {
        runSuspendCatching {
            withContext(Dispatchers.Default) {
                if (state) cache.put(OrreryCacheEntity(STATE, v.state.toString(), v.stateAtMs))
                v.plan?.let { cache.put(OrreryCacheEntity(PLAN, it.toString(), v.atMs)) }
                v.beacon?.let { cache.put(OrreryCacheEntity(BEACON, it.toString(), v.atMs)) }
            }
        }
    }

    companion object {
        /** An answer this young is not asked for again on an open. */
        const val FRESH_MS = 60_000L
        /** However still the beacon, the state is read whole at least this often. */
        const val FULL_EVERY_MS = 10 * 60_000L
        private const val STATE = "state"
        private const val PLAN = "plan"
        private const val BEACON = "beacon"
    }
}
