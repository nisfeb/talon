package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What the phones' own call surfaces are told; the wiring around it needs a phone. */
class NativeCallsTest {
    // The full-screen intent fires by itself over a locked screen: only
    // the Answer action may carry what answers.
    @Test
    fun onlyTheAnswerActionAnswers() {
        val show = RingIntents.extras(RingIntents.Tap.Show, "~zod", "c1")
        val answer = RingIntents.extras(RingIntents.Tap.Answer, "~zod", "c1")
        assertEquals(mapOf(RingIntents.RINGING_FROM to "~zod"), show)
        assertEquals(mapOf(RingIntents.ANSWER_FROM to "~zod", RingIntents.ANSWER_CALL_ID to "c1"), answer)
        assertEquals("answer_from", RingIntents.ANSWER_FROM, "what a notification posted by an older build carries")
        assertEquals("answer_call_id", RingIntents.ANSWER_CALL_ID)
        val codes = setOf(
            RingIntents.requestCode(RingIntents.Tap.Answer, "c1"),
            RingIntents.requestCode(RingIntents.Tap.Show, "c1"),
            "c1".hashCode() + 1, // the decline broadcast's
        )
        assertEquals(3, codes.size, "a shared code is one PendingIntent, with the last extras")
        assertNotEquals(RingIntents.requestCode(RingIntents.Tap.Show, "c1"), RingIntents.requestCode(RingIntents.Tap.Show, "c2"))
        assertTrue(RingIntents.isRing { it in show })
        assertTrue(RingIntents.isRing { it in answer })
        assertFalse(RingIntents.isRing { it == "open_whom" })
    }
}
