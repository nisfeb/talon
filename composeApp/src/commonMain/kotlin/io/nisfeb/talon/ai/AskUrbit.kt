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
        // Hybrid retrieval: semantic for "messages about X", lexical for
        // "the message that literally said X" (see hybridSearch).
        val hits = embedder.hybridSearch(question, k)
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

        // Question words + connectives that carry no retrieval signal —
        // searching them matches almost everything. Distinctive nouns,
        // names and numbers survive the filter and drive the lexical pass.
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "but", "is", "are", "was", "were",
            "be", "been", "being", "to", "of", "in", "on", "at", "for", "with",
            "from", "by", "about", "into", "over", "what", "whats", "when",
            "where", "who", "whom", "why", "how", "which", "that", "this",
            "these", "those", "there", "here", "did", "do", "does", "done",
            "can", "could", "would", "should", "will", "shall", "may", "i",
            "you", "he", "she", "it", "we", "they", "me", "him", "her", "us",
            "them", "my", "your", "his", "its", "our", "their", "said", "say",
            "says", "tell", "show", "find", "any", "some", "anyone", "someone",
            "message", "messages", "chat", "talking", "mentioned", "remember",
        )

        /** Distinctive words to drive the lexical pass — lowercased,
         *  stop-worded, deduped, longest-first so rare nouns/names win
         *  the cap over short common words. Pure. */
        internal fun salientTerms(question: String, max: Int = 6): List<String> =
            question.split(Regex("[^\\p{L}\\p{N}]+"))
                .asSequence()
                .map { it.lowercase() }
                .filter { it.length >= 3 && it !in STOPWORDS }
                .distinct()
                .sortedByDescending { it.length }
                .take(max)
                .toList()

        /** Merge lexical + semantic candidates into one ordered, deduped
         *  list of at most [k]. Lexical gets [lexicalBudget] reserved
         *  front slots so an exact-keyword match can't be crowded out by
         *  semantic neighbours; semantic then fills the rest; any unused
         *  room is topped up with leftover lexical. Dedup is by
         *  (whom, id). Pure. */
        internal fun mergeHits(
            lexical: List<MessageEntity>,
            semantic: List<MessageEntity>,
            k: Int,
            lexicalBudget: Int = k / 2,
        ): List<MessageEntity> {
            val seen = LinkedHashSet<String>()
            val out = ArrayList<MessageEntity>(k)
            fun add(m: MessageEntity) {
                if (out.size >= k) return
                if (seen.add("${m.whom}:${m.id}")) out.add(m)
            }
            lexical.take(lexicalBudget.coerceAtLeast(0)).forEach(::add)
            semantic.forEach(::add)
            lexical.forEach(::add)
            return out
        }

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
