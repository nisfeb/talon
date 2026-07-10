package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the SmartFeatures fold policy both platform stores route through.
 * Neither store ever wrote a toggle the user hadn't touched, so an absent
 * toggle means default-true: the fold is off ONLY when all four legacy
 * toggles are present and false. The release-review bug: a user who
 * disabled just one feature had the other three silently disabled too.
 */
class AiSettingsMigrationTest {

    @Test
    fun `one explicit opt-out among absent-therefore-on toggles stays on`() {
        assertTrue(AiSettings.migratedSmartFeatures(present = listOf(false), total = 4))
        assertTrue(AiSettings.migratedSmartFeatures(present = listOf(false, false, false), total = 4))
    }

    @Test
    fun `all four present and false is the only off`() {
        assertFalse(AiSettings.migratedSmartFeatures(present = List(4) { false }, total = 4))
        assertTrue(AiSettings.migratedSmartFeatures(present = listOf(false, false, false, true), total = 4))
    }
}
