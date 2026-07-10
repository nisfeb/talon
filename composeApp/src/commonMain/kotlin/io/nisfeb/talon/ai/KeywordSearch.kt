package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.escapeLikeNeedle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Lexical recall for the assistant. [SemanticSearch] is strong at
 * "messages *about* X" but weak at "the message that literally said X" —
 * a short, specifically-worded line can embed far from the natural
 * question that's looking for it, so it never clears the cosine floor.
 * This LIKE pass closes that gap.
 *
 * Each term runs through the existing [io.nisfeb.talon.data.MessageDao.search]
 * substring query; results are unioned and ranked by how many of the
 * query's terms a message contains (more distinct terms → more likely
 * the one the user means), then recency. Capped so a single common term
 * can't flood the merge.
 */
internal suspend fun keywordSearch(
    db: AppDatabase,
    terms: List<String>,
    cap: Int = 40,
): List<MessageEntity> {
    if (terms.isEmpty()) return emptyList()
    // One LIKE scan per term, and they don't depend on each other.
    val perTerm = coroutineScope {
        terms.map { term -> async { db.messages().search(escapeLikeNeedle(term)).first() } }
            .awaitAll()
    }
    val msgByKey = HashMap<String, MessageEntity>()
    val hitsByKey = HashMap<String, Int>()
    for (rows in perTerm) {
        for (m in rows) {
            val key = "${m.whom}:${m.id}"
            msgByKey[key] = m
            hitsByKey[key] = (hitsByKey[key] ?: 0) + 1
        }
    }
    return msgByKey.values
        .sortedWith(
            compareByDescending<MessageEntity> { hitsByKey["${it.whom}:${it.id}"] ?: 0 }
                .thenByDescending { it.sentMs },
        )
        .take(cap)
}

/**
 * Semantic + lexical retrieval merged into one ranked list — the single
 * retrieval path the assistant uses everywhere (the Ask answer and the
 * agent's `search_history` tool), so a specifically-worded message the
 * embedding model ranks low isn't missed. See [AskUrbit.mergeHits].
 */
suspend fun SearchEmbedderClient.hybridSearch(query: String, k: Int): List<MessageEntity> =
    coroutineScope {
        // Embed-then-vector-scan and the LIKE pass share no state; the merge
        // needs both, so run them side by side instead of back to back.
        val semantic = async { semanticSearch(query) }
        val terms = AskUrbit.salientTerms(query)
        val lexical = async { if (terms.isEmpty()) emptyList() else keywordSearch(terms) }
        AskUrbit.mergeHits(lexical = lexical.await(), semantic = semantic.await(), k = k)
    }
