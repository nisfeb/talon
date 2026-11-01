package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** How close the live map looks: the street for an exact fix, the neighbourhood for an area share. */
class LiveMapTest {
    @Test
    fun `an exact fix shows the street, a rough one the area around it`() {
        assertEquals(16.0, mapZoomFor(null))
        assertEquals(16.0, mapZoomFor(12.0))
        assertEquals(16.0, mapZoomFor(100.0))
        assertEquals(14.0, mapZoomFor(100.1))
        assertEquals(14.0, mapZoomFor(999.0))
        assertEquals(13.0, mapZoomFor(1000.0), "an area share is cut to a kilometre")
        assertEquals(600.0, mapSpanMetresFor(12.0))
        assertEquals(2_500.0, mapSpanMetresFor(500.0))
        assertEquals(5_000.0, mapSpanMetresFor(1000.0))
    }
}
