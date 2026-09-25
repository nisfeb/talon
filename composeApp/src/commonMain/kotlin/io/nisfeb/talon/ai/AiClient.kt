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
import io.nisfeb.talon.util.Log
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
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
class AiClient(
    /** The feature whose month spend a call adds to, or none. */
    private val feature: AiFeature? = null,
    /** Its own by default; a test hands in one that answers for the providers. */
    private val http: io.ktor.client.HttpClient = createAppHttpClient(),
    private val settingsProvider: () -> AiSettings.Config,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** What the last call cost, where the provider says or the price is known. */
    var lastCostUsd: Double? = null
        private set

    /** One-shot completion. Throws on transport / HTTP error. */
    suspend fun complete(
        systemPrompt: String?,
        userPrompt: String,
        maxOutputTokens: Int = 1024,
        timeoutMs: Long = 60_000,
    ): String {
        val cfg = settingsProvider()
        lastCostUsd = null
        val text = when (cfg.provider) {
            AiSettings.Provider.Anthropic -> anthropic(cfg, systemPrompt, userPrompt, maxOutputTokens, timeoutMs)
            AiSettings.Provider.OpenRouter -> openaiCompat(
                cfg, systemPrompt, userPrompt, maxOutputTokens,
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                defaultModel = "anthropic/claude-sonnet-4",
                timeoutMs = timeoutMs,
            )
            AiSettings.Provider.OpenAi -> openaiCompat(
                cfg, systemPrompt, userPrompt, maxOutputTokens,
                endpoint = "https://api.openai.com/v1/chat/completions",
                defaultModel = "gpt-4o-mini",
                timeoutMs = timeoutMs,
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
                    timeoutMs = timeoutMs,
                )
            }
        }
        feature?.let { AiSpend.add(it.name, lastCostUsd) }
        return text
    }

    // ───────── Anthropic ─────────

    private suspend fun anthropic(
        cfg: AiSettings.Config,
        systemPrompt: String?,
        userPrompt: String,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        val model = cfg.model ?: "claude-sonnet-4-5-20250929"
        val payload = buildJsonObject {
            put("model", model)
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
            timeoutMs = timeoutMs,
        ) { body ->
            usageLine(cfg.provider, model, body)?.let { Log.i("AiClient", it) }
            lastCostUsd = usageCost(cfg.provider, model, body)
            // Shape: { content: [{type:"text", text:"..."}], ... }. A model
            // that thinks puts a thinking block first, so take the text.
            (body["content"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
                ?.takeIf { it.isNotEmpty() }
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
        timeoutMs: Long,
    ): String {
        val payload = buildJsonObject {
            put("model", cfg.model ?: defaultModel)
            put(outputCapKey(endpoint), maxTokens)
            // OpenRouter says what a call cost only when asked, and so
            // does an Armillary base, which is OpenRouter under a lease
            // and the vendor's own proxy otherwise.
            if (cfg.provider == AiSettings.Provider.OpenRouter || cfg.usageInclude) {
                put("usage", buildJsonObject { put("include", true) })
            }
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
            timeoutMs = timeoutMs,
        ) { body ->
            usageLine(cfg.provider, cfg.model ?: defaultModel, body)?.let { Log.i("AiClient", it) }
            lastCostUsd = usageCost(cfg.provider, cfg.model ?: defaultModel, body)
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
        timeoutMs: Long,
        parse: (JsonObject) -> T,
    ): T = withContext(ioDispatcher) {
        val resp = http.post(url) {
            contentType(ContentType.Application.Json)
            headers()
            setBody(payload)
            timeout { requestTimeoutMillis = timeoutMs }
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
            if (resp.status.value == 402) throw ModelHttpError(402, outOfCredit(host, msg))
            throw ModelHttpError(resp.status.value, "$host ${resp.status.value}: $msg")
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error("$host bad JSON: ${body.take(300)}") }
        parse(obj)
    }
}

/**
 * One call's cost in dollars, or null where it cannot be known.
 * OpenRouter says the cost; Anthropic says the tokens and the price is
 * Anthropic's list price (cache reads a tenth of input, cache writes a
 * quarter more). Other providers give tokens only.
 * ponytail: a price table in code; add a model's row when it ships.
 */
internal fun usageCost(provider: AiSettings.Provider, model: String, body: JsonObject): Double? {
    val u = body["usage"] as? JsonObject ?: return null
    fun n(k: String) = u[k]?.jsonPrimitive?.longOrNull ?: 0L
    return if (provider == AiSettings.Provider.Anthropic) {
        claudePrice(model)?.let { (i, o) ->
            (n("input_tokens") * i + n("cache_creation_input_tokens") * i * 1.25 + n("cache_read_input_tokens") * i * 0.1 + n("output_tokens") * o) / 1_000_000
        }
    } else {
        u["cost"]?.jsonPrimitive?.doubleOrNull
    }
}

/** One call's tokens and cost, for the log, priced by [usageCost]. */
internal fun usageLine(provider: AiSettings.Provider, model: String, body: JsonObject): String? {
    val u = body["usage"] as? JsonObject ?: return null
    fun n(k: String) = u[k]?.jsonPrimitive?.longOrNull ?: 0L
    fun dollars(d: Double) = "$" + (kotlin.math.round(d * 10_000) / 10_000).toString()
    val cost = usageCost(provider, model, body)
    if (provider == AiSettings.Provider.Anthropic) {
        val read = n("cache_read_input_tokens")
        val wrote = n("cache_creation_input_tokens")
        return "model $model: ${n("input_tokens")} in, ${n("output_tokens")} out" +
            (if (read + wrote > 0) ", cache $read read, $wrote written" else "") +
            ", " + (cost?.let(::dollars) ?: "cost unknown")
    }
    return "model $model: ${n("prompt_tokens")} in, ${n("completion_tokens")} out, " + (cost?.let(::dollars) ?: "cost not reported")
}

/** Dollars per million tokens, in and out, by model family. */
internal fun claudePrice(model: String): Pair<Double, Double>? {
    val m = model.lowercase()
    return when {
        "fable" in m || "mythos" in m -> 10.0 to 50.0
        "opus-5" in m || Regex("opus-4-[5-9]").containsMatchIn(m) -> 5.0 to 25.0
        "opus" in m -> 15.0 to 75.0
        "sonnet-5" in m -> 2.0 to 10.0
        "sonnet" in m -> 3.0 to 15.0
        "haiku-4" in m -> 1.0 to 5.0
        else -> null
    }
}

/**
 * A 402 is the one failure the person can fix themselves: the Armillary
 * balance ran out. Say so, and where to go, instead of the raw line.
 */
internal fun outOfCredit(host: String, msg: String): String =
    "$OUT_OF_CREDIT ($host 402: $msg)"

/** The words every empty-balance failure starts with, which the error surfaces look for. */
const val OUT_OF_CREDIT = "Your Armillary balance is empty. Top up under Settings, AI."

/** Whether a failure's message is the empty balance, so a Top up action belongs beside it. */
fun isOutOfCredit(message: String?): Boolean = message?.contains(OUT_OF_CREDIT) == true

/**
 * A model's answer that was not a success, with its status: what tells
 * a busy or unpaid provider from a request it will never take. Still an
 * IllegalStateException, as every such failure was before.
 */
class ModelHttpError(val status: Int, message: String) : IllegalStateException(message)

/**
 * No answer from a model that another try may get: the network, out of
 * credit, rate limited, a key revoked or a model unloaded, the
 * provider's own fault. Only a request the provider calls bad, 400, 413
 * or 422, a message its moderation flags, or a reply that cannot be
 * read, is about the input, and asking again gets the same. A key or a
 * model gone was read as the input's fault, and every message was
 * marked read unread; one flagged message read as the model down held
 * everything behind it.
 *
 * ponytail: providers say which in words only. A balance, billing, a
 * quota or a model that is not there is the account's, whatever the
 * status; moderation is the input's, whatever the status.
 */
fun isModelUnavailable(e: Throwable): Boolean {
    if (io.nisfeb.talon.util.isTransientNetworkError(e)) return true
    if (e !is ModelHttpError) return false
    val said = e.message.orEmpty().lowercase()
    if (listOf("moderation", "flagged").any { it in said }) return false
    if (listOf("credit", "balance", "billing", "quota", "not a valid model", "model not found", "no such model").any { it in said }) return true
    return e.status != 400 && e.status != 413 && e.status != 422
}
