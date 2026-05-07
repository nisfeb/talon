package io.nisfeb.talon.ai

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop counterpart to the Android [Embedder]. Uses DJL's HuggingFace
 * + ONNX Runtime integration to run the
 * `sentence-transformers/all-MiniLM-L6-v2` model — 384-dim, ~80 MB
 * downloaded on first use and cached at `~/.djl.ai/cache/`.
 *
 * Singleton — model load is the dominant cost (~1s on warm cache,
 * 30s+ on cold first-run download). Lazy-init so the cost lands when
 * the user first runs a smart-search query, not at app start.
 *
 * Output dim is 384 (vs Android's 100 from MediaPipe's USE-Lite). The
 * embeddings table holds a `dim` column per row so the two formats can
 * coexist; the cosine search filter `if (v.size != queryVector.size)
 * continue` handles the mismatch — desktop ignores any Android-indexed
 * rows in the same DB and re-indexes from scratch on its own dim.
 */
class DesktopEmbedder {

    @Volatile private var model: ZooModel<String, FloatArray>? = null
    @Volatile private var predictor: Predictor<String, FloatArray>? = null
    @Volatile private var clientDim: Int = 0

    private fun ensurePredictor(): Predictor<String, FloatArray> {
        predictor?.let { return it }
        synchronized(this) {
            predictor?.let { return it }
            Log.i(TAG, "loading sentence-transformer model (cached or first-run download)")
            val criteria: Criteria<String, FloatArray> = Criteria.builder()
                .setTypes(String::class.java, FloatArray::class.java)
                .optModelUrls(MODEL_URL)
                .optEngine("OnnxRuntime")
                .optTranslatorFactory(TextEmbeddingTranslatorFactory())
                .build()
            val m = criteria.loadModel()
            val p = m.newPredictor()
            model = m
            predictor = p
            return p
        }
    }

    /**
     * Compute the embedding for [text]. Returns null on any embedder
     * failure (model load problem, malformed input, native crash) so
     * the caller can skip indexing this row instead of tearing down
     * the whole pipeline.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            val p = ensurePredictor()
            val raw = p.predict(text)
            // L2-normalize so cosine reduces to a dot product (matches
            // the Android embedder's behavior; SemanticSearch.cosine
            // works either way but the dot trick is faster).
            val out = l2Normalize(raw)
            if (clientDim == 0) clientDim = out.size
            out
        }.onFailure { err ->
            // Walk the cause chain — DJL / OnnxRuntime errors arrive
            // wrapped in ExceptionInInitializerError → InstantiationError
            // and the root cause is what tells us whether it's a
            // missing native, glibc mismatch, JNI extraction failure,
            // etc. Logging just `it.message` was leaving the actual
            // problem invisible (null on the wrapper). Stack traces
            // for the wrapper + each cause go to stderr; the journal
            // captures both.
            Log.w(TAG, "embed failed: ${err::class.simpleName}: ${err.message}")
            var cause: Throwable? = err.cause
            var depth = 0
            while (cause != null && depth < 5) {
                Log.w(TAG, "  caused by: ${cause::class.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            err.printStackTrace()
        }.getOrNull()
    }

    /** Output dimensionality. 0 until the first successful [embed]. */
    val dim: Int get() = clientDim

    companion object {
        private const val TAG = "DesktopEmbedder"
        // DJL URL syntax: djl://<engine>/<sentence-transformers id>.
        // Resolves to the bundled ONNX export hosted at
        // huggingface.co/sentence-transformers/all-MiniLM-L6-v2.
        // Updates flow through model-zoo metadata; pin via the engine
        // version in libs.versions.toml.
        private const val MODEL_URL =
            "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2"

        private fun l2Normalize(v: FloatArray): FloatArray {
            var sumSq = 0f
            for (x in v) sumSq += x * x
            val norm = kotlin.math.sqrt(sumSq)
            if (norm == 0f) return v
            val inv = 1f / norm
            val out = FloatArray(v.size)
            for (i in v.indices) out[i] = v[i] * inv
            return out
        }
    }
}
