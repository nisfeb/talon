package io.nisfeb.talon.ai
import io.nisfeb.talon.util.nowMs

import io.nisfeb.talon.data.LoopDao
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.LoopRunDao
import io.nisfeb.talon.data.LoopRunEntity
import io.nisfeb.talon.util.formatFullLocal
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone

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
    // Cross-device gate for scheduled write-loop fires (runDue only). Default
    // Noop runs everywhere; the host wires the %settings lease so several
    // devices don't all run the same write automation.
    private val coordinator: LoopWriteCoordinator = LoopWriteCoordinator.Noop,
    private val now: () -> Long = { nowMs() },
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
        // Stamp the run with the current local date/day/time so the agent
        // can reason about "now" (e.g. "what happened today?") — a headless
        // run has no other way to know when it fired.
        val question = "Current local date and time: ${formatFullLocal(ts)}.\n\n${loop.prompt}"
        val outcome = runCatching {
            AgentLoop(completer = completer, tools = active, systemPrompt = LoopPrompt.forLoop(aiConfig()))
                .run(question = question, confirm = { _, _ -> loop.writesAuthorized })
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
        val zone = TimeZone.currentSystemDefault()
        for (loop in loops.enabled()) {
            if (!LoopSchedule.isDue(t, loop, zone)) continue
            if (loop.writesAuthorized && !coordinator.canCoordinate()) {
                // No ship connection to take the lease with — NOBODY ran this
                // fire, unlike a lost contest below. Still advance the slot
                // (retrying every wake would spin the alarm), but leave a
                // visible failure instead of silence.
                loops.markRan(loop.id, t)
                runs.insert(
                    LoopRunEntity(
                        loopId = loop.id, ranAt = t, ok = false,
                        output = "Skipped: couldn't reach your ship to coordinate " +
                            "this write-authorized job. It will try again next interval.",
                    ),
                )
                runs.pruneForLoop(loop.id, RUN_HISTORY_KEEP)
                notify(loop.id, "Loop: ${loop.name}", "Skipped — couldn't reach your ship")
                continue
            }
            // Only one running device should perform a scheduled WRITE fire.
            // If we lose the lease, stamp lastRunAt locally so we treat this
            // slot as handled and don't re-contest every tick.
            if (loop.writesAuthorized && !coordinator.claim(loop)) {
                loops.markRan(loop.id, t)
                continue
            }
            runCatching { runLoop(loop) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    companion object {
        const val RUN_HISTORY_KEEP = 20
        const val NOTIFY_BODY_CHARS = 200
    }
}
