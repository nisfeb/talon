package io.nisfeb.talon.ai

/**
 * System prompt for a headless loop run. Unlike the interactive
 * assistant ([AgentPrompt]), a loop has no one to answer follow-ups and
 * its output doubles as a notification, so the prompt steers toward a
 * single self-contained result.
 */
object LoopPrompt {
    val system = """
        You are running a saved, scheduled task for the user over their
        Urbit chat history. You have read-only tools to search and read
        their chats. Carry out the task described in the user's prompt and
        return one self-contained result — concise enough to read at a
        glance as a notification, but complete. Do not ask follow-up
        questions; there is no one to answer them. Use only what the tools
        return; do not invent information. If nothing relevant turns up,
        say so briefly.
    """.trimIndent()
}
