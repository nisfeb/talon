package io.nisfeb.talon.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * Thin HTTP layer for Anthropic / OpenRouter / OpenAI completions.
 * Two shapes are represented — Anthropic's `messages` endpoint uses a
 * slightly different wire format than the OpenAI-compatible ones, so
 * we dispatch by provider.
 *
 * Callers provide a system prompt + user prompt (everything the LLM
 * needs, no chat history); we return the text response. For anything
 * fancier (streaming, tool use, images) extend here.
 */
class AiClient(private val settingsProvider: () -> AiSettings.Config) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", cfg.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(req) { body ->
            // Shape: { content: [{type:"text", text:"..."}], ... }
            (body["content"] as? kotlinx.serialization.json.JsonArray)
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
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(req) { body ->
            body["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: error("no content in response: $body")
        }
    }

    private suspend fun <T> execute(req: Request, parse: (JsonObject) -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val host = req.url.host
                if (!resp.isSuccessful) {
                    // Try to extract a human message from the usual error
                    // shapes (Anthropic + OpenAI-compat are similar enough).
                    val pretty = runCatching {
                        val obj = json.parseToJsonElement(body).jsonObject
                        val err = (obj["error"] as? JsonObject)
                        err?.get("message")?.jsonPrimitive?.content
                            ?: obj["message"]?.jsonPrimitive?.content
                    }.getOrNull()
                    val msg = pretty ?: body.take(200)
                    error("$host ${resp.code}: $msg")
                }
                val obj = runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { error("$host bad JSON: ${body.take(300)}") }
                parse(obj)
            }
        }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
