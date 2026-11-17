package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What `/trunk` reads from what was typed, and the message it posts. */
class TrunkCommandTest {
    private fun ok(vararg args: String) = (parseTrunk(args.toList()) as TrunkParse.Ok).ask
    private fun problem(vararg args: String) = (parseTrunk(args.toList()) as TrunkParse.Problem).message

    @Test
    fun `bare, it is ten guests for a day, speaking, untitled`() {
        assertEquals(TrunkAsk(seats = 10, ttlSecs = 86_400, speak = true, title = ""), ok())
    }

    @Test
    fun `guests, a span and listen come in any order, and the rest is the title`() {
        assertEquals(TrunkAsk(5, 7_200, true, ""), ok("5", "2h"))
        assertEquals(TrunkAsk(5, 7_200, false, "Board meeting"), ok("2h", "listen", "5", "Board", "meeting"))
        assertEquals(TrunkAsk(10, 45 * 60, true, "Standup"), ok("45m", "Standup"))
        assertEquals(TrunkAsk(10, 7 * 86_400, true, ""), ok("7D"))
        assertEquals(TrunkAsk(1000, 30 * 86_400, true, ""), ok("1000", "30d"))
    }

    @Test
    fun `a title may hold what looks like an option`() {
        // The first word that is no option starts the title, and a second number is a title's.
        assertEquals(TrunkAsk(10, 86_400, true, "Sprint 12 listen 2h"), ok("Sprint", "12", "listen", "2h"))
        assertEquals(TrunkAsk(5, 86_400, true, "7 wonders"), ok("5", "7", "wonders"))
        assertEquals(64, ok("x".repeat(200)).title.length)
    }

    @Test
    fun `what trunk would refuse is refused here, in words`() {
        assertTrue("1 to 1000" in problem("0"))
        assertTrue("1 to 1000" in problem("1001"))
        assertTrue("1 to 1000" in problem("99999999999999999999"))
        assertTrue("30 days" in problem("31d"))
        assertTrue("30 days" in problem("0m"))
        assertTrue("30 days" in problem("99999999999999999999h"))
    }

    @Test
    fun `the message says how to join and what the link allows`() {
        assertEquals(
            "📞 Call: https://ship.example/apps/trunk/guest/abc\n" +
                "Open it in any browser to join. No account is needed. The link takes 10 guests and works for 1 day.",
            trunkMessage(TrunkAsk(), "https://ship.example/apps/trunk/guest/abc"),
        )
        val m = trunkMessage(TrunkAsk(1, 5_400, false, "Town hall"), "L")
        assertTrue(m.startsWith("📞 Town hall: L\n"), m)
        assertTrue("Guests listen and watch. The link takes 1 guest and works for 90 minutes." in m, m)
        assertTrue("works for 2 hours." in trunkMessage(TrunkAsk(ttlSecs = 7_200), "L"))
    }

    @Test
    fun `the link is on the ship's public address, else where we signed in`() {
        assertEquals("https://pub.example/apps/trunk/guest/c", guestLink("https://pub.example", "http://192.168.1.5:8080", "/apps/trunk/guest/c"))
        assertEquals("https://ship.example/apps/trunk/guest/c", guestLink("", "https://ship.example/", "/apps/trunk/guest/c"))
    }

    @Test
    fun `an address only our own network reaches is known`() {
        for (u in listOf("http://localhost:8080/x", "http://127.0.0.1/x", "http://192.168.1.5:8080/x", "http://10.0.0.2/x", "http://172.20.1.1/x", "http://nas.local/x", "http://zod/x")) {
            assertTrue(isPrivateAddress(u), u)
        }
        for (u in listOf("https://ship.example/x", "https://urbit.example.com:8443/x", "http://172.32.0.1/x", "http://8.8.8.8/x", "https://192.168.example.com/x")) {
            assertFalse(isPrivateAddress(u), u)
        }
    }
}
