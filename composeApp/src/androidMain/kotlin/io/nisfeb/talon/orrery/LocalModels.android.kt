package io.nisfeb.talon.orrery

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.nisfeb.talon.talonAppContext
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.cacheDirPath
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android's rungs: MediaPipe LLM Inference with the same Qwen2.5 1.5B
 * the desktop floor runs, in LiteRT's bundle, on the GPU where the
 * phone has one and the CPU where it does not. The model is 1.6 GB
 * and is fetched on first use, never bundled.
 *
 * Gemma's bundles would be smaller and faster, and they sit behind
 * Google's terms on a gated download, which an app cannot accept for
 * a person. AICore's Gemini Nano is the rung above this one when its
 * prompt API ships.
 */
actual fun localModelRungs(): List<Rung> = listOf(MediaPipeRung)

object MediaPipeRung : Rung() {
    override val name = "Qwen2.5 1.5B on this phone"
    private const val TAG = "MediaPipeRung"
    const val FILE = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    const val URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/$FILE"
    const val BYTES = 1_598_556_720L
    val file: File get() = File(cacheDirPath, "models/$FILE")

    /** Why the last open failed, so Settings can say instead of shrugging. */
    @Volatile private var lastFailure: String? = null

    override suspend fun status(): RungStatus = when {
        lastFailure != null -> RungStatus.Unavailable("The model could not start here: $lastFailure")
        !file.isFile -> RungStatus.NeedsDownload(BYTES)
        else -> RungStatus.Ready
    }

    override suspend fun prepare(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (file.isFile) return@withContext
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        val http = createAppHttpClient()
        try {
            val resp = http.get(URL) { timeout { requestTimeoutMillis = Long.MAX_VALUE } }
            val total = resp.headers["Content-Length"]?.toLongOrNull() ?: BYTES
            val ch = resp.bodyAsChannel()
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (!part.renameTo(file)) error("could not place the model file")
        } finally {
            http.close()
            part.delete()
        }
    }

    override suspend fun open(): LocalModel = withContext(Dispatchers.IO) {
        val ctx = talonAppContext ?: error("the application has no context yet")
        fun options(backend: LlmInference.Backend) = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.path)
            .setMaxTokens(1536)
            .setPreferredBackend(backend)
            .build()
        val llm = try {
            LlmInference.createFromOptions(ctx, options(LlmInference.Backend.GPU))
        } catch (gpu: Throwable) {
            Log.i(TAG, "GPU backend refused (${gpu.message}); trying the CPU")
            try {
                LlmInference.createFromOptions(ctx, options(LlmInference.Backend.CPU))
            } catch (cpu: Throwable) {
                lastFailure = cpu.message ?: cpu::class.simpleName
                throw cpu
            }
        }
        MediaPipeModel(llm, name)
    }
}

/**
 * A session per call, so nothing carries over between messages, at
 * temperature zero. The runtime's own turn wrapping is switched off
 * and Qwen's ChatML is applied here, once, the same as on desktop.
 * No grammar over this API; the parse's checks stand in.
 */
private class MediaPipeModel(private val llm: LlmInference, override val rung: String) : LocalModel {
    private val bare = PromptTemplates.builder()
        .setSystemPrefix("").setSystemSuffix("")
        .setUserPrefix("").setUserSuffix("")
        .setModelPrefix("").setModelSuffix("")
        .build()

    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0f)
            .setTopK(1)
            .setPromptTemplates(bare)
            .build()
        LlmInferenceSession.createFromOptions(llm, options).use { session ->
            session.addQueryChunk("<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n")
            session.generateResponse().substringBefore("<|im_end|>").trim()
        }
    }

    override fun close() = llm.close()
}
