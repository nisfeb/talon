package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LoopDao
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.LoopRunDao
import io.nisfeb.talon.data.LoopRunEntity
import kotlinx.coroutines.CancellationException

/**
 * Runs a [LoopEntity]'s prompt through the assistant agent, headless:
 * no UI, no confirmation prompts. The caller supplies the FULL [tools]
 * list (reads + writes); per loop, write tools are kept only when that
 * loop has [LoopEntity.writesAuthorized] set, and the headless confirm
 * gate auto-approves writes only for those loops. A loop without the
 * flag can never mutate the ship by construction — its write tools are
 * filtered out AND confirm declines.
 *
 * Persists each run to `loop_run`, prunes the per-loop history, stamps
 * `lastRunAt`, and fires a notification. Platform-agnostic: the Android
 * alarm receiver and the desktop ticker both call [runDue] / [runLoop];
 * the [notify] sink and [completer] are injected per host. [notify]
 * carries the loop id so the platform can key its notification on the
 * stable id rather than the (mutable, non-unique) name.
 */
class LoopRunner(
    private val loops: LoopDao,
    private val runs: LoopRunDao,
    private val tools: List<Tool>,
    private val completer: AgentLoop.Completer,
    private val aiConfig: () -> AiSettings.Config,
    private val notify: (loopId: Long, title: String, body: String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** Run one loop now and record the result. Both the run-now button and
     *  the alarm path land here. No-ops without an AI key (no error noise).
     *  Stamps `lastRunAt` BEFORE the cancellable agent call so a run that's
     *  later timed out / cancelled still advances the schedule — otherwise
     *  the loop stays due and re-fires every wake (token + battery drain). */
    suspend fun runLoop(loop: LoopEntity) {
        if (!aiConfig().hasKey()) return
        val ts = now()
        loops.markRan(loop.id, ts)
        // Defence in depth: drop write tools unless this loop opted in, AND
        // gate confirm on the same flag. Either alone would block writes;
        // both means a non-authorized loop can't write even if a tool is
        // mis-tagged or the catalog changes.
        val active = if (loop.writesAuthorized) tools else tools.filter { !it.write }
        val outcome = runCatching {
            AgentLoop(completer = completer, tools = active, systemPrompt = LoopPrompt.forLoop(aiConfig()))
                .run(question = loop.prompt, confirm = { _, _ -> loop.writesAuthorized })
        }
        val ok = outcome.isSuccess
        val output = outcome.getOrElse {
            if (it is CancellationException) throw it
            "Error: ${it.message ?: it::class.simpleName}"
        }
        runs.insert(LoopRunEntity(loopId = loop.id, ranAt = ts, ok = ok, output = output))
        runs.pruneForLoop(loop.id, RUN_HISTORY_KEEP)
        notify(loop.id, "Loop: ${loop.name}", output.take(NOTIFY_BODY_CHARS))
    }

    /** Run every enabled loop that's due. The alarm fires one shared
     *  wake-up; this batches whatever has come due since. Skips entirely
     *  with no AI key set, so a keyless device records no error noise. */
    suspend fun runDue() {
        if (!aiConfig().hasKey()) return
        val t = now()
        for (loop in loops.enabled()) {
            if (!LoopSchedule.isDue(t, loop.lastRunAt, loop.intervalMinutes)) continue
            runCatching { runLoop(loop) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    companion object {
        const val RUN_HISTORY_KEEP = 20
        const val NOTIFY_BODY_CHARS = 200
    }
}
