package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
