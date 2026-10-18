package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Speech to text bought through Armillary, for an owner with no
 * transcription key of their own: the vendor names a transcription model
 * ([AiProfile.armillaryHearing]) and the audio goes where its chat goes.
 * One request for both modes: under a lease the base is OpenRouter's and
 * the key the lease's, through the proxy it is the vendor's
 * /apps/armillary/v1 and the armillary key. Contract:
 * lattice /project/talon/armillary-transcription-contract (armillary 31).
 */
object ArmillaryTranscribe {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * How long a dictation's or an Archie turn's words may take: the owner
     * is waiting on them, and eleven minutes was no answer at all. A call
     * recording keeps [ARMILLARY_WAIT_MS], since nobody waits on it.
     */
    const val SPOKEN_WAIT_MS = 3 * 60_000L

    /** The request body: the audio as base64, kept to endpoints that keep nothing. */
    @OptIn(ExperimentalEncodingApi::class)
    fun body(model: String, audio: ByteArray, format: String, language: String?): JsonObject = buildJsonObject {
        put("model", model)
        put("input_audio", buildJsonObject {
            put("data", Base64.encode(audio))
            put("format", format)
        })
        language?.takeIf { it.length == 2 }?.let { put("language", it) }
        // Zero data retention: OpenRouter routes the audio only to a
        // provider that keeps none (whisper-large-v3-turbo has two).
        put("provider", buildJsonObject { put("zdr", true) })
    }

    /**
     * [audio] ([format]: "m4a", "wav", ...) as text. Throws
     * [ModelHttpError] with the status for a refusal, so the line said
     * about it is in words ([DictationRun.why]); an empty balance in the
     * words every other Armillary feature uses. [timeoutMs] by default
     * covers the proxy's ten minutes on an upstream (armillary 33) and a
     * self-vendor's speech server, decoded on the ship first at about
     * 11 s per 3.6 MB; one the owner waits on passes [SPOKEN_WAIT_MS].
     */
    suspend fun transcribe(
        http: HttpClient,
        hearing: Hearing,
        audio: ByteArray,
        format: String,
        language: String? = null,
        timeoutMs: Long = ARMILLARY_WAIT_MS,
    ): String = withContext(ioDispatcher) {
        val url = hearing.baseUrl.trim().trimEnd('/') + "/audio/transcriptions"
        val res = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer ${hearing.key}")
            contentType(ContentType.Application.Json)
            setBody(body(hearing.model, audio, format, language).toString())
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
        val text = res.bodyAsText()
        val host = Url(url).host
        if (!res.status.isSuccess()) {
            val msg = runCatching {
                val o = json.parseToJsonElement(text).jsonObject
                (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: o["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: text.take(200)
            val status = res.status.value
            throw ModelHttpError(status, if (status == 402) outOfCredit(host, msg) else "$host $status: $msg")
        }
        runCatching { json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.content }.getOrNull()?.trim()
            ?: error("$host answered without text")
    }
}

/** Where speech is turned into text: the vendor's transcription model, on the row's base and key ([DictationRun.hearing]). */
data class Hearing(val baseUrl: String, val key: String, val model: String)

/**
 * The transcription the Armillary vendor offers this owner, or null: a
 * model named under "transcription" and a row that can reach it.
 */
fun AiProfile.armillaryHearing(): Hearing? {
    val row = armillary() ?: return null
    val model = row.suggested[TRANSCRIPTION]?.takeIf { it.isNotBlank() } ?: return null
    val base = row.baseUrl?.takeIf { it.isNotBlank() } ?: return null
    if (row.apiKey.isBlank()) return null
    return Hearing(base, row.apiKey, model)
}

/** The vendor's name for its speech-to-text tier, under suggested.models. */
const val TRANSCRIPTION = "transcription"

/**
 * The chat models of the vendor's list: never its transcription model,
 * nor anything that only takes speech, though the vendor leaves them out
 * of inference.models itself. A speech model offered as a chat model
 * would fail every message sent to it.
 */
fun chatModelsOf(models: List<String>, suggested: Map<String, String>): List<String> {
    val speech = suggested[TRANSCRIPTION]
    return models.filterNot { id ->
        val low = id.lowercase()
        id == speech || "whisper" in low || "transcribe" in low
    }
}
