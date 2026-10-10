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

    @Test
    fun cometLinksParse() {
        // A comet name carries `--`; the ship regex has to take it or
        // every link Talon emits for a comet parses to nothing.
        val comet = "~mister-botter-dozzod-anycpu--dortun-dotheb-holsur-rigdet"
        assertEquals(TalonLink.Message(comet, "1", null), TalonLink.parse("talon://chat/$comet?id=1"))
        assertEquals(TalonLink.InviteMe(comet), TalonLink.parse("talon://invite/$comet"))
        assertEquals(
            TalonLink.Group("$comet/hall"),
            TalonLink.parse("talon://group/$comet/hall"),
        )
        assertEquals(
            TalonLink.Message("chat/$comet/hall", "1", null),
            TalonLink.parse("talon://chat/chat%2F$comet%2Fhall?id=1"),
        )
        // Moons still parse, and a doubled dash inside a group name
        // does not make the ship part greedier than Patp's.
        assertEquals(TalonLink.Message("~mister-botter", "1", null), TalonLink.parse("talon://chat/~mister-botter?id=1"))
    }

    // A group's reference, copied from its info pane (2026-10-09).
    @Test fun `a group reference is Tlon's path, and the join box takes it, the flag, or Talon's link`() {
        assertEquals("/1/group/~bus/the-club", TalonLink.groupReference("~bus/the-club"))
        for (code in listOf("/1/group/~bus/the-club", "~bus/the-club", " /1/group/~bus/the-club\n", TalonLink.forGroup("~bus/the-club"))) {
            assertEquals("~bus/the-club", TalonLink.groupFlag(code), code)
        }
        assertEquals("~dister-dozzod-nisfeb/crew", TalonLink.groupFlag("/1/group/~dister-dozzod-nisfeb/crew"), "a moon's group")
        for (code in listOf("~bus", "bus/the-club", "/1/chan/chat/~bus/general", "/1/group/~bus", "/1/group/~bus/club/extra", "hello")) {
            assertEquals(null, TalonLink.groupFlag(code), code)
        }
    }
}
