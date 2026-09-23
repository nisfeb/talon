package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules that decide sameness and read people out of a title,
 * ported from orrery-utils so the client folds twins and names
 * participants the way the owner's own passes do.
 */
class OrreryTitlesTest {

    @Test
    fun `a reminder and the thing it reminds you of are one title`() {
        assertEquals("pottery", normalizeTitle("Reminder: Pottery @ Thu May 14, 6:00pm"))
        assertEquals("pottery", normalizeTitle("Pottery"))
        assertEquals("robin pottery/wheel", normalizeTitle("Robin- Pottery/Wheel"))
        assertEquals("dentist", normalizeTitle("Updated invitation: Dentist 2026-09-18"))
        assertEquals("", normalizeTitle(null))
    }

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
