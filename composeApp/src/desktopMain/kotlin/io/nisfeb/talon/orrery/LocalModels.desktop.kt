package io.nisfeb.talon.orrery

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.readAvailable
import io.nisfeb.talon.util.AppDirs
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.cacheDirPath
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The desktop's rungs, best first: a local server the person already
 * runs, which may hold a far larger model than anything Talon would
 * download, then the floor, llama.cpp on the JVM with a small model
 * fetched on first use.
 */
actual fun localModelRungs(): List<Rung> = listOf(LocalServerRung, LlamaRung)

/**
 * A local server the person already runs, speaking the OpenAI shape:
 * LM Studio on 1234, Ollama on 11434. Local by definition, and it may
 * hold a far larger model than anything Talon would download. The
 * first that answers wins; a model is picked by name preference.
 */

/**
 * The floor: llama.cpp on the JVM, CPU, with Qwen2.5 1.5B at 4-bit
 * (Apache 2.0, about 1.1 GB), fetched on first use into the cache.
 * The first open runs a child JVM once, so a native crash marks the
 * rung unusable instead of taking the app with it.
 */
object LlamaRung : Rung() {
    override val name = "Qwen2.5 1.5B on this computer"
    const val FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/$FILE"
    const val BYTES = 1_120_000_000L
    val file: File get() = File(cacheDirPath, "models/$FILE")

    override suspend fun status(): RungStatus = when {
        LlamaProbe.verdict() == false -> RungStatus.Unavailable("The model runtime crashed on this computer once, so it is not used.")
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
        if (LlamaProbe.verdict() == null && !LlamaProbe.run(file)) error("the model runtime failed its probe")
        if (LlamaProbe.verdict() == false) error("the model runtime is marked unusable here")
        LlamaCppModel(file.path, name)
    }
}

/** Qwen's ChatML turns, a grammar-bounded answer, temperature zero. */
class LlamaCppModel(path: String, override val rung: String) : LocalModel {
    private val model = LlamaModel(ModelParameters().setModel(path).setCtxSize(4096))

    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val prompt = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        val params = InferenceParameters(prompt).setNPredict(maxTokens).setTemperature(0f)
        if (grammar != null) params.setGrammar(grammar)
        model.complete(params)
    }

    override fun close() = model.close()
}

/**
 * The one-time child-JVM check for the llama.cpp natives, on the
 * pattern of [io.nisfeb.talon.ai.EmbedderProbe]: a clean exit means
 * safe, anything else marks the rung unusable until the next version.
 */
object LlamaProbe {
    private const val TAG = "LlamaProbe"
    private const val VERSION = 1
    private const val TIMEOUT_MS = 240_000L
    private val file: File get() = File(AppDirs.userData, "llama_probe")

    /** True or false once decided, null before. */
    fun verdict(): Boolean? = runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.let {
        when (it) { "v$VERSION:ok" -> true; "v$VERSION:bad" -> false; else -> null }
    }

    fun run(model: File): Boolean {
        val ok = runCatching {
            val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
            val cp = System.getProperty("java.class.path") ?: return false
            val proc = ProcessBuilder(javaBin, "-cp", cp, "io.nisfeb.talon.orrery.LlamaProbeMainKt", model.path)
                .redirectErrorStream(true).start()
            if (!proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { proc.destroyForcibly(); return false }
            proc.exitValue() == 0
        }.getOrElse { Log.w(TAG, "probe spawn failed: ${it.message}"); false }
        runCatching { AppDirs.userData.mkdirs(); file.writeText("v$VERSION:" + if (ok) "ok" else "bad") }
        Log.i(TAG, "verdict: ${if (ok) "ok" else "bad"}")
        return ok
    }
}

