package io.nisfeb.talon.ai

import io.nisfeb.talon.urbit.asText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Tool-use sibling of [AiClient]: one round-trip of an agentic
 * conversation. Given the running [AgentMessage] history and the
 * advertised [ToolSpec]s, returns the model's [AgentTurn] — either a
 * final answer or a batch of tool calls to run.
 *
 * The request-building and response-parsing are pure top-level functions
 * (`build*Request` / `parse*Turn`) so the wire translation for both the
 * Anthropic and OpenAI dialects is unit-tested without a live API.
 */
class AgentClient(private val settingsProvider: () -> AiSettings.Config) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun completeWithTools(
        system: String,
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
        maxOutputTokens: Int = 1024,
    ): AgentTurn {
        val cfg = settingsProvider()
        return when (cfg.provider) {
            AiSettings.Provider.Anthropic -> {
                val payload = buildAnthropicRequest(
                    cfg.model ?: "claude-sonnet-4-5-20250929",
                    system, messages, tools, maxOutputTokens,
                )
                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", cfg.apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                execute(req) { parseAnthropicTurn(it) }
            }
            AiSettings.Provider.OpenRouter -> openai(
                cfg, system, messages, tools, maxOutputTokens,
                "https://openrouter.ai/api/v1/chat/completions",
                cfg.model ?: "anthropic/claude-sonnet-4",
            )
            AiSettings.Provider.OpenAi -> openai(
                cfg, system, messages, tools, maxOutputTokens,
                "https://api.openai.com/v1/chat/completions",
                cfg.model ?: "gpt-4o-mini",
            )
            AiSettings.Provider.Custom -> {
                val base = cfg.baseUrl?.trimEnd('/')
                    ?: error("Custom provider requires a base URL")
                val endpoint =
                    if (base.endsWith("/chat/completions")) base
                    else "$base/chat/completions"
                openai(
                    cfg, system, messages, tools, maxOutputTokens, endpoint,
                    cfg.model ?: error("Custom provider requires a model name"),
                )
            }
        }
    }

    private suspend fun openai(
        cfg: AiSettings.Config,
        system: String,
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
        maxTokens: Int,
        endpoint: String,
        model: String,
    ): AgentTurn {
        val payload = buildOpenAiRequest(model, system, messages, tools, maxTokens)
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(req) { parseOpenAiTurn(it) }
    }

    private suspend fun execute(req: Request, parse: (JsonObject) -> AgentTurn): AgentTurn =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val host = req.url.host
                if (!resp.isSuccessful) {
                    error(extractApiError(host, resp.code, body))
                }
                val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }
                    .getOrElse { error("$host bad JSON: ${body.take(300)}") }
                parse(obj)
            }
        }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        internal val JSON = Json { ignoreUnknownKeys = true }
    }
}

// ───────── pure wire translation (testable) ─────────

internal fun buildAnthropicRequest(
    model: String,
    system: String,
    messages: List<AgentMessage>,
    tools: List<ToolSpec>,
    maxTokens: Int,
): JsonObject = buildJsonObject {
    put("model", model)
    put("max_tokens", maxTokens)
    put("system", system)
    putJsonArray("tools") {
        tools.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("input_schema", t.parameters)
            })
        }
    }
    putJsonArray("messages") {
        messages.forEach { m ->
            when (m) {
                is AgentMessage.User -> add(buildJsonObject {
                    put("role", "user")
                    put("content", m.text)
                })
                is AgentMessage.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        if (!m.text.isNullOrBlank()) add(buildJsonObject {
                            put("type", "text"); put("text", m.text)
                        })
                        m.toolCalls.forEach { c ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", c.id)
                                put("name", c.name)
                                put("input", c.args)
                            })
                        }
                    }
                })
                is AgentMessage.ToolResults -> add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        m.results.forEach { r ->
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", r.id)
                                put("content", r.content)
                            })
                        }
                    }
                })
            }
        }
    }
}

internal fun parseAnthropicTurn(body: JsonObject): AgentTurn {
    val content = body["content"] as? JsonArray ?: JsonArray(emptyList())
    val text = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    for (block in content) {
        val obj = block as? JsonObject ?: continue
        when (obj["type"]?.jsonPrimitive?.content) {
            "text" -> text.append(obj["text"]?.jsonPrimitive?.content.orEmpty())
            "tool_use" -> calls.add(
                ToolCall(
                    id = obj["id"]?.jsonPrimitive?.content.orEmpty(),
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    args = obj["input"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
            )
        }
    }
    return if (calls.isEmpty()) AgentTurn.Final(text.toString())
    else AgentTurn.Calls(text.toString().ifBlank { null }, calls)
}

internal fun buildOpenAiRequest(
    model: String,
    system: String,
    messages: List<AgentMessage>,
    tools: List<ToolSpec>,
    maxTokens: Int,
): JsonObject = buildJsonObject {
    put("model", model)
    put("max_tokens", maxTokens)
    putJsonArray("tools") {
        tools.forEach { t ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parameters)
                })
            })
        }
    }
    putJsonArray("messages") {
        add(buildJsonObject { put("role", "system"); put("content", system) })
        messages.forEach { m ->
            when (m) {
                is AgentMessage.User -> add(buildJsonObject {
                    put("role", "user"); put("content", m.text)
                })
                is AgentMessage.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", m.text ?: "")
                    if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                        m.toolCalls.forEach { c ->
                            add(buildJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", c.name)
                                    put("arguments", c.args.toString())
                                })
                            })
                        }
                    }
                })
                is AgentMessage.ToolResults -> m.results.forEach { r ->
                    add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", r.id)
                        put("content", r.content)
                    })
                }
            }
        }
    }
}

internal fun parseOpenAiTurn(body: JsonObject): AgentTurn {
    val msg = (body["choices"] as? JsonArray)?.firstOrNull()
        ?.jsonObject?.get("message")?.jsonObject
        ?: return AgentTurn.Final("")
    // "content" is null on the common tool-call turn, a string on a plain
    // answer, and — from OpenRouter and some OpenAI-compatible providers —
    // an array of typed parts. asText() degrades the first two to null/text
    // instead of throwing; the array we join like parseAnthropicTurn does.
    val text = when (val c = msg["content"]) {
        is JsonArray -> c.mapNotNull { (it as? JsonObject)?.get("text").asText() }
            .joinToString("")
            .ifEmpty { null }
        else -> c.asText()
    }
    val calls = (msg["tool_calls"] as? JsonArray)?.mapNotNull { tc ->
        val o = tc as? JsonObject ?: return@mapNotNull null
        val fn = o["function"]?.jsonObject ?: return@mapNotNull null
        val argStr = fn["arguments"]?.jsonPrimitive?.content.orEmpty()
        ToolCall(
            id = o["id"]?.jsonPrimitive?.content.orEmpty(),
            name = fn["name"]?.jsonPrimitive?.content.orEmpty(),
            args = parseArgs(argStr),
        )
    }.orEmpty()
    return if (calls.isEmpty()) AgentTurn.Final(text.orEmpty())
    else AgentTurn.Calls(text?.ifBlank { null }, calls)
}

/** OpenAI ships tool arguments as a JSON-encoded string; decode to an
 *  object, tolerating empty / malformed payloads. */
private fun parseArgs(raw: String): JsonObject =
    runCatching { (AgentClient.JSON.parseToJsonElement(raw) as? JsonObject) }
        .getOrNull() ?: JsonObject(emptyMap())

/**
 * Build a diagnosable message from an error response. OpenRouter wraps an
 * upstream provider failure as a generic `error.message` ("Provider
 * returned error") and stashes the real reason in `error.metadata`
 * (`provider_name` + `raw`, the raw being the provider's own JSON-or-
 * string error). Surface that so a 400 is actionable instead of opaque.
 * Pure + internal so it's unit-tested without a live API.
 */
internal fun extractApiError(host: String, code: Int, body: String): String {
    val parsed = runCatching {
        val obj = AgentClient.JSON.parseToJsonElement(body).jsonObject
        val err = obj["error"] as? JsonObject
        val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj["message"]?.jsonPrimitive?.contentOrNull
        val meta = err?.get("metadata") as? JsonObject
        val provider = meta?.get("provider_name")?.jsonPrimitive?.contentOrNull
        val upstream = meta?.get("raw")?.let { upstreamMessage(it) }
        buildString {
            append(msg.orEmpty())
            if (!provider.isNullOrBlank()) append(" (provider: $provider)")
            if (!upstream.isNullOrBlank() && upstream != msg) {
                if (isNotEmpty()) append(" — ")
                append(upstream)
            }
        }.trim().ifBlank { null }
    }.getOrNull()
    return "$host $code: ${(parsed ?: body.take(300)).take(600)}"
}

/** `error.metadata.raw` is either the upstream provider's error object, or
 *  a JSON-encoded string of one, or an opaque string. Dig out its nested
 *  message when present; otherwise return the raw text. */
private fun upstreamMessage(raw: JsonElement): String? {
    val asObj = raw as? JsonObject
        ?: (raw as? JsonPrimitive)?.contentOrNull?.let { s ->
            runCatching { AgentClient.JSON.parseToJsonElement(s).jsonObject }.getOrNull()
        }
    val nested = (asObj?.get("error") as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        ?: asObj?.get("message")?.jsonPrimitive?.contentOrNull
    return nested ?: (raw as? JsonPrimitive)?.contentOrNull
}
