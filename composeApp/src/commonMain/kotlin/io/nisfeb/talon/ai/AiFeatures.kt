package io.nisfeb.talon.ai

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache

/**
 * Prompts and post-processing for the individual AI-powered features.
 * The [AiClient] handles the wire protocol; this is the content layer.
 */
class AiFeatures(
    private val client: AiClient,
) {

    /**
     * "Catch me up": summarize a run of unread messages into a few
     * bullet points. Caller supplies the chronological message list
     * plus a pretty-name lookup so we don't feed raw patps.
     */
    suspend fun catchMeUp(
        messages: List<MessageEntity>,
        displayName: (String) -> String,
    ): String {
        if (messages.isEmpty()) return "No new messages."
        val lines = messages.joinToString("\n") { m ->
            val who = displayName(m.author)
            val text = StoryCache.textFor(m.id, m.contentJson)
                .replace('\n', ' ')
                .take(400)
            "$who: $text"
        }
        val sys = """
            You are summarizing an unread chat for a user who's been away.
            Be brief and punchy — max 5 bullets or a short paragraph.
            Mention who said what. Flag action items or questions directed
            at the user (identified by their @-mentions) clearly.
            Use only information in the transcript — don't invent.
        """.trimIndent()
        val user = "Summarize what I missed:\n\n$lines"
        return client.complete(sys, user, maxOutputTokens = 512)
    }
}
