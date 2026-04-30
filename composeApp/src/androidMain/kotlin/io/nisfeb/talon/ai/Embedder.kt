package io.nisfeb.talon.ai

import android.content.Context
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device sentence embedder. Wraps MediaPipe Tasks Text Embedder
 * with the Universal Sentence Encoder Lite model bundled in
 * `assets/text_embedder.tflite`. ~6MB model, 100-dim float vectors,
 * <20ms per call on a recent Pixel.
 *
 * Singleton — the underlying TextEmbedder is expensive to create and
 * thread-safe to share. Lazy-initialized so the cost lands on the
 * first call (already off the main thread because [embed] is suspend).
 */
class Embedder(private val context: Context) {

    @Volatile private var client: TextEmbedder? = null
    @Volatile private var clientDim: Int = 0

    private fun ensureClient(): TextEmbedder {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
                )
                .setL2Normalize(true) // saves a normalize per cosine call
                .build()
            val c = TextEmbedder.createFromOptions(context, options)
            client = c
            return c
        }
    }

    /**
     * Compute the embedding for [text]. The model expects a single
     * sentence-ish chunk; long messages get truncated by the model
     * itself (no need to chunk on our side for chat-length input).
     *
     * Returns null on any embedder failure (model load issue, etc.) so
     * the caller can skip indexing this row instead of crashing.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = ensureClient()
            val result = c.embed(text)
            val emb: Embedding = result.embeddingResult().embeddings().first()
            emb.floatEmbedding()
        }.onFailure {
            android.util.Log.w(TAG, "embed failed", it)
        }.getOrNull()?.also {
            if (clientDim == 0) clientDim = it.size
        }
    }

    /** Output dimensionality of the loaded model. 0 until the first
     *  successful embed call. */
    val dim: Int get() = clientDim

    companion object {
        private const val TAG = "Embedder"
        private const val MODEL_ASSET = "text_embedder.tflite"
    }
}
