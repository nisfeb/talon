package io.nisfeb.talon.orrery

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the ship's list shows an answer given here, so the ship's word wins again. */
class AnswerShownTest {
    private fun at(status: String) = OrreryAction("a1", "task", "Buy goggles", JsonObject(emptyMap()), emptyList(), null, status, "generator")

    @Test
    fun `gone from the open list shows any answer`() {
        for (sent in listOf("approved", "claimed", "done", "dismissed", "failed")) assertTrue(answerShown(null, sent), sent)
    }

    @Test
    fun `an approval shows once the ship has it approved or further on`() {
        assertFalse(answerShown(at("proposed"), "approved"))
        assertTrue(answerShown(at("approved"), "approved"))
        assertTrue(answerShown(at("claimed"), "approved"), "the executor took it already")
        assertFalse(answerShown(at("approved"), "claimed"))
        assertTrue(answerShown(at("claimed"), "claimed"))
    }

    // Mark done or Remove on an approved action came back while the ship
    // still listed it approved: "no longer a proposal" counted as shown.
    @Test
    fun `done or removed shows only once it has left the list`() {
        for (still in listOf("proposed", "approved", "claimed")) {
            assertFalse(answerShown(at(still), "done"), still)
            assertFalse(answerShown(at(still), "dismissed"), still)
        }
    }
}
