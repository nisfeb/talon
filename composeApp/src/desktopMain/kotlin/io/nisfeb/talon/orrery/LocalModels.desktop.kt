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
object LocalServerRung : Rung() {
    override val name: String get() = found?.let { "${it.label} on this computer (${it.model})" } ?: "A local model server"
    private val http: HttpClient by lazy { createAppHttpClient() }
    private val preferred = listOf("qwen2.5", "qwen3", "llama-3", "llama3", "gemma-3", "gemma3", "gemma", "mistral", "phi")

    data class Server(val label: String, val base: String, val model: String)
    @Volatile private var found: Server? = null

    override suspend fun status(): RungStatus {
        val chosen = LocalModels.serverUrl.trim().trimEnd('/')
        found = if (chosen.isNotEmpty()) {
            // The person named a server: that one, whichever shape it speaks.
            probe("The server at $chosen", chosen, "/v1/models") { it.jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { m -> m.jsonObject["id"]?.jsonPrimitive?.content } }
                ?: probe("The server at $chosen", chosen, "/api/tags") { it.jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { m -> m.jsonObject["name"]?.jsonPrimitive?.content } }
        } else {
            probe("LM Studio", "http://localhost:1234", "/v1/models") { it.jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { m -> m.jsonObject["id"]?.jsonPrimitive?.content } }
                ?: probe("Ollama", "http://localhost:11434", "/api/tags") { it.jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { m -> m.jsonObject["name"]?.jsonPrimitive?.content } }
        }
        return if (found != null) RungStatus.Ready
        else RungStatus.Unavailable(if (chosen.isNotEmpty()) "Nothing answers at $chosen." else "No LM Studio (port 1234) or Ollama (port 11434) is running.")
    }

    private suspend fun probe(label: String, base: String, path: String, names: (kotlinx.serialization.json.JsonElement) -> List<String>): Server? = runCatching {
        val text = http.get("$base$path") { timeout { requestTimeoutMillis = 1500 } }.bodyAsText()
        val all = names(Json.parseToJsonElement(text)).filterNot { it.contains("embed", ignoreCase = true) }
        val wanted = LocalModels.serverModel.trim()
        val pick = when {
            // A named model is used as named, listed or not: a server may load it on demand.
            wanted.isNotEmpty() -> all.firstOrNull { it.equals(wanted, ignoreCase = true) } ?: wanted
            else -> preferred.firstNotNullOfOrNull { pre -> all.firstOrNull { it.lowercase().contains(pre) } } ?: all.firstOrNull()
        }
        pick?.let { Server(label, base, it) }
    }.getOrNull()

    override suspend fun open(): LocalModel = OpenAiShapeModel(http, found ?: error("no server"), name)
}

/**
 * The OpenAI chat shape with the answer's schema attached, which both
 * servers can enforce; a server that refuses the schema gets JSON mode,
 * and one that refuses that gets the prompt alone and the parse's
 * checks.
 */
internal class OpenAiShapeModel(private val http: HttpClient, private val server: LocalServerRung.Server, override val rung: String) : LocalModel {
    private var format = 0 // 0 schema, 1 json_object, 2 none

    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
        while (true) {
            val body = buildJsonObject {
                put("model", server.model)
                put("temperature", 0)
                put("max_tokens", maxTokens)
                when (format) {
                    0 -> put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject { put("name", "claims"); put("strict", true); put("schema", Json.parseToJsonElement(CLAIMS_SCHEMA)) })
                    })
                    1 -> put("response_format", buildJsonObject { put("type", "json_object") })
                }
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                })
            }
            val resp = http.post("${server.base}/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                // A server of your own usually wants no key. One behind a
                // proxy, or a hosted private model, does.
                LocalModels.serverKey.takeIf { it.isNotBlank() }
                    ?.let { header(io.ktor.http.HttpHeaders.Authorization, "Bearer $it") }
                setBody(body.toString())
                timeout { requestTimeoutMillis = 180_000 }
            }
            val text = resp.bodyAsText()
            if (resp.status.value == 400 && format < 2) { format++; continue }
            if (resp.status.value >= 400) error("${server.label} answered ${resp.status.value}: ${text.take(160)}")
            return Json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }
    }

    override fun close() = Unit

    companion object {
        /** The answer's shape as JSON Schema, the same one the grammar bounds on the floor. */
        const val CLAIMS_SCHEMA = """{"type":"object","properties":{"claims":{"type":"array","items":{"type":"object","properties":{"subject":{"type":"string"},"attr":{"type":"string"},"value":{"anyOf":[{"type":"string"},{"type":"null"},{"type":"object","properties":{"ref":{"type":"string"}},"required":["ref"],"additionalProperties":false}]},"conf":{"type":"number"},"until_hours":{"type":"number"}},"required":["subject","attr","value","conf"],"additionalProperties":false}}},"required":["claims"],"additionalProperties":false}"""
    }
}

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

/** A server model by address and name, with no probe, for the fixture gate. */
internal fun serverModel(base: String, model: String): LocalModel =
    OpenAiShapeModel(createAppHttpClient(), LocalServerRung.Server("server", base.trimEnd('/'), model), "$model at $base")
