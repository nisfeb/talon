package io.nisfeb.talon.ai

/**
 * Local topic grouping for the assistant. A new question joins the
 * current conversation when its embedding is close enough to the
 * conversation's centroid (the running mean of its question vectors);
 * otherwise a new conversation begins. This is what bounds context —
 * only a conversation's own recent turns feed the model, and a topic
 * shift resets it.
 */
object ConversationGrouper {
    // ponytail: cosine threshold tuned by feel. The on-device sentence
    // encoder puts related short texts well above unrelated ones; 0.45
    // is a middle ground. Raise it to split topics more eagerly, lower
    // to keep a conversation together longer. The "New conversation"
    // button is the manual override when the heuristic guesses wrong.
    const val CONTINUE_THRESHOLD = 0.45f

    /** How many of a conversation's most recent turns to replay to the
     *  model as context. Caps prompt growth on a long-running topic. */
    const val CONTEXT_TURNS = 6

    /** True if [questionVec] is on-topic for a conversation summarised by
     *  running mean [centroid]. Both must be same-dim and non-empty;
     *  otherwise we can't tell, so treat it as a new topic (false). */
    fun continues(
        questionVec: FloatArray?,
        centroid: FloatArray?,
        threshold: Float = CONTINUE_THRESHOLD,
    ): Boolean {
        if (questionVec == null || centroid == null) return false
        if (questionVec.isEmpty() || centroid.isEmpty()) return false
        if (questionVec.size != centroid.size) return false
        return cosine(questionVec, centroid) >= threshold
    }

    /** Mean of [vectors] — used to rebuild a conversation's centroid from
     *  its turns' question embeddings. A conversation synced from another
     *  device arrives without a centroid (embeddings are device-local and
     *  the two platforms' vector spaces differ), so the first time this
     *  device would group against it we recompute one here. Null if there
     *  are no usable (non-empty, same-dim) vectors. */
    fun centroidOf(vectors: List<FloatArray>): FloatArray? {
        val usable = vectors.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return null
        val dim = usable.first().size
        val sum = FloatArray(dim)
        var n = 0
        for (v in usable) {
            if (v.size != dim) continue // skip a stray off-dim vector
            for (i in 0 until dim) sum[i] += v[i]
            n++
        }
        if (n == 0) return null
        for (i in 0 until dim) sum[i] /= n
        return sum
    }

    /** Running mean after folding [vec] into a [centroid] that already
     *  summarised [count] vectors. Returns [vec] if shapes don't line up
     *  (e.g. a conversation that started before embeddings were on). */
    fun updateCentroid(centroid: FloatArray, count: Int, vec: FloatArray): FloatArray {
        if (vec.isEmpty()) return centroid
        if (centroid.size != vec.size || count <= 0) return vec.copyOf()
        val n = count.toFloat()
        return FloatArray(vec.size) { i -> (centroid[i] * n + vec[i]) / (n + 1f) }
    }
}
