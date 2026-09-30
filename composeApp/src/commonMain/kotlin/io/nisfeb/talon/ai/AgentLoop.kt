package io.nisfeb.talon.ai

import kotlinx.coroutines.CancellationException

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

    /** Emitted as the loop runs so the UI can show progress. */
    sealed interface Event {
        data class Thinking(val text: String) : Event
        data class ToolStarted(val call: ToolCall, val write: Boolean) : Event
        data class ToolFinished(val call: ToolCall, val result: String) : Event
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
        while (step < maxSteps) {
            step++
            when (val turn = completer.complete(systemPrompt, history, specs)) {
                is AgentTurn.Final -> {
                    onEvent(Event.Answer(turn.text))
                    return turn.text
                }
                is AgentTurn.Calls -> {
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
        history.add(AgentMessage.User(WRAP_UP))
        val last = runCatching { completer.complete(systemPrompt, history, emptyList()) }
            .getOrElse { if (it is CancellationException) throw it; null }
        val msg = when (last) {
            is AgentTurn.Final -> last.text
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
            ?: return ToolResult(call.id, call.name, "Error: unknown tool '${call.name}'.")
        onEvent(Event.ToolStarted(call, tool.write))
        if (tool.write && !confirm(call, tool)) {
            onEvent(Event.Declined(call))
            return ToolResult(call.id, call.name, "The user declined this action.")
        }
        val content = runCatching { tool.execute(call.args) }
            .getOrElse {
                // Don't turn a cancellation into a fake tool error — that
                // masks it as a result the model reasons about and keeps the
                // loop stepping. Unwind instead (LoopRunner does the same).
                if (it is CancellationException) throw it
                "Error: ${it.message ?: it::class.simpleName}"
            }
        onEvent(Event.ToolFinished(call, content))
        return ToolResult(call.id, call.name, content)
    }

    companion object {
        // A backstop against a model that loops, not a budget for work:
        // 8 cut real tasks off partway (setting up a reader is several
        // reads, writes and checks). Each step is a model call, so it is
        // still a bound on what a runaway costs.
        const val MAX_STEPS = 50

        private const val WRAP_UP =
            "You have used every step this run allows, so no more tools can be called. " +
                "Answer now: say what you did, what you found, and what is left undone, " +
                "so the owner can ask you to carry on from here."
    }
}
