package io.nisfeb.talon.ai

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ReactionPalette
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

    /**
     * "AI emoji react": pick a single reaction for a message based on
     * its content. Returns a shortcode from [ReactionPalette] or null
     * if the model's output doesn't map to anything we can send.
     *
     * Prompting returns the emoji character directly rather than a
     * shortcode — models are more reliable at single-token emoji
     * output than choosing from a typed list, and matching on the
     * emoji glyph (not a substring of a word like "thinking") avoids
     * accidental defaults.
     */
    suspend fun suggestEmojiReact(messageText: String): String? {
        val emojiList = ReactionPalette.picker.joinToString(" ") { it.second }
        val sys = """
            You react to a chat message with a single emoji.
            Pick the one from this list that fits the message best:
            $emojiList
            Reply with just the emoji character. No words. No punctuation.
            Default to 👍 if nothing else obviously fits — do not default
            to 🤔 unless the message is genuinely puzzling.
        """.trimIndent()
        val raw = client.complete(sys, messageText.take(1200), maxOutputTokens = 8)
            .trim()
        // Match by emoji glyph. Order matters — pick the first palette
        // emoji that appears anywhere in the response string.
        val emojiToCode = ReactionPalette.picker.associate { (code, emoji) -> emoji to code }
        return emojiToCode.entries.firstOrNull { (emoji, _) -> raw.contains(emoji) }?.value
    }
}
