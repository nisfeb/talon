package io.nisfeb.talon.relay

import kotlin.test.Test
import kotlin.test.assertEquals
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

    // A user's report, 2026-10-07: a relay pushed every post in every room
    // they had not set, where the app showed those rooms as Mentions only.
    @Test
    fun `unset is mentions, as the app shows it, and all is all`() {
        assertFalse(NotifyPolicy.allows("chat/~zod/x", null, mention = false))
        assertTrue(NotifyPolicy.allows("chat/~zod/x", null, mention = true))
        assertTrue(NotifyPolicy.allows("~nec", null, mention = false), "a DM is addressed to you")
        assertTrue(NotifyPolicy.allows("chat/~zod/x", NotifyPolicy.ALL, mention = false))
    }

    // %activity flags a reply only in a thread we wrote, replied in or were
    // mentioned in: the default must not silence those.
    @Test
    fun `a flagged reply passes mentions, not none`() {
        assertTrue(NotifyPolicy.allows("chat/~zod/x", null, mention = false, reply = true))
        assertFalse(NotifyPolicy.allows("chat/~zod/x", NotifyPolicy.NONE, mention = false, reply = true))
    }

    @Test
    fun `a channel's own level, else its group's, else none`() {
        val levels = mapOf("group/~zod/crew" to NotifyPolicy.NONE, "chat/~zod/loud" to NotifyPolicy.ALL)
        assertEquals(NotifyPolicy.NONE to "group", NotifyPolicy.resolve(levels, "chat/~zod/x", "~zod/crew"))
        assertEquals(NotifyPolicy.ALL to "own", NotifyPolicy.resolve(levels, "chat/~zod/loud", "~zod/crew"))
        assertEquals(null to "default", NotifyPolicy.resolve(levels, "chat/~zod/y", "~zod/other"))
        assertEquals(null to "default", NotifyPolicy.resolve(levels, "~nec", null), "a DM takes no group")
    }
}
