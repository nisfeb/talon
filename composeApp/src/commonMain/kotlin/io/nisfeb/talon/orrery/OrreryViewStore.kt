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

/** Orrery's state and the ship's leave plan, as the Orrery section shows them, and when they came. */
data class OrreryView(val state: JsonObject, val plan: JsonObject?, val atMs: Long)

/**
 * The Orrery section's data, kept. Opening shows the last answer at once
 * (from memory, or from the database after a restart) and asks the ship
 * again behind it: both reads together, one ask at a time however many
 * opens come, and none within [FRESH_MS] of the last answer unless the
 * owner asks. The section blocked on a 220 KB read of a busy ship every
 * time it opened.
 */
class OrreryViewStore(
    private val cache: OrreryCacheDao,
    private val scope: CoroutineScope,
    private val readState: suspend () -> JsonObject,
    /** GET /api/travel/last, or null when it did not answer. */
    private val readPlan: suspend () -> JsonObject?,
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
            if (force || last == null || now() - last >= FRESH_MS) ask()
        }
    }

    private suspend fun restore() {
        if (restored) return
        restored = true
        if (_view.value != null) return
        val kept = runSuspendCatching {
            withContext(Dispatchers.Default) {
                val state = cache.get(STATE) ?: return@withContext null
                OrreryView(
                    state = Json.parseToJsonElement(state.json) as? JsonObject ?: return@withContext null,
                    plan = cache.get(PLAN)?.let { Json.parseToJsonElement(it.json) as? JsonObject },
                    atMs = state.atMs,
                )
            }
        }.getOrNull()
        if (kept != null && _view.value == null) _view.value = kept
    }

    private suspend fun ask() {
        _refreshing.value = true
        try {
            coroutineScope {
                val plan = async { runSuspendCatching { readPlan() }.getOrNull() }
                runSuspendCatching { readState() }
                    .onSuccess { state ->
                        // A plan that did not answer leaves the last one up.
                        val v = OrreryView(state, plan.await() ?: _view.value?.plan, now())
                        _view.value = v
                        _problem.value = null
                        runSuspendCatching {
                            withContext(Dispatchers.Default) {
                                cache.put(OrreryCacheEntity(STATE, state.toString(), v.atMs))
                                v.plan?.let { cache.put(OrreryCacheEntity(PLAN, it.toString(), v.atMs)) }
                            }
                        }
                    }
                    .onFailure { _problem.value = it.message ?: "no answer" }
            }
        } finally {
            _refreshing.value = false
        }
    }

    companion object {
        /** An answer this young is not asked for again on an open. */
        const val FRESH_MS = 60_000L
        private const val STATE = "state"
        private const val PLAN = "plan"
    }
}
