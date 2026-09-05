package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.nisfeb.talon.call.PcmMix
import io.nisfeb.talon.call.RecordedCall
import io.nisfeb.talon.call.WavFile
import io.nisfeb.talon.urbit.LatticePublish
import io.nisfeb.talon.urbit.TranscriptGemtext

/**
 * Turn a [RecordedCall] into a Lattice transcript and/or a mixed-down
 * audio file. Ties together the transcription core: each speaker's WAV
 * is transcribed on its own (attribution for free), the segments merge
 * into one timeline, and the gemtext is published to the user's ship.
 */
object CallRecordingPublisher {

    /** A ready-to-use speech-to-text endpoint. */
    data class Stt(val endpoint: String, val key: String, val model: String)

    /**
     * Where to send audio for transcription, derived from the user's AI
     * settings. Only OpenAI-compatible providers expose an audio
     * endpoint, so Anthropic/OpenRouter return null and the UI explains
     * that a Whisper-capable key is needed.
     */
    fun sttFrom(cfg: AiSettings.Config): Stt? = when (cfg.provider) {
        AiSettings.Provider.OpenAi ->
            cfg.apiKey.takeIf { it.isNotBlank() }?.let {
                Stt("https://api.openai.com/v1/audio/transcriptions", it, "whisper-1")
            }
        AiSettings.Provider.Custom ->
            cfg.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() && cfg.apiKey.isNotBlank() }?.let {
                Stt("$it/audio/transcriptions", cfg.apiKey, cfg.model ?: "whisper-1")
            }
        else -> null
    }

    /**
     * Transcribe every speaker and publish the merged transcript to
     * Lattice. Returns the canonical urb:// address of the new page.
     * Sequential per speaker — a call has few speakers and this keeps
     * the code and the rate-limit behaviour simple.
     */
    suspend fun publishTranscript(
        http: HttpClient,
        stt: Stt,
        shipUrl: String,
        ourShip: String,
        cookie: String,
        title: String,
        whenLabel: String,
        call: RecordedCall,
        nameFor: (String) -> String,
    ): String {
        val utterances = mutableListOf<TranscriptGemtext.Utterance>()
        for ((ship, pcm) in call.clips) {
            if (pcm.isEmpty()) continue
            val wav = WavFile.encode(pcm, call.sampleRate)
            val label = nameFor(ship)
            CallTranscribe.transcribe(http, stt.endpoint, stt.key, stt.model, wav)
                .forEach { utterances += TranscriptGemtext.Utterance(label, it.startMs, it.text) }
        }
        val gemtext = TranscriptGemtext.build(
            title = title.ifBlank { "Party line" },
            whenLabel = whenLabel,
            participants = call.clips.keys.map(nameFor),
            utterances = utterances,
        )
        val slug = LatticePublish.slug(title.ifBlank { "party-line" }, "$ourShip-$whenLabel")
        return LatticePublish.publish(http, shipUrl, ourShip, cookie, slug, gemtext)
    }

    /** Mix every speaker into one WAV for the "keep the full recording"
     *  option. Empty if nothing was captured. */
    fun fullRecordingWav(call: RecordedCall): ByteArray? {
        if (call.isEmpty) return null
        val mixed = PcmMix.mix(call.clips.values.toList())
        if (mixed.isEmpty()) return null
        return WavFile.encode(mixed, call.sampleRate)
    }
}
