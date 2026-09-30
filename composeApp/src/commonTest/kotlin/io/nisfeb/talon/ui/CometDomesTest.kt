package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CometDomesTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun `a groundwire comet's dome names its registry`() =
        assertEquals("gw-btc", CometDomes.registryOf(hex("09f8ceee5ac4e8c6")))

    @Test fun `a comet jael has no record of is none`() =
        assertEquals("", CometDomes.registryOf(hex("02")))

    @Test fun `a body that is not a dome answer can't tell`() {
        assertNull(CometDomes.registryOf(hex("01")), "life's answer is not a jam of a dome")
        assertNull(CometDomes.registryOf(ByteArray(0)))
        // A page whose first byte happens to start like `~` is not `~`.
        assertNull(CometDomes.registryOf("null".encodeToByteArray()), "json null")
        assertNull(CometDomes.registryOf("<!doctype html>".encodeToByteArray()), "a redirected landing page")
    }

    @Test fun `cue follows a backref`() {
        val (h, t) = CometDomes.cue(hex("01e6f7e6d5c4b3a29193")) as Pair<*, *>
        val big = hex("efcdab8967452301")
        assertContentEquals(big, h as ByteArray)
        assertContentEquals(big, t as ByteArray)
    }
}
