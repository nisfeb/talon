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

    /**
     * Where call audio is heard: Armillary's transcription model, while
     * transcription is on. Null where the vendor offers none, and the
     * dialog says so.
     */
    fun hearingFrom(cfg: AiSettings.Config): Hearing? =
        cfg.profile().takeIf { it.isOn(AiFeature.Transcription) }?.armillaryHearing()

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
        hearing: Hearing,
        call: RecordedCall,
        nameFor: (String) -> String,
    ): Transcript {
        val utterances = mutableListOf<TranscriptGemtext.Utterance>()
        val failed = mutableListOf<String>()
        for ((ship, pcm) in call.clips) {
            if (pcm.isEmpty()) continue
            val label = nameFor(ship)
            // Not runCatching: a Stop cancels this, and a cancellation
            // counted as the speaker failing ended in "transcription
            // failed for every speaker" over the "Stopped." it asked for.
            try {
                transcribeClip(http, hearing, pcm, call.rateOf(ship))
                    .forEach { utterances += TranscriptGemtext.Utterance(label, it.startMs, it.text) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                failed += label
                Log.w(TAG, "could not transcribe $label", t)
            }
        }
        if (failed.isNotEmpty() && utterances.isEmpty()) {
            error("transcription failed for every speaker (${failed.joinToString(", ")})")
        }
        return Transcript(utterances.sortedBy { it.startMs }, failed)
    }

    /** A published transcript: where it lives, and the words, which stay on this device. */
    data class PublishedTranscript(val address: String, val utterances: List<TranscriptGemtext.Utterance>)

    /**
     * Transcribe every speaker and publish the merged transcript to
     * Lattice. Returns the canonical urb:// address of the new page
     * with the utterances, so the orrery triage can read them here
     * rather than fetch its own words back.
     */
    suspend fun publishTranscript(
        http: HttpClient,
        hearing: Hearing,
        shipUrl: String,
        ourShip: String,
        cookie: String,
        title: String,
        whenLabel: String,
        call: RecordedCall,
        nameFor: (String) -> String,
    ): PublishedTranscript {
        val t = transcribeAll(http, hearing, call, nameFor)
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
        return PublishedTranscript(LatticePublish.publish(http, shipUrl, ourShip, cookie, slug, gemtext), t.utterances)
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
     * Transcribe one speaker's PCM, a stretch of speech at a time
     * ([speechSpans]). Armillary's speech route answers the words and no
     * timestamps, so where each stretch starts in the clip is where its
     * words go in the call; whole chunks put minutes of one voice in a
     * single line. Downsampled first: Whisper works at 16 kHz.
     *
     * ponytail: one request per stretch, one after another. A long call
     * takes minutes; a few at a time is the upgrade if that is too slow.
     */
    private suspend fun transcribeClip(
        http: HttpClient,
        hearing: Hearing,
        pcm: ByteArray,
        rate: Int,
    ): List<CallTranscribe.Segment> {
        val small = PcmResample.to(pcm, rate, STT_RATE)
        return speechSpans(small, STT_RATE).mapNotNull { span ->
            val wav = WavFile.encode(small.copyOfRange(span.first, span.last + 1), STT_RATE)
            ArmillaryTranscribe.transcribe(http, hearing, wav, "wav").takeIf { it.isNotBlank() }
                ?.let { CallTranscribe.Segment(span.first.toLong() * 1000L / (STT_RATE * 2), it) }
        }
    }

    /**
     * Where [pcm] (16-bit mono at [rate]) has speech, as byte ranges: runs
     * of loud frames, joined across pauses shorter than [gapMs], padded by
     * [padMs] each side, and cut at [maxMs] so each stays well under the
     * vendor's 16 MB body. A run of less than a quarter second is a click.
     *
     * Loud is [overFloor] times the room, at least [minSpeechRms] and at
     * most [ceilingRms]. The room is the quietest tenth of the track, zeros
     * and all: a track that is digital silence between words has no hiss
     * to measure, and its quietest frame that is not zero was speech.
     *
     * ponytail: the dictation's energy gate ([EndOfSpeech]), not a voice
     * model. A track whose noise is over [ceilingRms] reads as one long
     * stretch cut at [maxMs], which is what was sent before. The
     * thresholds are the calibration knobs.
     */
    internal fun speechSpans(
        pcm: ByteArray,
        rate: Int,
        gapMs: Int = 800,
        padMs: Int = 200,
        maxMs: Int = 25_000,
        minSpeechRms: Double = 500.0,
        overFloor: Double = 3.0,
        ceilingRms: Double = 1_500.0,
    ): List<IntRange> {
        val frame = rate * 2 * FRAME_MS / 1000
        val n = pcm.size / frame
        if (n == 0) return emptyList()
        val loud = DoubleArray(n) { EndOfSpeech.rms(pcm, frame / 2, from = it * frame) }
        val room = loud.sorted()[n / 10]
        val threshold = (room * overFloor).coerceIn(minSpeechRms, ceilingRms)
        val speech = BooleanArray(n) { loud[it] >= threshold }
        val gap = gapMs / FRAME_MS
        val pad = padMs / FRAME_MS
        val max = maxMs / FRAME_MS
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < n) {
            if (!speech[i]) { i++; continue }
            var end = i
            var j = i + 1
            while (j < n && j - end <= gap) { if (speech[j]) end = j; j++ }
            if ((end - i + 1) * FRAME_MS >= MIN_SPEECH_MS) {
                var from = maxOf(0, i - pad)
                val to = minOf(n - 1, end + pad)
                while (from <= to) {
                    val stop = minOf(to, from + max - 1)
                    out += (from * frame) until ((stop + 1) * frame)
                    from = stop + 1
                }
            }
            i = end + 1
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

    /** What Whisper works at internally; sending more is wasted upload. */
    private const val STT_RATE = 16_000

    private const val FRAME_MS = 30
    private const val MIN_SPEECH_MS = 250
}
