package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the AI section says, by what the platform has: no top up where
 * buying must not be mentioned (iOS outside the US storefront), and no
 * dictation where there is none (desktop and iOS).
 */
class AiSettingsCopyTest {
    @Test
    fun `the card names no top up where buying is not offered, and no dictation where there is none`() {
        assertTrue("top up" in armillaryIntro(canBuy = true))
        assertTrue("top up" !in armillaryIntro(canBuy = false), armillaryIntro(false))
        assertTrue("quick dictation" in assistantLine(quickDictation = true))
        assertTrue("dictation" !in assistantLine(quickDictation = false), assistantLine(false))
        assertEquals("Catch-up, the assistant, dictation and call transcripts", pitchFeatures(assistant = true, dictation = true, transcripts = true))
        assertEquals("Catch-up, the assistant and call transcripts", pitchFeatures(assistant = true, dictation = false, transcripts = true))
        assertEquals("Catch-up", pitchFeatures(assistant = false, dictation = false, transcripts = false))
    }
}
