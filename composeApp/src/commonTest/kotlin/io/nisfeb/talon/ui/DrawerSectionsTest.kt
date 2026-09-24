package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrawerSectionsTest {

    private val all = RailItem.entries.toList()

    @Test
    fun `hiding a section leaves it out`() {
        val out = drawerSections(all, mapOf(RailItem.Bookmarks to false))
        assertTrue(RailItem.Bookmarks !in out)
        assertTrue(RailItem.Mail in out)
    }

    @Test
    fun `hiding everything still leaves a way back`() {
        val out = drawerSections(all, all.associateWith { false })
        assertEquals(listOf(RailItem.Chats, RailItem.Settings), out)
    }

    @Test
    fun `a section this host cannot open is left out, settings included`() {
        // canOpen is the host saying "no such destination here", which
        // outranks the always-on rule: offering a row that goes
        // nowhere is worse than not offering it.
        val out = drawerSections(all, emptyMap()) { it != RailItem.Settings }
        assertTrue(RailItem.Settings !in out)
    }

    @Test
    fun `order is preserved`() {
        val reversed = all.reversed()
        assertEquals(reversed, drawerSections(reversed, emptyMap()))
    }
}
