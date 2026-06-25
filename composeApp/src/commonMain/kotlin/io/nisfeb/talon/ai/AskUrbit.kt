package io.nisfeb.talon.ai

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache

/**
 * "Ask your Urbit" — grounded Q&A over the user's own chat history.
 *
 * Retrieval is on-device ([SearchEmbedderClient.semanticSearch] already
 * embeds the query + runs the cosine search and returns ranked messages);
 * only the final prompt leaves the device, to the user's own LLM key via
 * [AiClient]. The model is constrained to answer from the retrieved
 * excerpts and cite them by number, so [Answer.sources] can deep-link
 * each claim back to the real message.
 *
 * Phase 1 of the Talon Assistant (see docs/assistant.md): read-only.
 */
class AskUrbit(
    private val client: AiClient,
    private val embedder: SearchEmbedderClient,
) {

    /** A retrieved message the answer cited, for tap-through. */
    data class SourceRef(
        /** The `[n]` the model used (1-based, matches the excerpt). */
        val index: Int,
        val whom: String,
        val postId: String,
        val author: String,
        val snippet: String,
    )

    data class Answer(
        val text: String,
        /** Sources the answer actually cited, in first-citation order. */
        val sources: List<SourceRef>,
    )

    suspend fun ask(
        question: String,
        displayName: (String) -> String,
        k: Int = 20,
    ): Answer {
        val hits = embedder.semanticSearch(question).take(k)
        if (hits.isEmpty()) {
            return Answer(
                "I couldn't find anything in your message history about that.",
                emptyList(),
            )
        }
        val excerpts = numberedContext(
            hits.map { displayName(it.author) to StoryCache.textFor(it.id, it.contentJson) },
        )
        val text = client.complete(
            AskUrbitPrompt.system,
            AskUrbitPrompt.user(question, excerpts),
            maxOutputTokens = 700,
        )
        return Answer(text, citedSources(text, hits))
    }

    companion object {
        private val CITE = Regex("""\[(\d+)]""")
        private const val EXCERPT_CHARS = 400
        private const val SNIPPET_CHARS = 120

        /** Number (author, body) pairs into `"[n] who: text"` lines.
         *  Pure — newline-flattened and length-capped so the prompt
         *  stays bounded. */
        internal fun numberedContext(rows: List<Pair<String, String>>): List<String> =
            rows.mapIndexed { i, (who, text) ->
                "[${i + 1}] $who: ${text.replace('\n', ' ').take(EXCERPT_CHARS)}"
            }

        /** The `[n]` markers the model used, in first-mention order,
         *  deduped, dropping any index outside `1..max`. Pure. */
        internal fun parseCitedIndices(answer: String, max: Int): List<Int> {
            val seen = LinkedHashSet<Int>()
            for (m in CITE.findAll(answer)) {
                val n = m.groupValues[1].toIntOrNull() ?: continue
                if (n in 1..max) seen.add(n)
            }
            return seen.toList()
        }

        private fun citedSources(answer: String, hits: List<MessageEntity>): List<SourceRef> =
            parseCitedIndices(answer, hits.size).map { n ->
                val msg = hits[n - 1]
                SourceRef(
                    index = n,
                    whom = msg.whom,
                    postId = msg.id,
                    author = msg.author,
                    snippet = StoryCache.textFor(msg.id, msg.contentJson)
                        .replace('\n', ' ')
                        .take(SNIPPET_CHARS),
                )
            }
    }
}
