package io.nisfeb.talon.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

class PulseParseTest {
    @Test
    fun streamsResolveTheirDeviceNames() {
        val sinks = Pulse.parseDevices(
            """[{"index":1,"name":"alsa_output.pci","description":"Speakers"},
                {"index":7,"name":"TalonBridgeSpace","description":"TalonBridgeSpace"}]""",
        )
        val streams = Pulse.parseStreams(
            """[{"index":42,"sink":7,"properties":{"application.name":"Brave","application.process.id":"123"}},
                {"index":43,"sink":9,"properties":{}}]""",
            "sink",
            sinks,
        )
        assertEquals(
            listOf(
                Pulse.Stream(42, "Brave", 123L, "TalonBridgeSpace"),
                Pulse.Stream(43, "?", null, "9"),
            ),
            streams,
        )
        assertEquals(false, Pulse.hasDevices(sinks))
    }
}
