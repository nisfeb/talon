package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.nisfeb.talon.ui.isTouchPrimary
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A model on a server that speaks the OpenAI shape: LM Studio or
 * Ollama on this machine, or on another machine of yours.
 *
 * Common code, and on every platform's ladder, because nothing about
 * it is particular to a computer. It is HTTP. A phone pointed at the
 * machine under your desk reads with whatever is loaded there, which
 * is a great deal more than a phone can hold.
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
            probe("The server at $chosen", chosen, "/v1/models", OPENAI_MODELS)
                ?: probe("The server at $chosen", chosen, "/api/tags", OLLAMA_TAGS)
        } else if (isTouchPrimary) {
            // Nothing is listening on a phone's own ports, so it looks
            // only where it has been told to.
            null
        } else {
            probe("LM Studio", "http://localhost:1234", "/v1/models", OPENAI_MODELS)
                ?: probe("Ollama", "http://localhost:11434", "/api/tags", OLLAMA_TAGS)
        }
        return when {
            found != null -> RungStatus.Ready
            chosen.isNotEmpty() -> RungStatus.Unavailable("Nothing answers at $chosen.")
            isTouchPrimary -> RungStatus.Unavailable("No server set. This device reads with its own model.")
            else -> RungStatus.Unavailable("No LM Studio (port 1234) or Ollama (port 11434) is running.")
        }
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
            // Down a format only when the refusal is about the format: a
            // prompt over the context answers 400 too, and one long post
            // took the schema off every request after it.
            val aboutFormat = listOf("response_format", "json_schema", "json_object", "schema", "grammar").any { it in text.lowercase() }
            if (resp.status.value == 400 && format < 2 && aboutFormat) { format++; continue }
            if (resp.status.value >= 400) throw io.nisfeb.talon.ai.ModelHttpError(resp.status.value, "${server.label} answered ${resp.status.value}: ${text.take(160)}")
            return Json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }
    }

    override fun close() = Unit

    companion object {
        /** The answer's shape as JSON Schema, the same one the grammar bounds on the floor; a strict schema wants every key, so no plan is null. */
        const val CLAIMS_SCHEMA = """{"type":"object","properties":{"claims":{"type":"array","items":{"type":"object","properties":{"subject":{"type":"string"},"attr":{"type":"string"},"value":{"anyOf":[{"type":"string"},{"type":"null"},{"type":"object","properties":{"ref":{"type":"string"}},"required":["ref"],"additionalProperties":false}]},"conf":{"type":"number"},"until_hours":{"type":"number"}},"required":["subject","attr","value","conf"],"additionalProperties":false}},"plan":{"anyOf":[{"type":"null"},{"type":"object","properties":{"title":{"type":"string"},"starts":{"type":"string"},"ends":{"type":["string","null"]},"location":{"type":["string","null"]}},"required":["title","starts","ends","location"],"additionalProperties":false}]}},"required":["claims","plan"],"additionalProperties":false}"""
    }
}

/** A server model by address and name, with no probe, for the fixture gate. */
internal fun serverModel(base: String, model: String): LocalModel =
    OpenAiShapeModel(createAppHttpClient(), LocalServerRung.Server("server", base.trimEnd('/'), model), "$model at $base")

/** The model ids an OpenAI-shaped server lists at /v1/models: LM Studio, llama.cpp. */
private val OPENAI_MODELS: (kotlinx.serialization.json.JsonElement) -> List<String> =
    { it.jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { m -> m.jsonObject["id"]?.jsonPrimitive?.content } }

/** The model names Ollama lists at /api/tags. */
private val OLLAMA_TAGS: (kotlinx.serialization.json.JsonElement) -> List<String> =
    { it.jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { m -> m.jsonObject["name"]?.jsonPrimitive?.content } }
