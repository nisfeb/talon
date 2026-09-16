package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TalonLinkTest {
    @Test fun `a message address round-trips with its whom, id and parent`() {
        val url = TalonLink.forMessage("chat/~sampel-palnet/general", "170.141.184.506", "170.141.184.500")
        assertEquals(TalonLink.Message("chat/~sampel-palnet/general", "170.141.184.506", "170.141.184.500"), TalonLink.parse(url))
        assertEquals(TalonLink.Message("~zod", "1", null), TalonLink.parse(TalonLink.forMessage("~zod", "1")))
    }

    @Test fun `a mail address names its thread, and the rest is not ours`() {
        assertEquals(TalonLink.Mail("0v3.abc.def"), TalonLink.parse(TalonLink.forMail("0v3.abc.def")))
        assertNull(TalonLink.parse("https://example.com"))
        assertNull(TalonLink.parse("talon://chat/~zod"))
        assertNull(TalonLink.parse("talon://what/ever"))
    }

    @Test
    fun groupAndInviteLinksRoundTrip() {
        assertEquals(TalonLink.Group("~sampel-palnet/tlon-studio"), TalonLink.parse(TalonLink.forGroup("~sampel-palnet/tlon-studio")))
        assertEquals(TalonLink.InviteMe("~zod"), TalonLink.parse(TalonLink.forInviteMe("~zod")))
        assertNull(TalonLink.parse("talon://group/sampel/x"))
        assertNull(TalonLink.parse("talon://group/~zod"))
        assertNull(TalonLink.parse("talon://invite/zod"))
        assertNull(TalonLink.parse("talon://invite/~zod/x"))
    }

    @Test
    fun chatLinksValidateWhom() {
        assertNull(TalonLink.parse("talon://chat/not-a-ship?id=1"))
        assertNull(TalonLink.parse("talon://chat/?id=1"))
        assertNull(TalonLink.parse("talon://chat/~zod/extra?id=1"))
        assertNull(TalonLink.parse("talon://chat/~zod?id="))
        assertEquals(TalonLink.Message("~zod", "1", null), TalonLink.parse("talon://chat/~zod?id=1"))
        assertEquals(
            TalonLink.Message("chat/~zod/general", "1", null),
            TalonLink.parse("talon://chat/chat%2F~zod%2Fgeneral?id=1"),
        )
        assertEquals(TalonLink.Message("0v1.a2c3d", "1", null), TalonLink.parse("talon://chat/0v1.a2c3d?id=1"))
    }
}
