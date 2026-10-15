package io.nisfeb.talon.ai

/**
 * One voice dictation, run through the agent and acted on with NO
 * confirmation — the "dictate straight into orrery" surfaces (widget,
 * Quick Settings tile, assist-app) fire this.
 *
 * Unlike the assistant screen, which confirms each write before it runs,
 * a dictation authorises every write up front: the owner asked for the
 * least possible friction (speak, done), so the agent writes to orrery /
 * the calendar / the ship directly and the result notification is the
 * only after-the-fact signal. This is the same autonomy a scheduled Loop
 * has; a dictation is just a one-shot of it with the spoken text as the
 * turn.
 *
 * A blank transcript runs nothing, so a mis-fire or a silent recording
 * is a no-op rather than an empty agent round-trip.
 *
 * The Android surfaces assemble [tools], [systemPrompt] and [completer]
 * (the same orrery + calendar + message tools a Loop gets) and call
 * [run]. The autonomy and the blank-guard live here so they are tested
 * once, without a device. See
 * docs/superpowers/specs/2026-10-15-dictate-to-orrery-design.md.
 */
class DictationAgent(
    private val completer: AgentLoop.Completer,
    private val tools: List<Tool>,
    private val systemPrompt: String = AgentPrompt.system,
    private val maxSteps: Int = 8,
) {
    /**
     * Run [transcript] and return the agent's own summary of what it
     * did (for the result notification), or "" for a blank dictation,
     * which runs nothing.
     */
    suspend fun run(transcript: String): String {
        val text = transcript.trim()
        if (text.isEmpty()) return ""
        return AgentLoop(completer, tools, systemPrompt, maxSteps)
            .run(question = text, confirm = { _, _ -> true })
    }
}
