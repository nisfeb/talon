package io.nisfeb.talon.data

import io.nisfeb.talon.notify.notifyAllowed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A group's level, which its channels follow until one is given its own
 * (sneagan, 2026-10-07: "how do you set the whole group's notification").
 * Trunk resolves the same way: channel, then group, then mentions.
 */
class GroupNotifyLevelTest {
    private val crew = "~bus/crew"
    private val general = "chat/~bus/general"
    private val random = "chat/~bus/random"

    @Test
    fun a_channel_follows_its_group_until_it_has_its_own() {
        assertEquals(NotifyLevel.NONE, effectiveLevel(own = null, groupLevel = NotifyLevel.NONE))
        assertEquals(NotifyLevel.ALL, effectiveLevel(own = NotifyLevel.ALL, groupLevel = NotifyLevel.NONE), "its own wins")
        assertEquals(NotifyLevel.MENTIONS, effectiveLevel(own = null, groupLevel = null), "neither: mentions")
    }

    @Test
    fun the_group_fills_in_only_where_a_channel_has_none() {
        val own = mapOf(groupLevelKey(crew) to NotifyLevel.NONE, random to NotifyLevel.ALL, "~nec" to NotifyLevel.NONE)
        val levels = withGroupLevels(own, mapOf(general to crew, random to crew))
        assertEquals(NotifyLevel.NONE, levels[general], "follows the group")
        assertEquals(NotifyLevel.ALL, levels[random], "keeps its own")
        assertEquals(NotifyLevel.NONE, levels["~nec"], "a DM is its own")
        // Muting the group silences a post in a channel that follows it,
        // a mention included; a channel set to all still notifies.
        assertFalse(notifyAllowed(general, levels[general], mention = true))
        assertTrue(notifyAllowed(random, levels[random], mention = false))
    }

    @Test
    fun a_group_key_is_no_chat() {
        assertEquals("group/~bus/crew", groupLevelKey(crew))
        assertFalse(groupLevelKey(crew).startsWith("chat/"))
    }
}
