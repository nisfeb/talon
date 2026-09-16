package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeaturedVideoTest {
    private val cams = listOf("~me", "~bud", "~sam")

    @Test
    fun `pinned beats speaker beats first other beats self`() {
        assertEquals("~sam", featuredVideo(cams, "~me", focusedShip = "~sam", lastSpeaker = "~bud"))
        assertEquals("~bud", featuredVideo(cams, "~me", focusedShip = null, lastSpeaker = "~bud"))
        assertEquals("~bud", featuredVideo(cams, "~me", focusedShip = null, lastSpeaker = null))
        assertEquals("~me", featuredVideo(listOf("~me"), "~me", null, null), "alone, our own camera")
    }

    @Test
    fun `a pin or a speaker whose camera is off does not count`() {
        assertEquals("~bud", featuredVideo(cams, "~me", focusedShip = "~gone", lastSpeaker = "~off"))
        assertNull(featuredVideo(emptyList(), "~me", "~sam", "~bud"))
    }
}
