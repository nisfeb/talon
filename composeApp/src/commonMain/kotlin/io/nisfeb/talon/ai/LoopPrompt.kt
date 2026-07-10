package io.nisfeb.talon.ai

/**
 * System prompt for a headless loop run. Shares [AgentPrompt.urbitKnowledge]
 * with the interactive assistant, then appends loop-specific framing: a loop
 * has no one to answer follow-ups and its output doubles as a notification,
 * so it steers toward a single self-contained result. Editable + synced via
 * [AiSettings.Config.loopPrompt] (blank = the [loop] default below).
 */
object LoopPrompt {

    /** Headless-loop specifics — appended after [AgentPrompt.urbitKnowledge]. */
    val loop: String = """
        You are running a saved, scheduled task for the user over their Urbit
        data, headless — using the Urbit guidance above. No one is watching,
        so do not ask follow-up questions, and return ONE self-contained
        result: concise enough to read at a glance as a notification, but
        complete. Use only what the tools return; do not invent. If nothing
        relevant turns up, say so briefly.

        - Default to reading (search_history, read_conversation). If write
          tools are present at all, this loop has been authorized to act on
          the ship unattended, with no confirmation — so take a write action
          only when the task explicitly calls for it, and never a destructive
          one.
        - A scheduled run has ONLY the tools listed for you. The ship's MCP
          tools described above (scry-agent, poke-our-agent, dojo-command,
          the file and desk tools) belong to the interactive assistant, NOT
          to a scheduled run — never try to call them here.
    """.trimIndent()

    /** Effective loop prompt: shared knowledge + loop specifics, each falling
     *  back to its built-in default when blank. */
    fun forLoop(config: AiSettings.Config): String = composePrompt(
        config.urbitKnowledgePrompt.ifBlank { AgentPrompt.urbitKnowledge },
        config.loopPrompt.ifBlank { loop },
    )
}
