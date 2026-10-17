package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the shared recorder adds to [PartyLineRecordingTest]'s rules: mute
 * keeps our voice out, and no sink is handed out once it has stopped. A
 * 1:1 call has no party line around it to enforce either.
 */
class CallRecorderTest {
    private fun tone(samples: Int) = ByteArray(samples * 2) { if (it % 2 == 0) 0x10 else 0x01 }

    @Test
    fun mutedFramesOfOurOwnVoiceAreNotKeptButTheirsAre() {
        var muted = true
        val rec = CallRecorder("test") { muted }
        assertTrue(rec.start())
        val us = rec.sink("~zod", isSelf = true)!!
        val them = rec.sink("~bus")!!
        us(tone(480), 48_000)
        them(tone(480), 48_000)
        muted = false
        val call = rec.stop()
        assertEquals(setOf("~bus"), call.clips.keys, "a muted aside reaches no file")
    }

    // Dropped, a muted frame took its time out of our track, and what we
    // said after the mute ran early against everyone else's.
    @Test
    fun aMuteMidRecordingIsKeptAsSilenceOfItsLength() {
        var muted = false
        // A clock of its own: a millisecond between start and the first
        // frame is lead silence, and would be counted here.
        val rec = CallRecorder("test", now = { 1_000L }) { muted }
        rec.start()
        val us = rec.sink("~zod", isSelf = true)!!
        us(tone(480), 48_000)
        muted = true
        us(tone(480), 48_000)
        muted = false
        us(tone(480), 48_000)
        val clip = rec.stop().clips.getValue("~zod")
        assertEquals(3 * 960, clip.size, "the muted frame keeps its place")
        assertTrue(clip.copyOfRange(960, 1920).all { it == 0.toByte() }, "and none of its sound")
        assertTrue(clip[0] != 0.toByte() && clip[1920] != 0.toByte())
    }

    @Test
    fun afterStopNoSinkIsHandedOutAndTheRecordingIsKept() {
        val rec = CallRecorder("test") { false }
        rec.start()
        val them = rec.sink("~bus")!!
        them(tone(480), 48_000)
        val call = rec.stop()
        assertTrue(call.clips.getValue("~bus").size >= 960, "their frame, after any lead silence")
        assertNull(rec.sink("~bus"), "no sink without a recording")
        assertFalse(rec.isRecording)
        assertEquals(call, rec.last.value)
    }

    // The shared t=0 is what orders the merged transcript: a speaker's
    // lead silence runs to their first frame, and a re-tap pads only the
    // gap since, each at that speaker's own rate.
    @Test
    fun leadSilenceRunsToTheFirstFrameAtTheSpeakersOwnRate() {
        var t = 1_000L
        val rec = CallRecorder("test", now = { t }) { false }
        rec.start()
        val us = rec.sink("~zod", isSelf = true)!!
        val them = rec.sink("~bus")!!
        us(tone(480), 48_000)
        t = 1_500L
        them(tone(441), 44_100)
        val call = rec.stop()
        assertEquals(960, call.clips.getValue("~zod").size, "no lead: we spoke at once")
        val bus = call.clips.getValue("~bus")
        assertEquals(44_100 + 882, bus.size, "half a second at 44.1 kHz, then the frame")
        assertTrue(bus.copyOfRange(0, 44_100).all { it == 0.toByte() } && bus[44_100] != 0.toByte())
        assertEquals(48_000, call.sampleRate, "the mix runs at the highest rate")
        assertEquals(44_100, call.rateOf("~bus"))
    }

    @Test
    fun aReTappedSpeakerIsPaddedForTheGapOnly() {
        var t = 5_000L
        val rec = CallRecorder("test", now = { t }) { false }
        rec.start()
        rec.sink("~bus")!!(tone(441), 44_100)
        t = 6_000L
        val again = rec.sink("~bus")!!
        again(tone(441), 44_100)
        val bus = rec.stop().clips.getValue("~bus")
        assertEquals(88_200 + 882, bus.size, "one second at their rate, less what was kept, then the new frame")
    }

    @Test
    fun aStopWithNothingRecordingPublishesNothing() {
        val rec = CallRecorder("test") { false }
        assertTrue(rec.stop().isEmpty)
        assertNull(rec.last.value)
        rec.start()
        rec.stop()
        assertTrue(rec.last.value!!.isEmpty, "a recording with no voices is still finished, and said so")
        rec.clearLast()
        assertNull(rec.last.value)
    }
}
