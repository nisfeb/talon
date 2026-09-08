package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.nisfeb.talon.call.PcmMix
import io.nisfeb.talon.call.PcmResample
import io.nisfeb.talon.call.RecordedCall
import io.nisfeb.talon.call.WavFile
import io.nisfeb.talon.urbit.LatticePublish
import io.nisfeb.talon.urbit.TranscriptGemtext
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.nowMs

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
    fun sttFrom(cfg: AiSettings.Config): Stt? {
        // A dedicated Whisper key wins, whatever the chat provider is —
        // this is how an Anthropic/OpenRouter user gets transcripts.
        cfg.sttApiKey.takeIf { it.isNotBlank() }?.let {
            return Stt(OPENAI_STT, it, WHISPER_MODEL)
        }
        return when (cfg.provider) {
            AiSettings.Provider.OpenAi ->
                cfg.apiKey.takeIf { it.isNotBlank() }?.let {
                    Stt(OPENAI_STT, it, WHISPER_MODEL)
                }
            AiSettings.Provider.Custom ->
                cfg.baseUrl?.takeIf { it.isNotBlank() && cfg.apiKey.isNotBlank() }?.let {
                    // The chat model is not a speech model — posting it
                    // here (what this used to do) fails on every host.
                    // And the saved base URL is often a full chat URL,
                    // which produced ".../chat/completions/audio/…".
                    Stt(audioEndpoint(it), cfg.apiKey, WHISPER_MODEL)
                }
            else -> null
        }
    }

    /** A saved base URL -> its /audio/transcriptions endpoint. Accepts
     *  a bare base, a `/v1`, or a full chat-completions URL. */
    internal fun audioEndpoint(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/completions", "/responses")) {
            if (b.endsWith(suffix)) b = b.dropLast(suffix.length)
        }
        return "${b.trimEnd('/')}/audio/transcriptions"
    }

    /**
     * Transcribe every speaker and publish the merged transcript to
     * Lattice. Returns the canonical urb:// address of the new page.
     * Sequential per speaker — a call has few speakers and this keeps
     * the code and the rate-limit behaviour simple.
     */
    /** The transcript of every speaker, merged on one timeline, plus who could not be transcribed. */
    data class Transcript(
        val utterances: List<TranscriptGemtext.Utterance>,
        val failed: List<String>,
    )

    /**
     * Transcribe every speaker's own track. Attribution is exact
     * because each request carries one voice; the segments then merge
     * by their start time. Sequential per speaker — a call has few
     * speakers and this keeps the rate-limit behaviour simple.
     */
    suspend fun transcribeAll(
        http: HttpClient,
        stt: Stt,
        call: RecordedCall,
        nameFor: (String) -> String,
    ): Transcript {
        val utterances = mutableListOf<TranscriptGemtext.Utterance>()
        val failed = mutableListOf<String>()
        for ((ship, pcm) in call.clips) {
            if (pcm.isEmpty()) continue
            val label = nameFor(ship)
            runCatching { transcribeClip(http, stt, pcm, call.rateOf(ship)) }
                .onSuccess { segs ->
                    segs.forEach { utterances += TranscriptGemtext.Utterance(label, it.startMs, it.text) }
                }
                .onFailure {
                    failed += label
                    Log.w(TAG, "could not transcribe $label", it)
                }
        }
        if (failed.isNotEmpty() && utterances.isEmpty()) {
            error("transcription failed for every speaker (${failed.joinToString(", ")})")
        }
        return Transcript(utterances.sortedBy { it.startMs }, failed)
    }

    /**
     * Transcribe every speaker and publish the merged transcript to
     * Lattice. Returns the canonical urb:// address of the new page.
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
        val t = transcribeAll(http, stt, call, nameFor)
        val gemtext = TranscriptGemtext.build(
            title = title.ifBlank { "Party line" },
            whenLabel = whenLabel,
            participants = call.clips.keys.map(nameFor),
            utterances = t.utterances,
            note = if (t.failed.isEmpty()) "" else
                "Could not transcribe: ${t.failed.joinToString(", ")}.",
        )
        val slug = LatticePublish.slug(
            title.ifBlank { "party-line" },
            "$ourShip-$whenLabel-${nowMs()}",
        )
        return LatticePublish.publish(http, shipUrl, ourShip, cookie, slug, gemtext)
    }

    /**
     * The transcript as a plain text file: one line per utterance,
     * "[mm:ss] Speaker: words", which is what a summarising model or
     * a person wants to read. Speaker labels come from our own tracks,
     * never from guessing voices apart.
     */
    fun transcriptText(title: String, whenLabel: String, participants: List<String>, t: Transcript): String {
        val sb = StringBuilder()
        sb.append(title.ifBlank { "Party line" }).append(" · ").append(whenLabel).append('\n')
        sb.append("Speakers: ").append(participants.joinToString(", ")).append('\n')
        if (t.failed.isNotEmpty()) sb.append("Could not transcribe: ").append(t.failed.joinToString(", ")).append('\n')
        sb.append('\n')
        for (u in t.utterances) {
            sb.append('[').append(TranscriptGemtext.clock(u.startMs)).append("] ").append(u.speaker).append(": ").append(u.text.trim()).append('\n')
        }
        return sb.toString()
    }

    /**
     * Transcribe one speaker's PCM, downsampled and split to fit the
     * upload limit.
     *
     * Whisper resamples to 16 kHz internally, so sending 48 kHz only
     * burns upload budget — at 48 kHz a clip passed OpenAI's 25 MB cap
     * after about 4.5 minutes and failed the whole publish. Anything
     * still too long after downsampling is sent in chunks, with each
     * chunk's segment times shifted back into the clip's own timeline.
     */
    private suspend fun transcribeClip(
        http: HttpClient,
        stt: Stt,
        pcm: ByteArray,
        rate: Int,
    ): List<CallTranscribe.Segment> {
        val small = PcmResample.to(pcm, rate, STT_RATE)
        val out = mutableListOf<CallTranscribe.Segment>()
        val bytesPerSec = STT_RATE * 2
        var at = 0
        while (at < small.size) {
            val end = minOf(at + MAX_CHUNK_BYTES, small.size)
            val slice = small.copyOfRange(at, end)
            val offsetMs = (at.toLong() * 1000L) / bytesPerSec
            val wav = WavFile.encode(slice, STT_RATE)
            CallTranscribe.transcribe(http, stt.endpoint, stt.key, stt.model, wav)
                .forEach { out += CallTranscribe.Segment(it.startMs + offsetMs, it.text) }
            at = end
        }
        return out
    }

    /** Speakers in the order the multitrack file's channels use, and the per-speaker files are named. */
    fun speakerOrder(call: RecordedCall): List<String> = call.clips.keys.sorted()

    /**
     * One WAV with a channel per speaker, in [speakerOrder], all on the
     * same clock. Transcription services with a multichannel mode
     * (Deepgram, AssemblyAI, Rev) attribute by channel; anything else
     * plays it as a mix. Null if nothing was captured.
     */
    fun multitrackWav(call: RecordedCall): ByteArray? {
        if (call.isEmpty) return null
        val tracks = speakerOrder(call).map { ship ->
            PcmResample.to(call.clips.getValue(ship), call.rateOf(ship), call.sampleRate)
        }
        val frames = io.nisfeb.talon.call.PcmTracks.interleave(tracks)
        if (frames.isEmpty()) return null
        return WavFile.encode(frames, call.sampleRate, channels = tracks.size)
    }

    /** One mono WAV per speaker, keyed by ship, each starting at the recording's t=0. */
    fun perSpeakerWavs(call: RecordedCall): Map<String, ByteArray> =
        speakerOrder(call).filter { call.clips.getValue(it).isNotEmpty() }
            .associateWith { ship -> WavFile.encode(call.clips.getValue(ship), call.rateOf(ship)) }

    /** Mix every speaker into one WAV for the "keep the full recording"
     *  option. Empty if nothing was captured. */
    fun fullRecordingWav(call: RecordedCall): ByteArray? {
        if (call.isEmpty) return null
        // Clips can legitimately differ in rate (a 44.1 kHz mic beside
        // 48 kHz remotes); mixing by sample index without this plays
        // whoever was slower back at the wrong speed.
        val aligned = call.clips.map { (ship, pcm) ->
            PcmResample.to(pcm, call.rateOf(ship), call.sampleRate)
        }
        val mixed = PcmMix.mix(aligned)
        if (mixed.isEmpty()) return null
        return WavFile.encode(mixed, call.sampleRate)
    }

    private const val TAG = "CallRecording"
    private const val OPENAI_STT = "https://api.openai.com/v1/audio/transcriptions"
    private const val WHISPER_MODEL = "whisper-1"

    /** What Whisper works at internally; sending more is wasted upload. */
    private const val STT_RATE = 16_000

    /** Headroom under OpenAI's 25 MB multipart cap. At 16 kHz mono this
     *  is a little over 10 minutes of audio per request. */
    private const val MAX_CHUNK_BYTES = 20 * 1024 * 1024
}
