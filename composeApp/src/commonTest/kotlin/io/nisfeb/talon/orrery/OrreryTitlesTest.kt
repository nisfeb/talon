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

    @Test
    fun `a title is certain of some names, guesses at one, and invents none`() {
        // Every answer here is what common/reconcile.py's names_in gives.
        assertEquals(listOf("Adelaide") to null, namesInTitle("Adelaide- Ballet/Tap"))
        assertEquals(listOf("Rose", "Linus") to null, namesInTitle("Rose and Linus- Opti Sail"))
        assertEquals(listOf("Magnus") to null, namesInTitle("Magnus Birthday"))
        assertEquals(listOf("magnus") to null, namesInTitle("magnus's birthday"))
        assertEquals(listOf("Dana") to null, namesInTitle("  Dana - Pottery"))
        // A leading word is only a name if the ship keeps somebody by it.
        assertEquals(emptyList<String>() to "Magnus", namesInTitle("Magnus Fencing Lesson"))
        assertEquals(emptyList<String>() to "Nutcracker", namesInTitle("Nutcracker rehearsal"))
        assertEquals(emptyList<String>() to "Pirates", namesInTitle("Pirates practice"))
        assertEquals(emptyList<String>() to null, namesInTitle("standup"))
    }

    @Test
    fun `a first name skips the role it is written with`() {
        assertEquals("dana", firstNameOf("Dana Quill"))
        assertEquals("dana", firstNameOf("the Dana"))
        assertEquals(null, firstNameOf("wife"))
        assertEquals(null, firstNameOf(""))
    }
}
