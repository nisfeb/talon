package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RailTabTest {

    @Test
    fun `name + railTabOrDefault round-trips every value`() {
        for (tab in RailTab.entries) {
            assertEquals(tab, railTabOrDefault(tab.name))
        }
    }

    @Test
    fun `null name falls back to Chats`() {
        assertEquals(RailTab.Chats, railTabOrDefault(null))
    }

    @Test
    fun `blank name falls back to Chats`() {
        assertEquals(RailTab.Chats, railTabOrDefault(""))
        assertEquals(RailTab.Chats, railTabOrDefault("   "))
    }

    @Test
    fun `unknown name falls back to Chats`() {
        // Future-proofing: removing or renaming a tab on disk shouldn't
        // crash on next launch; default to the safe home tab instead.
        assertEquals(RailTab.Chats, railTabOrDefault("Profile"))
        assertEquals(RailTab.Chats, railTabOrDefault("nope"))
    }
}
