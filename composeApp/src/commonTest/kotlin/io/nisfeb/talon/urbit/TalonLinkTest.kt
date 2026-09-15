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
}
