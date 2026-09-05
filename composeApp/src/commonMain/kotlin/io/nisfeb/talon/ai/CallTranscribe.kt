package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cloud speech-to-text for a recorded party line, one speaker's audio
 * at a time.
 *
 * The SFU gives us one audio track per speaker, so each speaker's WAV
 * is transcribed on its own — that is where the speaker attribution
 * comes from, with no diarization model. The caller tags the returned
 * segments with the speaker and merges the speakers by time.
 *
 * OpenAI's `/v1/audio/transcriptions` shape (verbose_json), which Groq
 * and other OpenAI-compatible hosts also serve. The endpoint and key
 * are passed in rather than read from settings so this stays a pure
 * transport with a testable parser.
 *
 * ponytail: cloud, not on-device — the user chose it for cross-platform
 * reach and accepted that call audio leaves the ship (announced to the
 * room + opt-in at the UI). On-device Whisper is the upgrade path.
 */
object CallTranscribe {

    /** One transcribed span: milliseconds from the start of the clip,
     *  and the text. Speaker is filled in by the caller. */
    data class Segment(val startMs: Long, val text: String)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transcribe one WAV clip. Returns time-ordered segments.
     *
     * @param endpoint full URL, e.g. https://api.openai.com/v1/audio/transcriptions
     * @param model    e.g. "whisper-1"
     * @throws IllegalStateException on a non-2xx response.
     */
    suspend fun transcribe(
        http: HttpClient,
        endpoint: String,
        apiKey: String,
        model: String,
        wav: ByteArray,
    ): List<Segment> = withContext(ioDispatcher) {
        val res = http.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
                        append("response_format", "verbose_json")
                        append(
                            "file",
                            wav,
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"line.wav\"")
                            },
                        )
                    },
                ),
            )
        }
        if (!res.status.isSuccess()) {
            error("transcription failed: ${res.status.value} ${res.bodyAsText().take(200)}")
        }
        parse(res.bodyAsText())
    }

    /**
     * Parse a verbose_json body into segments. Falls back to a single
     * segment carrying the whole `text` when no per-segment timings are
     * present (some hosts omit them). Split out so it is unit-testable
     * without a network call.
     */
    fun parse(body: String): List<Segment> {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val segs = (obj["segments"])?.let { runCatching { it.jsonArray }.getOrNull() }
        if (segs != null && segs.isNotEmpty()) {
            return segs.mapNotNull { el ->
                val s = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                val text = s["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                val startSec = s["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                Segment((startSec * 1000).toLong(), text)
            }
        }
        val whole = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        return if (whole.isEmpty()) emptyList() else listOf(Segment(0, whole))
    }
}
