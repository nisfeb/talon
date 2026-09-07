package io.nisfeb.talon.relay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifyPolicyTest {
    @Test
    fun `none silences everything`() {
        assertFalse(NotifyPolicy.allows("~zod", NotifyPolicy.NONE, mention = true))
        assertFalse(NotifyPolicy.allows("chat/~zod/x", NotifyPolicy.NONE, mention = true))
    }

    @Test
    fun `mentions only for channels but DMs always`() {
        assertFalse(NotifyPolicy.allows("chat/~zod/x", NotifyPolicy.MENTIONS, mention = false))
        assertTrue(NotifyPolicy.allows("chat/~zod/x", NotifyPolicy.MENTIONS, mention = true))
        assertTrue(NotifyPolicy.allows("~zod", NotifyPolicy.MENTIONS, mention = false))
        assertTrue(NotifyPolicy.allows("0v1.abc", NotifyPolicy.MENTIONS, mention = false))
    }

    @Test
    fun `unset and all defer to the ship`() {
        assertTrue(NotifyPolicy.allows("chat/~zod/x", null, mention = false))
        assertTrue(NotifyPolicy.allows("chat/~zod/x", NotifyPolicy.ALL, mention = false))
    }
}
