package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What someone pastes when they are asked for their public key. */
class ShipKeyBlockTest {
    @Test
    fun `the block names the ship and both keys`() {
        val keys = AzimuthRpc.Keys(auth = "0x" + "ab".repeat(32), crypt = "0x" + "cd".repeat(32), suite = 1)
        assertEquals(
            """
            ~sampel-palnet
            signing: 0x${"ab".repeat(32)}
            encryption: 0x${"cd".repeat(32)}
            suite: 1
            """.trimIndent(),
            shipKeyBlock("~sampel-palnet", keys),
        )
    }

    @Test
    fun `a comet is told apart from the ships Azimuth knows`() {
        assertTrue(isComet("~dilnym-ritmet-haddeb-sigfen--maslun-labtem-pilryc-locwep"))
        assertFalse(isComet("~sampel-palnet"))
        assertFalse(isComet("~zod"))
        assertFalse(isComet("~marzod"))
        assertFalse(isComet("~sampel-palnet-sampel-palnet"))
        assertFalse(isComet("not-a-ship"))
    }
}
