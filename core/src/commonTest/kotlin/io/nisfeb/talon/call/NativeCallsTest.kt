package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

    // B3-1: MainActivity is exported, and any app could have it place a
    // call or answer a ring. The call extras count only through the alias.
    @Test
    fun callExtrasCountOnlyThroughTheNotificationAlias() {
        assertTrue(RingIntents.trusted("io.nisfeb.talon.NotificationTap"))
        assertEquals("io.nisfeb.talon.NotificationTap", RingIntents.TAP_ALIAS, "the manifest declares this name")
        assertFalse(RingIntents.trusted("io.nisfeb.talon.MainActivity"), "the launcher's, exported")
        assertFalse(RingIntents.trusted(null), "an intent with no component")
        assertFalse(RingIntents.trusted("io.nisfeb.talon.HealthPermissionsRationale"), "another exported alias")
        assertFalse(RingIntents.trusted("NotificationTap"))
    }

    // B3-5: only the rung ship's controller hears the ring.
    @Test
    fun aRingForAnotherSignedInShipGoesThereFirst() {
        val ships = listOf("~zod", "~bus")
        assertEquals("~bus", RingIntents.shipToSwitch("~bus", active = "~zod", ships = ships))
        assertNull(RingIntents.shipToSwitch("~zod", active = "~zod", ships = ships), "already there")
        assertNull(RingIntents.shipToSwitch("~nec", active = "~zod", ships = ships), "not signed in here")
        assertNull(RingIntents.shipToSwitch(null, active = "~zod", ships = ships), "an older ring names no ship")
        assertEquals("~bus", RingIntents.shipToSwitch("~bus", active = null, ships = ships), "from the login screen")
    }

    @Test
    fun aRingAnsweredOnAnotherDeviceIsFiledSo() {
        val none = CallUiState.None
        val ended = { why: String -> CallUiState.Ended("~zod", why) }
        assertEquals(CallEnd.AnsweredElsewhere, callEnd(incoming = true, answered = false, end = none))
        assertEquals(CallEnd.Missed, callEnd(incoming = true, answered = false, end = ended("missed")))
        assertEquals(CallEnd.Missed, callEnd(incoming = true, answered = false, end = ended("ended")))
        assertEquals(CallEnd.Rejected, callEnd(incoming = true, answered = false, end = ended("declined")))
        assertEquals(CallEnd.Remote, callEnd(incoming = true, answered = true, end = ended("ended")))
        assertEquals(CallEnd.Remote, callEnd(incoming = false, answered = false, end = ended("no answer")))
        assertEquals(CallEnd.Local, callEnd(incoming = false, answered = true, end = none))
    }

    @Test
    fun glareEndsTheOutgoingCallItReplaced() {
        assertEquals("ours", abandonedOutgoing(reported = "ours", outgoing = true, ringing = "theirs"))
        assertNull(abandonedOutgoing(reported = "ours", outgoing = true, ringing = "ours"), "the same call")
        assertNull(abandonedOutgoing(reported = "theirs", outgoing = false, ringing = "theirs"), "a ring reported from the push")
        assertNull(abandonedOutgoing(reported = null, outgoing = true, ringing = "theirs"))
    }
}
