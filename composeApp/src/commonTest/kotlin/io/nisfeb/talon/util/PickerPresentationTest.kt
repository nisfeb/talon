package io.nisfeb.talon.util

import io.nisfeb.talon.ui.fitWithin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS photo picker "didn't consistently open" and a photo picked in
 * it "didn't attach": UIKit drops a presentation asked for mid
 * transition, and Talon gave up on one after a single turn of the main
 * queue. The rules, here where they can be tested; the UIKit wiring
 * around them is iOS-only.
 */
class PickerPresentationTest {
    @Test
    fun `a free screen presents at once, a busy one is tried again, then given up on`() {
        assertEquals(PresentStep.PRESENT, presentStep(busy = false, attempt = 0))
        assertEquals(PresentStep.PRESENT, presentStep(busy = false, attempt = 4), "free on a later try")
        assertEquals(PresentStep.RETRY, presentStep(busy = true, attempt = 0))
        assertEquals(PresentStep.RETRY, presentStep(busy = true, attempt = PRESENT_ATTEMPTS - 2))
        assertEquals(PresentStep.GIVE_UP, presentStep(busy = true, attempt = PRESENT_ATTEMPTS - 1))
    }

    /** The watch as the iOS picker runs it: a look every [PRESENT_WATCH_MS] while it is not up. */
    private fun watched(upAtMs: Long?): Pair<PresentWatch, Long> {
        var waited = 0L
        while (true) {
            waited += PRESENT_WATCH_MS
            val step = presentWatch(up = upAtMs != null && waited >= upAtMs, waitedMs = waited)
            if (step != PresentWatch.WAIT) return step to waited
        }
    }

    @Test
    fun `a photo library four seconds slow is waited for, not called dropped`() {
        // iOS 26.6: "The picker did not open", then it opened, and the
        // photo picked in it attached nothing.
        assertEquals(PresentWatch.UP to 4_000L, watched(upAtMs = 4_000))
        assertEquals(PresentWatch.UP to 9_500L, watched(upAtMs = 9_500), "up on the last look before the limit")
        assertEquals(PresentWatch.UP to PRESENT_WATCH_MS, watched(upAtMs = 0), "a quick one at the first look")
    }

    @Test
    fun `a picker that never comes up is called dropped at the limit, not before`() {
        assertEquals(PresentWatch.DROPPED to PRESENT_CONFIRM_MS, watched(upAtMs = null))
        assertEquals(PresentWatch.WAIT, presentWatch(up = false, waitedMs = PRESENT_CONFIRM_MS - 1))
        assertEquals(PresentWatch.DROPPED, presentWatch(up = false, waitedMs = PRESENT_CONFIRM_MS))
        assertEquals(PresentWatch.UP, presentWatch(up = true, waitedMs = PRESENT_CONFIRM_MS), "up wins at the limit")
        // Longer than the busy tries take, and than a sheet's animation.
        assert(PRESENT_CONFIRM_MS >= PRESENT_RETRY_MS * PRESENT_ATTEMPTS) { "$PRESENT_CONFIRM_MS" }
    }

    @Test
    fun `a picker that comes up after Talon stopped waiting closes at once`() {
        assertEquals(false, closeOnArrival(droppedAlready = false, wanted = true), "the one waited for stays")
        assertEquals(true, closeOnArrival(droppedAlready = true, wanted = true), "counted dropped: its pick goes nowhere")
        assertEquals(true, closeOnArrival(droppedAlready = false, wanted = false), "its caller left")
        assertEquals(true, closeOnArrival(droppedAlready = true, wanted = false))
    }

    @Test
    fun `a 48 MP photo is taken in at 4096 px on its longest side, aspect kept`() {
        assertEquals(4096 to 3072, fitWithin(8064, 6048))
        assertEquals(3072 to 4096, fitWithin(6048, 8064))
        assertEquals(1170 to 2532, fitWithin(1170, 2532), "a screenshot stands")
    }
}
