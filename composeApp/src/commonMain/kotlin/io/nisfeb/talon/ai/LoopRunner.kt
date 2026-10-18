package io.nisfeb.talon.ai
import io.nisfeb.talon.util.nowMs

import io.nisfeb.talon.data.LoopDao
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.LoopHoldDao
import io.nisfeb.talon.data.LoopHoldEntity
import io.nisfeb.talon.data.LoopRunDao
import io.nisfeb.talon.data.LoopRunEntity
import io.nisfeb.talon.util.formatFullLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Runs a [LoopEntity]'s prompt through the assistant agent, headless:
 * no UI, no confirmation prompts. The caller supplies the FULL [tools]
 * list (reads + writes); per loop, write tools are kept only when that
 * loop has [LoopEntity.writesAuthorized] set, and the headless confirm
 * gate auto-approves writes only for those loops. A loop without the
 * flag can never mutate the ship by construction — its write tools are
 * filtered out AND confirm declines.
 *
 * [DESTRUCTIVE_TOOLS] (delete_event and its ilk) are filtered out for
 * EVERY loop, authorized or not: an unattended run auto-confirms its own
 * writes, so an irreversible delete must never be one prompt-injection
 * away from firing. Destructive actions stay with the interactive
 * assistant, where a human reads the confirmation card.
 *
 * [ASK_FIRST_TOOLS] (sharing a calendar, answering a calendar offer) are
 * held instead of done for a loop with [LoopEntity.askBeforeSharing] on:
 * the model is told it waits for the owner, the run says so, and the
 * owner allows it ([allow]) or not on the Loops screen. Off by default,
 * so such a loop does them unattended as before.
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
    private val holds: LoopHoldDao,
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
     *  the loop stays due and re-fires every wake (token + battery drain).
     *  A run cut short that way is recorded and notified as [CUT_SHORT]. */
    suspend fun runLoop(loop: LoopEntity) {
        if (!aiConfig().loopsCanRun()) return
        val ts = now()
        loops.markRan(loop.id, ts)
        holds.deleteBefore(ts - HOLD_MS)
        // What this run held for the owner, said with its result.
        val held = mutableListOf<String>()
        // Defence in depth: drop write tools unless this loop opted in, AND
        // gate confirm on the same flag. Either alone would block writes;
        // both means a non-authorized loop can't write even if a tool is
        // mis-tagged or the catalog changes. Destructive tools are dropped
        // for EVERY loop: an unattended run auto-confirms, so nothing
        // irreversible may be within the model's reach here. A loop that
        // asks first has its shares held instead ([holding]).
        val active = tools.filter { it.spec.name !in DESTRUCTIVE_TOOLS && (loop.writesAuthorized || !it.write) }
            .map { if (loop.askBeforeSharing && it.spec.name in ASK_FIRST_TOOLS) holding(loop, it, held) else it }
        // Stamp the run with the current local date/day/time so the agent
        // can reason about "now" (e.g. "what happened today?") — a headless
        // run has no other way to know when it fired.
        val question = "Current local date and time: ${formatFullLocal(ts)}.\n\n${loop.prompt}"
        val outcome = runCatching {
            AgentLoop(completer = completer, tools = active, systemPrompt = LoopPrompt.forLoop(aiConfig()))
                .run(question = question, confirm = { _, _ -> loop.writesAuthorized })
        }
        val output = outcome.getOrElse {
            if (it is CancellationException) {
                // Cut short (its time ran out, or Talon was stopped): said,
                // not silent. The stamp above stands, so it is not run a
                // second time for this fire. Caught, so a database closing
                // under a quit cannot replace the cancellation.
                withContext(NonCancellable) { runCatching { record(loop, ts, ok = false, output = CUT_SHORT, held = held) } }
                throw it
            }
            modelProblem("Error", it).line
        }
        record(loop, ts, outcome.isSuccess, output, held)
    }

    /** [tool] for a loop that asks first: the call is kept for the owner, not done. */
    private fun holding(loop: LoopEntity, tool: Tool, held: MutableList<String>) = Tool(tool.spec, tool.write) { args ->
        val what = wanted(loop.name, tool.spec.name, args)
        holds.insert(LoopHoldEntity(loopId = loop.id, tool = tool.spec.name, args = args.toString(), what = what, heldAt = now()))
        held += what
        HELD
    }

    /**
     * Do the held call [holdId] through the loop's own tool, as the run
     * would have, and say what came of it in the loop's history and a
     * notification. Nothing for a hold already answered, expired, or
     * whose loop is gone.
     */
    suspend fun allow(holdId: Long) {
        val hold = holds.get(holdId) ?: return
        // Taken first, so a second tap finds nothing to do.
        if (holds.delete(holdId) == 0) return
        val ts = now()
        if (ts - hold.heldAt >= HOLD_MS) return
        val loop = loops.get(hold.loopId) ?: return
        val tool = tools.firstOrNull { it.spec.name == hold.tool }
        val result = if (tool == null) {
            "Error: ${hold.tool} is not available on this device."
        } else {
            runCatching { tool.execute(Json.parseToJsonElement(hold.args).jsonObject) }
                .getOrElse { if (it is CancellationException) throw it; "Error: ${it.message ?: it::class.simpleName}" }
        }
        record(loop, ts, ok = !result.startsWith("Error"), output = "You allowed it: ${hold.what}\n\n$result")
    }

    private suspend fun record(loop: LoopEntity, ts: Long, ok: Boolean, output: String, held: List<String> = emptyList()) {
        val waiting = held.joinToString(" ")
        runs.insert(
            LoopRunEntity(
                loopId = loop.id, ranAt = ts, ok = ok,
                output = if (held.isEmpty()) output else "$output\n\nWaiting for you in Loops: $waiting",
            ),
        )
        runs.pruneForLoop(loop.id, RUN_HISTORY_KEEP)
        // What waits on the owner goes first: it is why they look.
        notify(loop.id, "Loop: ${loop.name}", (if (held.isEmpty()) output else "$waiting $ASK_IN_LOOPS").take(NOTIFY_BODY_CHARS))
    }

    /** Run every enabled loop that's due. The alarm fires one shared
     *  wake-up; this batches whatever has come due since. Skips entirely
     *  with no AI key set, so a keyless device records no error noise.
     *  [perLoopMs] is each loop's own time: one budget for the whole fire
     *  left the loops after a slow one none, each cut short unrun. */
    suspend fun runDue(perLoopMs: Long? = null) {
        if (!aiConfig().loopsCanRun()) return
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
            runCatching { if (perLoopMs == null) runLoop(loop) else withTimeoutOrNull(perLoopMs) { runLoop(loop) } }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    companion object {
        const val RUN_HISTORY_KEEP = 20
        const val NOTIFY_BODY_CHARS = 200
        const val CUT_SHORT = "Stopped before it finished: it ran out of time, or Talon was stopped. " +
            "It may have done part of it. It runs again at its next time."


        /** Tools no loop may ever call, even with writesAuthorized set:
         *  irreversible-by-design actions stay behind the interactive
         *  assistant's human confirmation. Matched on the model-facing
         *  [ToolSpec.name]. */
        val DESTRUCTIVE_TOOLS = setOf("delete_event")

        /** Held for the owner where a loop asks first ([LoopEntity.askBeforeSharing]). */
        val ASK_FIRST_TOOLS = setOf("share_calendar", "answer_calendar_offer")

        /** How long a held call waits for the owner before it expires, undone. */
        const val HOLD_MS = 24 * 60 * 60 * 1000L

        /** What a held tool tells the model. */
        const val HELD = "Not done: this waits for the owner, who allows it or not in Loops. " +
            "Say in your answer that it is waiting for them."

        const val ASK_IN_LOOPS = "Allow it or not in Loops."

        /** What a loop wanted of a held call, in words, for the owner. */
        internal fun wanted(loop: String, tool: String, args: JsonObject): String {
            fun arg(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return if (tool == "share_calendar") {
                "$loop wants to share ${arg("calendar")} with ${arg("ship")}, edit ${if (arg("edit") == "true") "on" else "off"}."
            } else {
                "$loop wants to ${if (arg("accept") == "true") "accept" else "decline"} the calendar offer ${arg("offer")}."
            }
        }
    }
}

/**
 * [block] on a scope of its own under [parent], ended when it returns.
 * A run that builds its own mail and calendar builds their workers too,
 * which never end by themselves (the mail's relister and its listing's
 * flows), so on the process scope every scheduled run left a repo
 * running for good.
 */
suspend fun <T> withRunScope(
    parent: kotlinx.coroutines.CoroutineScope,
    block: suspend (kotlinx.coroutines.CoroutineScope) -> T,
): T {
    val run = kotlinx.coroutines.CoroutineScope(
        parent.coroutineContext + kotlinx.coroutines.SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job]),
    )
    try {
        return block(run)
    } finally {
        run.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
