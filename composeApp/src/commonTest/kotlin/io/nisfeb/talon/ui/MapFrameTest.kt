package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** What the people map looks at, and when it moves (sneagan, 2026-11-10: "everyone on one full screen map"). */
class MapFrameTest {
    private val bus = MapPin("~bus", "Bus", 10.0, 20.0, 15.0)
    private val nec = MapPin("~nec", "Nec", -5.0, 40.0, null)

    @Test
    fun `nobody is no frame, and one person is looked at close up`() {
        assertEquals(null, mapFrame(emptyList(), null))
        assertEquals(MapFrame(10.0, 20.0, 10.0, 20.0, bus), mapFrame(listOf(bus), null))
    }

    @Test
    fun `everyone is framed by the box around them, and a tap looks at one`() {
        assertEquals(MapFrame(-5.0, 20.0, 10.0, 40.0, null), mapFrame(listOf(bus, nec), null))
        assertEquals(nec, mapFrame(listOf(bus, nec), "~nec")?.one)
        assertEquals(null, mapFrame(listOf(bus, nec), "~gone")?.one, "a focus on someone who stopped sharing shows everyone")
    }

    @Test
    fun `the map moves when who is framed changes, not when they move`() {
        val moved = bus.copy(lat = 11.0)
        assertEquals(mapFrame(listOf(bus, nec), null)!!.key(listOf(bus, nec)), mapFrame(listOf(moved, nec), null)!!.key(listOf(nec, moved)))
        assertNotEquals(mapFrame(listOf(bus, nec), null)!!.key(listOf(bus, nec)), mapFrame(listOf(bus, nec), "~bus")!!.key(listOf(bus, nec)))
        assertNotEquals(mapFrame(listOf(bus), null)!!.key(listOf(bus)), mapFrame(listOf(bus, nec), null)!!.key(listOf(bus, nec)))
    }

    @Test
    fun `the way to the list says when someone waits there`() {
        assertEquals("List", locationsListLabel(0))
        assertEquals("List (2 waiting)", locationsListLabel(2))
    }
}
