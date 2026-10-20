package io.nisfeb.talon.ui

import io.nisfeb.talon.call.LineAsk
import io.nisfeb.talon.ui.screens.lineAskText
import kotlin.test.Test
import kotlin.test.assertEquals

/** What an admin is told after switching on a line another ship keeps. */
class LineAskTextTest {

    @Test
    fun eachOutcomeNamesItsCause() {
        assertEquals("Asking ~bus to open it…", lineAskText(LineAsk.Asking(), "~bus", 3))
        assertEquals(
            "Your ship didn't take the request. Check that %trunk is installed and running on it.",
            lineAskText(LineAsk.OwnShip, "~bus", 3),
        )
        assertEquals("~bus turned it down. Its %trunk may be out of date.", lineAskText(LineAsk.Refused, "~bus", 3))
        assertEquals(
            "~bus has %trunk but didn't open the line. Its call settings may not let your ship ring it.",
            lineAskText(LineAsk.Ignored, "~bus", 3),
        )
        assertEquals(
            "~bus hasn't answered. This group's line lives on ~bus, which needs %trunk installed and running. " +
                "It may also be offline, or not let your ship ring it.",
            lineAskText(LineAsk.NoAnswer, "~bus", 3),
        )
    }

    /** The host refuses a roster over 256 without a word, so silence over it is that. */
    @Test
    fun silenceOverTheRosterCapIsTheCap() {
        val cap = "~bus won't open it: a party line holds at most 256 members, and this group has 257."
        assertEquals(cap, lineAskText(LineAsk.NoAnswer, "~bus", 257))
        assertEquals(cap, lineAskText(LineAsk.Ignored, "~bus", 257))
        assertEquals("~bus hasn't answered.", lineAskText(LineAsk.NoAnswer, "~bus", 256).substringBefore(" This"))
        assertEquals("~bus turned it down. Its %trunk may be out of date.", lineAskText(LineAsk.Refused, "~bus", 257))
    }
}
