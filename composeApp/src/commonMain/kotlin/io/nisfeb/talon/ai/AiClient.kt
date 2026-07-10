package io.nisfeb.talon.ai

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.createAppHttpClient
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Thin HTTP layer for Anthropic / OpenRouter / OpenAI completions.
 * Two shapes are represented — Anthropic's `messages` endpoint uses a
 * slightly different wire format than the OpenAI-compatible ones, so
 * we dispatch by provider.
 *
 * Callers provide a system prompt + user prompt (everything the LLM
 * needs, no chat history); we return the text response.
 */
class AiClient(private val settingsProvider: () -> AiSettings.Config) {

    private val http = createAppHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** One-shot completion. Throws on transport / HTTP error. */
    suspend fun complete(
        systemPrompt: String?,
        userPrompt: String,
        maxOutputTokens: Int = 1024,
    ): String {
        val cfg = settingsProvider()
        return when (cfg.provider) {
            AiSettings.Provider.Anthropic -> anthropic(cfg, systemPrompt, userPrompt, maxOutputTokens)
            AiSettings.Provider.OpenRouter -> openaiCompat(
                cfg, systemPrompt, userPrompt, maxOutputTokens,
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                defaultModel = "anthropic/claude-sonnet-4",
            )
            AiSettings.Provider.OpenAi -> openaiCompat(
                cfg, systemPrompt, userPrompt, maxOutputTokens,
                endpoint = "https://api.openai.com/v1/chat/completions",
                defaultModel = "gpt-4o-mini",
            )
            AiSettings.Provider.Custom -> {
                val base = cfg.baseUrl?.trimEnd('/')
                    ?: error("Custom provider requires a base URL")
                val endpoint =
                    if (base.endsWith("/chat/completions")) base
                    else "$base/chat/completions"
                openaiCompat(
                    cfg, systemPrompt, userPrompt, maxOutputTokens,
                    endpoint = endpoint,
                    defaultModel = cfg.model ?: error(
                        "Custom provider requires a model name",
                    ),
                )
            }
        }
    }

    // ───────── Anthropic ─────────

    private suspend fun anthropic(
        cfg: AiSettings.Config,
        systemPrompt: String?,
        userPrompt: String,
        maxTokens: Int,
    ): String {
        val payload = buildJsonObject {
            put("model", cfg.model ?: "claude-sonnet-4-5-20250929")
            put("max_tokens", maxTokens)
            systemPrompt?.let { put("system", it) }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
        }
        return execute(
            url = "https://api.anthropic.com/v1/messages",
            payload = payload.toString(),
            headers = {
                header("x-api-key", cfg.apiKey)
                header("anthropic-version", "2023-06-01")
            },
        ) { body ->
            // Shape: { content: [{type:"text", text:"..."}], ... }
            (body["content"] as? JsonArray)
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
                ?: error("no content in response: $body")
        }
    }

    // ───────── OpenAI / OpenRouter ─────────

    private suspend fun openaiCompat(
        cfg: AiSettings.Config,
        systemPrompt: String?,
        userPrompt: String,
        maxTokens: Int,
        endpoint: String,
        defaultModel: String,
    ): String {
        val payload = buildJsonObject {
            put("model", cfg.model ?: defaultModel)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                systemPrompt?.let {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", it)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
        }
        return execute(
            url = endpoint,
            payload = payload.toString(),
            headers = { header("Authorization", "Bearer ${cfg.apiKey}") },
        ) { body ->
            body["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: error("no content in response: $body")
        }
    }

    private suspend fun <T> execute(
        url: String,
        payload: String,
        headers: HttpRequestBuilder.() -> Unit,
        parse: (JsonObject) -> T,
    ): T = withContext(ioDispatcher) {
        val resp = http.post(url) {
            contentType(ContentType.Application.Json)
            headers()
            setBody(payload)
            timeout { requestTimeoutMillis = 60_000 }
        }
        val body = resp.bodyAsText()
        val host = Url(url).host
        if (!resp.status.isSuccess()) {
            val pretty = runCatching {
                val obj = json.parseToJsonElement(body).jsonObject
                val err = (obj["error"] as? JsonObject)
                err?.get("message")?.jsonPrimitive?.content
                    ?: obj["message"]?.jsonPrimitive?.content
            }.getOrNull()
            val msg = pretty ?: body.take(200)
            error("$host ${resp.status.value}: $msg")
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error("$host bad JSON: ${body.take(300)}") }
        parse(obj)
    }
}
