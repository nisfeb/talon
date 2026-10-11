package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which ships still hold what an earlier build gave them (2026-10-11). */
class CallDefaultsTest {
    private val d = CallDefaults(
        iceSpec = "stun:sfu.new.test:3479||;turn:sfu.new.test:3479|talon|p",
        sfuBase = "https://sfu.new.test",
        retiredSfuBase = "http://10.9.8.7:8444",
    )

    private fun ice(vararg url: String) = url.map { IceServer(it, "talon", "p") }

    @Test
    fun hostsComeOutOfEveryShape() {
        assertEquals("10.9.8.7", CallDefaults.host("http://10.9.8.7:8444"))
        assertEquals("sfu.new.test", CallDefaults.host("https://sfu.new.test"))
        assertEquals("sfu.new.test", CallDefaults.host("https://sfu.new.test/group/x/"))
        assertEquals("sfu.new.test", CallDefaults.host("turn:sfu.new.test:3479?transport=tcp"))
        assertEquals("h", CallDefaults.host("stun:h"))
        assertEquals("", CallDefaults.host(""))
    }

    @Test
    fun onlyTheExactRetiredSidecarIsRetired() {
        assertTrue(d.retires("http://10.9.8.7:8444"))
        assertFalse(d.retires("http://10.9.8.7:8443"), "a sidecar of the same look elsewhere is someone's own")
        assertFalse(d.retires("https://sfu.new.test"))
        assertFalse(d.retires(""))
        assertFalse(CallDefaults(sfuBase = "https://sfu.new.test").retires(""), "a build that retires nothing")
    }

    @Test
    fun iceOnOurOldOrNewHostMovesAndAnyOtherStays() {
        assertTrue(d.retiresIce(ice("stun:10.9.8.7:3478", "turn:10.9.8.7:3478")), "on the old host")
        assertTrue(d.retiresIce(ice("turn:sfu.new.test:3478")), "on the new host, the wrong port")
        assertFalse(d.retiresIce(TrunkWire.defaultIce(d)), "already what this build ships")
        assertFalse(d.retiresIce(ice("turn:10.9.8.7:3478", "turn:mine.example:3478")), "one of the user's own")
        assertFalse(d.retiresIce(emptyList()))
        assertFalse(d.copy(iceSpec = "").retiresIce(ice("turn:10.9.8.7:3478")), "nothing to move it to")
        assertFalse(CallDefaults().retiresIce(ice("turn:10.9.8.7:3478")))
    }
}
