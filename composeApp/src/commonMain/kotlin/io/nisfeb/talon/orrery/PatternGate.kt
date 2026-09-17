package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.SearchEmbedderClient
import io.nisfeb.talon.ai.dotNorm
import kotlin.math.sqrt

/**
 * Whether a message is worth a model run, learnt from the person.
 *
 * Two centroids over the embedder's vectors: what they confirmed in
 * the tray, and what they discarded. A message closer to the discarded
 * side by a margin skips the model; the rules still read it. Until
 * there are enough of each kind to say anything, there is no gate and
 * every message in scope gets its turn. Nothing is trained, so a
 * change of heart shows on the next pass.
 */
class PatternGate internal constructor(private val yes: FloatArray, private val no: FloatArray) {
    fun worthAModel(v: FloatArray): Boolean = dotNorm(v, yes) + MARGIN >= dotNorm(v, no)

    companion object {
        const val MIN_EACH = 5
        const val MARGIN = 0.05f

        suspend fun build(embedder: SearchEmbedderClient, confirmed: List<String>, discarded: List<String>): PatternGate? {
            if (confirmed.size < MIN_EACH || discarded.size < MIN_EACH) return null
            val yes = centroid(confirmed.mapNotNull { embedder.embed(it) }) ?: return null
            val no = centroid(discarded.mapNotNull { embedder.embed(it) }) ?: return null
            return PatternGate(yes, no)
        }

        /** The mean of unit vectors, made unit again; null with too few. */
        internal fun centroid(vs: List<FloatArray>): FloatArray? {
            if (vs.size < MIN_EACH) return null
            val dim = vs.first().size
            val c = FloatArray(dim)
            for (v in vs) if (v.size == dim) for (i in 0 until dim) c[i] += v[i]
            var norm = 0f
            for (x in c) norm += x * x
            norm = sqrt(norm)
            if (norm == 0f) return null
            for (i in 0 until dim) c[i] /= norm
            return c
        }
    }
}
