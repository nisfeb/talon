package io.nisfeb.talon.call

import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * One recording's per-speaker capture, for a party line and a 1:1 call
 * alike. A caller registers each speaker's [sink] on wherever that
 * speaker's audio arrives, and [stop] hands back every speaker's PCM on
 * a shared t=0.
 *
 * Structural writes (adding a speaker) happen on the app thread in
 * [sink]; the WebRTC audio thread only touches an already-registered
 * [Clip], never the map itself, so no lock is needed. Reads at [stop]
 * are fenced by [Clip.open].
 *
 * [selfMuted] is read on every frame of our own clip: mute is the app's
 * one hard privacy control, and every platform taps outside the send
 * path (Android's sink sits pre-encode, desktop's is a separate capture
 * entirely), so without it a muted aside still reaches the WAV, Whisper
 * and the published transcript.
 */
class CallRecorder(
    private val tag: String,
    /** The clock the timeline is measured on; a test's own. */
    private val now: () -> Long = ::nowMs,
    private val selfMuted: () -> Boolean,
) {
    /**
     * Whether we are capturing right now. A StateFlow, and owned here,
     * because the screen must not be able to disagree with the tap: the
     * recording controls used to live in the chat slot, so opening a DM
     * cleared the room-wide badge while the taps kept running.
     */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    val isRecording: Boolean get() = _recording.value

    /**
     * The finished recording. Published rather than only returned so a
     * recording finalized by a teardown (leaving the line, the call
     * ending) still reaches the UI instead of being lost with the
     * control that would have stopped it.
     */
    private val _last = MutableStateFlow<RecordedCall?>(null)
    val last: StateFlow<RecordedCall?> = _last.asStateFlow()

    /** Drop the finished recording once the UI has dealt with it. */
    fun clearLast() { _last.value = null }

    private var startMs = 0L
    private var rate = 48_000
    private val clips = mutableMapOf<String, Clip>()

    /**
     * One speaker's capture buffer. The audio thread appends to [chunks]
     * and stamps [rate]/[frames]; it never touches the enclosing map.
     * [open] is the fence stop() closes before it reads.
     */
    private class Clip {
        val chunks = mutableListOf<ByteArray>()
        @Volatile var rate = 0
        /** Real captured frames, as opposed to the silence we padded in,
         *  muted frames among it. Zero means this speaker never spoke
         *  while we recorded, and the clip is pure padding — not worth a
         *  Whisper upload. */
        @Volatile var frames = 0
        @Volatile var open = true
    }

    /** Begin a recording. False when one is already running. */
    fun start(): Boolean {
        if (_recording.value) return false
        startMs = now()
        clips.clear()
        _recording.value = true
        return true
    }

    /**
     * The sink for [ship]'s audio, to register where it arrives; null
     * when nothing is recording.
     *
     * Re-entrant on purpose: an up-link republish or a ship rejoining on
     * a new stream re-taps a speaker we already hold audio for, so the
     * existing clip is kept and only the gap is padded. Replacing it
     * silently threw away everything captured before a mid-recording
     * network blip.
     */
    fun sink(ship: String, isSelf: Boolean = false): ((pcm: ByteArray, sampleRate: Int) -> Unit)? {
        if (!_recording.value) return null
        val clip = clips.getOrPut(ship) { Clip() }
        clip.open = true
        // Only a clip that already holds audio is padded here: it is
        // anchored to the timeline, so the gap since its last frame is
        // real. A speaker we have nothing from yet is padded on their
        // first frame instead (below): a party line taps a link from its
        // offer, before ICE connects, so measuring the lead here placed
        // a mid-line joiner 0.5-3s early in both the mixdown and the
        // transcript.
        if (clip.chunks.isNotEmpty()) {
            val r = if (clip.rate > 0) clip.rate else rate
            val elapsedMs = (now() - startMs).coerceAtLeast(0)
            val wantBytes = ((r.toLong() * elapsedMs / 1000L).toInt()) * 2
            pad(clip.chunks, wantBytes - clip.chunks.sumOf { it.size })
        }
        return { pcm, r ->
            // A sink registered just as stop() ran must not append into
            // a clip nobody will read again.
            if (clip.open && _recording.value) {
                // Muted, our frame is kept as silence of its length, and
                // not counted as speech. Dropped, everything said after
                // the mute ran early in the recording by the mute's length.
                val silent = isSelf && selfMuted()
                clip.rate = r
                if (!silent) clip.frames++
                if (r > rate) rate = r
                if (clip.chunks.isEmpty()) {
                    // The lead silence, measured where the speaker's audio
                    // actually starts and at this link's own rate.
                    val elapsedMs = (now() - startMs).coerceAtLeast(0)
                    pad(clip.chunks, ((r.toLong() * elapsedMs / 1000L).toInt()) * 2)
                }
                clip.chunks.add(if (silent) ByteArray(pcm.size) else pcm)
            }
        }
    }

    /** Append [bytes] of silence as bounded chunks. One contiguous array
     *  is a ~170 MB allocation for a speaker joining 30 minutes in, on
     *  the audio pump; these cost the same in total and never spike. */
    private fun pad(chunks: MutableList<ByteArray>, bytes: Int) {
        var left = bytes
        while (left > 0) {
            val n = if (left < PAD_CHUNK_BYTES) left else PAD_CHUNK_BYTES
            chunks.add(ByteArray(n))
            left -= n
        }
    }

    /**
     * Stop and return the per-speaker audio, published to [last]. The
     * caller removes its native sinks first, so no callback races the
     * read.
     */
    fun stop(): RecordedCall {
        if (!_recording.value) return RecordedCall(emptyMap(), rate)
        _recording.value = false
        // Close every clip before reading. Dropping the sinks is not
        // quite enough on its own: a frame already inside the callback
        // would otherwise append while we concatenate.
        clips.values.forEach { it.open = false }
        val out = mutableMapOf<String, ByteArray>()
        val rates = mutableMapOf<String, Int>()
        val entries = clips.entries.iterator()
        while (entries.hasNext()) {
            val (ship, clip) = entries.next()
            // Free each speaker's chunks as we go. Building the whole
            // result while still holding every chunk doubled peak heap,
            // which is what turned a long Android recording into an OOM
            // on the Stop tap that was meant to save it.
            if (clip.frames > 0) {
                val total = clip.chunks.sumOf { it.size }
                val bytes = ByteArray(total)
                var at = 0
                for (c in clip.chunks) { c.copyInto(bytes, at); at += c.size }
                out[ship] = bytes
                rates[ship] = if (clip.rate > 0) clip.rate else rate
            }
            clip.chunks.clear()
            entries.remove()
        }
        val mixRate = rates.values.maxOrNull() ?: rate
        Log.i(
            tag,
            "recording stop: mixRate=$mixRate " +
                out.entries.joinToString(", ") { "${it.key}=${it.value.size}B@${rates[it.key]}" }.ifEmpty { "(no clips)" },
        )
        val call = RecordedCall(out, mixRate, rates)
        _last.value = call
        return call
    }

    /** Forget any speakers left over, as a teardown after [stop] does. */
    fun reset() {
        clips.clear()
        startMs = 0L
    }

    private companion object {
        const val PAD_CHUNK_BYTES = 64 * 1024
    }
}
