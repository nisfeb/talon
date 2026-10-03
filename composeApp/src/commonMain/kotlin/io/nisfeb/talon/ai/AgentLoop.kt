package io.nisfeb.talon.ai

import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/**
 * Drives the agentic conversation (Phase 2, docs/assistant.md):
 *
 *   question → model → tool calls → execute → feed results → repeat
 *
 * until the model returns a final answer. The step cap is a backstop
 * against a model that loops, not a limit on real work, and reaching it
 * still ends in an answer: the model is asked once more, with no tools,
 * to say what it did and what is left.
 *
 * The trust boundary lives here: **read** tools run automatically;
 * every **write** tool must clear [confirm] before it executes. A
 * declined or unconfirmed write is reported back to the model as a
 * normal tool result ("user declined"), so a message in the user's own
 * history can never coerce an unconfirmed action — prompt-injection is
 * contained by construction, not by trusting the model.
 *
 * A final message with nothing in it is not an answer. The model is
 * nudged once to answer; a second empty one is [EmptyAnswer], a
 * failure the screen shows like any other, with the question kept.
 * "(no reply)" after minutes of tool use was what the owner got before.
 */
class AgentLoop(
    private val completer: Completer,
    private val tools: List<Tool>,
    private val systemPrompt: String = AgentPrompt.system,
    private val maxSteps: Int = MAX_STEPS,
) {
    private val byName = tools.associateBy { it.spec.name }
    private val specs = tools.map { it.spec }

    /** One model round-trip. Injected (rather than a concrete
     *  [AgentClient]) so the loop is testable with a scripted model. */
    fun interface Completer {
        suspend fun complete(
            system: String,
            messages: List<AgentMessage>,
            tools: List<ToolSpec>,
        ): AgentTurn
    }

    /** The model answered with nothing, twice. */
    class EmptyAnswer : IllegalStateException("the model returned nothing, twice. Try again, or ask for less at once.")

    /** Emitted as the loop runs so the UI can show progress. */
    sealed interface Event {
        data class Thinking(val text: String) : Event
        data class ToolStarted(val call: ToolCall, val write: Boolean) : Event
        /** [ms] the tool took; [result] its whole text. */
        data class ToolFinished(val call: ToolCall, val result: String, val ms: Long = 0) : Event
        data class Declined(val call: ToolCall) : Event
        data class Answer(val text: String) : Event
    }

    /**
     * Run one user question to completion.
     *
     * @param priorTurns prior turns of the same conversation, oldest
     *   first, as alternating User/Assistant messages — replayed so the
     *   model has context for follow-ups. The caller bounds this (recent
     *   turns of one topic) so the prompt stays in budget.
     * @param confirm gate for write tools — return true to allow. Reads
     *   never call it.
     * @param onEvent progress sink (UI transcript).
     * @throws EmptyAnswer when the model returns nothing twice running.
     */
    suspend fun run(
        question: String,
        priorTurns: List<AgentMessage> = emptyList(),
        confirm: suspend (ToolCall, Tool) -> Boolean,
        onEvent: (Event) -> Unit = {},
    ): String {
        val history = mutableListOf<AgentMessage>()
        history.addAll(priorTurns)
        history.add(AgentMessage.User(question))
        var step = 0
        var nudged = false
        while (step < maxSteps) {
            step++
            val started = TimeSource.Monotonic.markNow()
            val turn = completer.complete(systemPrompt, history, specs)
            val ms = started.elapsedNow().inWholeMilliseconds
            when (turn) {
                is AgentTurn.Final -> {
                    if (turn.text.isBlank()) {
                        Log.w(TAG, "step $step: the model returned nothing (${ms}ms)" + if (nudged) ", after a nudge" else "")
                        if (nudged) throw EmptyAnswer()
                        nudged = true
                        onEvent(Event.Thinking("The model returned nothing; asking it once more."))
                        history.add(AgentMessage.User(NUDGE))
                        continue
                    }
                    Log.i(TAG, "step $step: answer, ${turn.text.length} chars (${ms}ms)")
                    onEvent(Event.Answer(turn.text))
                    return turn.text
                }
                is AgentTurn.Calls -> {
                    Log.i(TAG, "step $step: ${turn.calls.size} tool call(s) ${turn.calls.joinToString { it.name }} (${ms}ms)")
                    turn.text?.let { onEvent(Event.Thinking(it)) }
                    history.add(AgentMessage.Assistant(turn.text, turn.calls))
                    val results = turn.calls.map { call ->
                        runOne(call, confirm, onEvent)
                    }
                    history.add(AgentMessage.ToolResults(results))
                }
            }
        }
        // Out of steps: not a canned "stopped" in place of an answer, which
        // left the owner with nothing after all that work. One more turn
        // with the tools taken away, so the model has to answer.
        Log.w(TAG, "out of steps ($maxSteps); asking for a wrap-up")
        history.add(AgentMessage.User(WRAP_UP))
        val last = runCatching { completer.complete(systemPrompt, history, emptyList()) }
            .getOrElse { if (it is CancellationException) throw it; null }
        val msg = when (last) {
            is AgentTurn.Final -> last.text.takeIf { it.isNotBlank() }
            is AgentTurn.Calls -> last.text?.takeIf { it.isNotBlank() }
            null -> null
        } ?: "I ran out of steps ($maxSteps) before finishing. Ask me to carry on and I will pick up from here."
        onEvent(Event.Answer(msg))
        return msg
    }

    private suspend fun runOne(
        call: ToolCall,
        confirm: suspend (ToolCall, Tool) -> Boolean,
        onEvent: (Event) -> Unit,
    ): ToolResult {
        val tool = byName[call.name]
            ?: return ToolResult(call.id, call.name, "Error: unknown tool '${call.name}'.").also {
                Log.w(TAG, "tool ${call.name}: unknown")
            }
        onEvent(Event.ToolStarted(call, tool.write))
        if (tool.write && !confirm(call, tool)) {
            Log.i(TAG, "tool ${call.name}: declined")
            onEvent(Event.Declined(call))
            return ToolResult(call.id, call.name, "The user declined this action.")
        }
        val started = TimeSource.Monotonic.markNow()
        val content = runCatching { tool.execute(call.args) }
            .getOrElse {
                // Don't turn a cancellation into a fake tool error — that
                // masks it as a result the model reasons about and keeps the
                // loop stepping. Unwind instead (LoopRunner does the same).
                if (it is CancellationException) throw it
                "Error: ${it.message ?: it::class.simpleName}"
            }
        val ms = started.elapsedNow().inWholeMilliseconds
        Log.i(TAG, "tool ${call.name}: args ${call.args.toString().length} chars, result ${content.length} chars, ${ms}ms" +
            if (content.startsWith("Error:")) " — ${content.take(200)}" else "")
        onEvent(Event.ToolFinished(call, content, ms))
        return ToolResult(call.id, call.name, content)
    }

    companion object {
        private const val TAG = "AgentLoop"

        // A backstop against a model that loops, not a budget for work:
        // 8 cut real tasks off partway (setting up a reader is several
        // reads, writes and checks). Each step is a model call, so it is
        // still a bound on what a runaway costs.
        const val MAX_STEPS = 50

        private const val WRAP_UP =
            "You have used every step this run allows, so no more tools can be called. " +
                "Answer now: say what you did, what you found, and what is left undone, " +
                "so the owner can ask you to carry on from here."

        internal const val NUDGE =
            "You returned an empty message. Answer the owner now: say what you did, " +
                "what you found, and what stopped you if something did."
    }
}
