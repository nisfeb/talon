package io.nisfeb.talon.ai

import kotlinx.serialization.json.JsonObject

/**
 * Provider-agnostic conversation + tool types for the agentic assistant
 * (Phase 2, docs/assistant.md). [AgentClient] translates these to/from
 * the Anthropic and OpenAI tool-use wire formats; [AgentLoop] drives the
 * turn-by-turn loop.
 */

/** One message in the running agent conversation, replayed to the model
 *  on each turn so it keeps full context. */
sealed interface AgentMessage {
    data class User(val text: String) : AgentMessage

    /** What the model produced: optional prose plus zero or more tool
     *  calls. Echoed back verbatim so the provider sees its own turn. */
    data class Assistant(
        val text: String?,
        val toolCalls: List<ToolCall>,
    ) : AgentMessage

    /** Results for the tool calls in the immediately-preceding
     *  [Assistant] turn. */
    data class ToolResults(val results: List<ToolResult>) : AgentMessage
}

/** A tool invocation the model requested. [args] is the decoded
 *  arguments object (already JSON for both dialects). */
data class ToolCall(
    val id: String,
    val name: String,
    val args: JsonObject,
)

data class ToolResult(
    val id: String,
    val name: String,
    val content: String,
)

/** Tool advertised to the model: name, human description, and a JSON
 *  Schema object for its arguments. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** The model's response for one turn: either it's done ([Final]) or it
 *  wants tools run ([Calls]). */
sealed interface AgentTurn {
    data class Final(val text: String) : AgentTurn
    data class Calls(val text: String?, val calls: List<ToolCall>) : AgentTurn
}
