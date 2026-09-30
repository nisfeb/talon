package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** orrery-utils' rule for two names being one person, as ported. */
class OrreryTitlesTest {

    @Test
    fun `a shorter name inside a longer one is the same person`() {
        assertTrue(samePerson("dana", "dana quill"))
        assertTrue(samePerson("Dana Quill", "dana"))
        assertFalse(samePerson("dana", "daniel quill"))
        assertFalse(samePerson("wife", "dana quill"))
        assertFalse(samePerson("", "dana"))
        assertTrue(samePerson("dana quill", "quill dana"))
    }
}
