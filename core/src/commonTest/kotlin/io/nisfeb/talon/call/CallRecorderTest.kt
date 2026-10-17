package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the shared recorder adds to [PartyLineRecordingTest]'s rules: mute
 * keeps our voice out, and a sink that outlives its recording appends
 * nothing. A 1:1 call has no party line around it to enforce either.
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

    @Test
    fun aSinkKeptPastStopAppendsNothing() {
        val rec = CallRecorder("test") { false }
        rec.start()
        val them = rec.sink("~bus")!!
        them(tone(480), 48_000)
        val call = rec.stop()
        them(tone(480), 48_000)
        assertEquals(960, call.clips.getValue("~bus").size)
        assertNull(rec.sink("~bus"), "no sink without a recording")
        assertFalse(rec.isRecording)
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
