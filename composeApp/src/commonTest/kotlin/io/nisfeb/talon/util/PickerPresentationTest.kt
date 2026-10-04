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

    @Test
    fun `a presentation has time to show before it counts as dropped`() {
        // Longer than the tries take, and than a sheet's animation.
        assert(PRESENT_CONFIRM_MS >= PRESENT_RETRY_MS * 3) { "$PRESENT_CONFIRM_MS" }
    }

    @Test
    fun `a 48 MP photo is taken in at 4096 px on its longest side, aspect kept`() {
        assertEquals(4096 to 3072, fitWithin(8064, 6048))
        assertEquals(3072 to 4096, fitWithin(6048, 8064))
        assertEquals(1170 to 2532, fitWithin(1170, 2532), "a screenshot stands")
    }
}
