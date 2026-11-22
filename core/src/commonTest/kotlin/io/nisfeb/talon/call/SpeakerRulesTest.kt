package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A phone's speaker button and its screen off at the ear, as rules. */
class SpeakerRulesTest {
    private val ear = AudioDevice("ear", "Earpiece", AudioRouteKind.Earpiece)
    private val speaker = AudioDevice("spk", "Speaker", AudioRouteKind.Speaker)
    private val wired = AudioDevice("wired", "Wired headset")
    private val buds = AudioDevice("bt", "Pixel Buds")

    @Test
    fun `a phone's earpiece and speaker make a toggle`() {
        assertEquals(SpeakerButton.Toggle(speaker, ear), speakerButton(listOf(ear, speaker)))
        // A wired headset takes the earpiece's place, as a dialer has it.
        assertEquals(SpeakerButton.Toggle(speaker, wired), speakerButton(listOf(wired, speaker)))
    }

    @Test
    fun `a headset in as well opens the list`() {
        assertEquals(SpeakerButton.Pick, speakerButton(listOf(ear, speaker, buds)))
    }

    @Test
    fun `no speaker, or nothing but the speaker, is no button`() {
        assertEquals(SpeakerButton.None, speakerButton(emptyList()))
        assertEquals(SpeakerButton.None, speakerButton(listOf(speaker)))
        // A computer's devices are all Other, whatever their names say.
        assertEquals(SpeakerButton.None, speakerButton(listOf(AudioDevice("a", "Speaker"), AudioDevice("b", "Headphones"))))
    }

    @Test
    fun `the screen goes off at the ear only on the earpiece of a call or a line`() {
        val active = CallUiState.Active(peer = "~bus", media = MediaState.Live, muted = false)
        assertTrue(proximityWanted(active, onLine = false, AudioRouteKind.Earpiece))
        assertTrue(proximityWanted(CallUiState.Outgoing("~bus"), onLine = false, AudioRouteKind.Earpiece), "ringing out, at the ear")
        assertTrue(proximityWanted(CallUiState.None, onLine = true, AudioRouteKind.Earpiece), "a line")
        assertFalse(proximityWanted(active, onLine = false, AudioRouteKind.Speaker))
        assertFalse(proximityWanted(active, onLine = true, AudioRouteKind.Other), "a headset")
        assertFalse(proximityWanted(CallUiState.Incoming("~bus"), onLine = false, AudioRouteKind.Earpiece), "ringing in: the screen answers it")
        assertFalse(proximityWanted(CallUiState.None, onLine = false, AudioRouteKind.Earpiece), "no call")
    }
}
