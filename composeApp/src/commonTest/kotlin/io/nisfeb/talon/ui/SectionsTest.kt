package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stuck-section bug: a new section was left out of the reset list
 * or the back handlers, so it stayed on screen and neither back nor
 * the drawer could leave it. These pin the rule that makes that
 * impossible: every flag the registry made is reset and backed out of.
 */
class SectionsTest {
    @Test
    fun `putting sections down closes every one, however late it was added`() {
        val s = Sections()
        val settings = s.flag()
        val actions = s.flag() // the section somebody added last
        actions.value = true
        settings.value = true
        s.closeAll()
        assertFalse(actions.value, "a section added later is not forgotten")
        assertFalse(settings.value)
        assertFalse(s.anyOpen)
    }

    @Test
    fun `back closes the newest section first`() {
        val s = Sections()
        val settings = s.flag()
        val sidebar = s.flag()
        settings.value = true
        sidebar.value = true // opened on top of settings
        assertTrue(s.closeLast())
        assertFalse(sidebar.value, "the one on top goes first")
        assertTrue(settings.value, "and what was underneath is still there")
        assertTrue(s.closeLast())
        assertFalse(s.anyOpen)
        assertFalse(s.closeLast(), "nothing left to close")
    }

    @Test
    fun `reopening a section makes it the newest again`() {
        val s = Sections()
        val a = s.flag()
        val b = s.flag()
        a.value = true
        b.value = true
        a.value = false
        a.value = true
        s.closeLast()
        assertFalse(a.value)
        assertTrue(b.value)
    }

    @Test
    fun `a section can start open`() {
        val s = Sections()
        val mail = s.flag(initial = true)
        assertTrue(s.anyOpen)
        s.closeLast()
        assertFalse(mail.value)
    }

}
