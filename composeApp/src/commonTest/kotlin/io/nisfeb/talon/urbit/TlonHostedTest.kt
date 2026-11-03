package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TlonHostedTest {
    @Test
    fun `a tlon network address is hosted, with a path or a port`() {
        assertTrue(isTlonHosted("https://sampel-palnet.tlon.network"))
        assertTrue(isTlonHosted("https://sampel-palnet.tlon.network/"))
        assertTrue(isTlonHosted("https://Sampel-Palnet.TLON.network:443/apps/groups"))
        assertTrue(isTlonHosted("https://zod.test.tlon.systems"))
    }

    @Test
    fun `anything else is the ship's own`() {
        assertFalse(isTlonHosted("https://my.ship"))
        assertFalse(isTlonHosted("https://urbit.example.com/jars"))
        assertFalse(isTlonHosted("https://eviltlon.network"))
        assertFalse(isTlonHosted("https://tlon.network.example.com"))
        assertFalse(isTlonHosted("http://localhost:8080"))
        assertFalse(isTlonHosted(null))
        assertFalse(isTlonHosted(""))
    }
}
