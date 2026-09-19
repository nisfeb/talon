package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.nisfeb.talon.orrery.RungStatus
import io.nisfeb.talon.orrery.localModelRungs
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/** What a provider offers: its models, and whether Jev is reachable through it. */
data class Catalog(val models: List<ModelInfo>, val jev: Boolean = false)

/** A provider's answer to the test button: whether it answered, how fast, and what it said. */
data class ProviderCheck(val ok: Boolean, val ms: Long, val detail: String)

/**
 * The models a provider offers, fetched when it is added, and the test
 * button. OpenRouter's model list says nothing of retention; its ZDR
 * endpoint list does, and names Jev, which the model list leaves out.
 */
class ModelCatalog(private val http: HttpClient = createAppHttpClient()) {

    suspend fun fetch(p: AiProvider): Catalog = when (p.kind) {
        ProviderKind.OpenRouter -> openRouterCatalog(get("$OPENROUTER/models"), get("$OPENROUTER/endpoints/zdr"))
        ProviderKind.Anthropic -> Catalog(anthropicModels(get("https://api.anthropic.com/v1/models?limit=1000") { anthropic(p) }))
        ProviderKind.OpenAi -> Catalog(openAiModels(get("https://api.openai.com/v1/models") { bearer(p) }))
        ProviderKind.OpenAiCompatible -> Catalog(openAiModels(get("${base(p)}/models") { bearer(p) }))
        // The ship says what the vendor sells, and has already written
        // it onto the row: there is no /models call to make.
        ProviderKind.Armillary -> Catalog(p.models)
        ProviderKind.ThisDevice -> Catalog(listOf(ModelInfo("", "This device's own model")))
    }

    /** Whether [p] answers with its key. OpenRouter lists models to anyone, so its key is asked about directly. */
    suspend fun check(p: AiProvider): ProviderCheck {
        val start = TimeSource.Monotonic.markNow()
        fun ms() = start.elapsedNow().inWholeMilliseconds
        return runCatching {
            when (p.kind) {
                ProviderKind.OpenRouter -> get("$OPENROUTER/key") { bearer(p) }
                // The ship is what answers, not the vendor: a read of
                // everything is the test, and it says what went wrong.
                ProviderKind.Armillary -> {
                    val repo = io.nisfeb.talon.armillary.ArmillaryRepo.attached()
                        ?: return ProviderCheck(false, ms(), "No ship is signed in.")
                    repo.refresh(fresh = true).onFailure { return ProviderCheck(false, ms(), it.message ?: "The ship did not answer.") }
                    return ProviderCheck(true, ms(), "Answers in ${ms()} ms.")
                }
                ProviderKind.ThisDevice -> {
                    val ready = localModelRungs().firstOrNull { it.status() == RungStatus.Ready }
                        ?: return ProviderCheck(false, ms(), "No model on this device is ready.")
                    return ProviderCheck(true, ms(), ready.name)
                }
                else -> fetch(p)
            }
            ProviderCheck(true, ms(), "Answers in ${ms()} ms.")
        }.getOrElse { ProviderCheck(false, ms(), it.message ?: "No answer.") }
    }

    private fun base(p: AiProvider) = p.baseUrl?.trim()?.trimEnd('/')?.removeSuffix("/chat/completions")
        ?.takeIf { it.isNotEmpty() } ?: error("No address for ${p.label}.")

    private fun HttpRequestBuilder.bearer(p: AiProvider) {
        if (p.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${p.apiKey}")
    }

    private fun HttpRequestBuilder.anthropic(p: AiProvider) {
        header("x-api-key", p.apiKey)
        header("anthropic-version", "2023-06-01")
    }

    private suspend fun get(url: String, block: HttpRequestBuilder.() -> Unit = {}): JsonElement {
        val resp = http.get(url) {
            block()
            timeout { requestTimeoutMillis = 15_000 }
        }
        val text = resp.bodyAsText()
        when (resp.status.value) {
            401, 403 -> error("The key was refused (${resp.status.value}).")
            in 400..599 -> error("Answered ${resp.status.value}: ${text.take(160)}")
        }
        return Json.parseToJsonElement(text)
    }

    companion object {
        const val OPENROUTER = "https://openrouter.ai/api/v1"
    }
}

private fun JsonElement.data() = (this as? JsonObject)?.get("data")?.jsonArray.orEmpty().map { it.jsonObject }
private fun JsonObject.str(k: String) = get(k)?.jsonPrimitive?.contentOrNull

/** OpenRouter's models, each marked ZDR when its ZDR endpoint list names it; Jev is on that list alone. */
internal fun openRouterCatalog(models: JsonElement, zdr: JsonElement): Catalog {
    val zdrIds = zdr.data().mapNotNull { it.str("model_id") }.toSet()
    val list = models.data().mapNotNull { m ->
        val id = m.str("id") ?: return@mapNotNull null
        ModelInfo(
            id = id,
            name = m.str("name") ?: id,
            zdr = id in zdrIds,
            tools = m["supported_parameters"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "tools" },
            contextLength = m["context_length"]?.jsonPrimitive?.intOrNull,
        )
    }
    return Catalog(list, jev = zdrIds.any { it.startsWith(JEV_PREFIX) })
}

internal const val JEV_PREFIX = "typesafe/jev"

/** Anthropic's models; every Claude model uses tools. */
internal fun anthropicModels(body: JsonElement): List<ModelInfo> = body.data().mapNotNull { m ->
    val id = m.str("id") ?: return@mapNotNull null
    ModelInfo(id, m.str("display_name") ?: id, tools = true, contextLength = m["max_input_tokens"]?.jsonPrimitive?.intOrNull)
}

/**
 * The OpenAI list shape, which OpenAI, LM Studio, Ollama and llama.cpp
 * all answer. What cannot chat or transcribe is left out; a speech
 * model is marked for the transcription row.
 */
internal fun openAiModels(body: JsonElement): List<ModelInfo> = body.data().mapNotNull { m ->
    val id = m.str("id") ?: return@mapNotNull null
    val low = id.lowercase()
    if (NOT_CHAT.any { it in low }) return@mapNotNull null
    ModelInfo(id, speech = "whisper" in low || "transcribe" in low)
}

private val NOT_CHAT = listOf("embed", "tts", "dall-e", "moderation", "image", "sora")
