package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.escapeLikeNeedle
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
    val msgByKey = HashMap<String, MessageEntity>()
    val hitsByKey = HashMap<String, Int>()
    for (term in terms) {
        val rows = db.messages().search(escapeLikeNeedle(term)).first()
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
suspend fun SearchEmbedderClient.hybridSearch(query: String, k: Int): List<MessageEntity> {
    val semantic = semanticSearch(query)
    val terms = AskUrbit.salientTerms(query)
    val lexical = if (terms.isEmpty()) emptyList() else keywordSearch(terms)
    return AskUrbit.mergeHits(lexical = lexical, semantic = semantic, k = k)
}
