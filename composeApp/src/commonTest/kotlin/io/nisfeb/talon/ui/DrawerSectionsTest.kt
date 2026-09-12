package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrawerSectionsTest {

    private val all = RailItem.entries.toList()

    @Test
    fun `an untouched config shows everything`() {
        assertEquals(all, drawerSections(all, emptyMap()))
    }

    @Test
    fun `hiding a section leaves it out`() {
        val out = drawerSections(all, mapOf(RailItem.Bookmarks to false))
        assertTrue(RailItem.Bookmarks !in out)
        assertTrue(RailItem.Mail in out)
    }

    @Test
    fun `settings survives being hidden`() {
        // The drawer is the only thing on a phone that opens Settings,
        // and Settings is the only thing that unhides it. Honouring
        // the preference here would strand somebody with no way back.
        val out = drawerSections(all, mapOf(RailItem.Settings to false))
        assertTrue(RailItem.Settings in out)
    }

    @Test
    fun `chats survives being hidden`() {
        assertTrue(RailItem.Chats in drawerSections(all, mapOf(RailItem.Chats to false)))
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
