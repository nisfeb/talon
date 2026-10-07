package io.nisfeb.talon.notify

import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.Mnemonym
import io.nisfeb.talon.ui.shipHandle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A relay push woke the phone: the notification names the conversation
 * the way the app does (nickname, word name, then @p), not by our own @p
 * over the raw conversation id as it did.
 */
class PushHintNotificationTest {
    private val us = "~zod"
    private val other = "~nec"
    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"
    private val names = ContactMap(
        contacts = listOf(ContactEntity("~litzod", "Maya", null, null), ContactEntity(comet, null, null, null)),
        groups = listOf(GroupEntity("~bus/crew", "Crew", null)),
        channelGroups = listOf(ChannelGroupEntity("chat/~bus/general", "~bus/crew", title = "General")),
    )

    @Test
    fun `a push for the signed-in ship uses its names`() {
        assertEquals(NotificationCandidate("~litzod", "Maya", "New activity"), pushHintNotification("~litzod", us, us, names))
        assertEquals("Crew · General", pushHintNotification("chat/~bus/general", us, us, names).title)
        assertEquals(Mnemonym.display(comet), pushHintNotification(comet, us, us, names).title, "a comet by its word name")
        assertEquals("Maya", pushHintNotification("~litzod", null, us, names).title, "an older relay names no ship")
    }

    @Test
    fun `a push for another of our ships uses none of this one's nicknames, and says which ship`() {
        val n = pushHintNotification("~litzod", other, us, names)
        assertEquals(shipHandle("~litzod"), n.title)
        assertEquals("New activity on ${shipHandle(other)}", n.body)
    }

    @Test
    fun `a ring names the caller the same way`() {
        assertEquals("Maya", pushNames(us, us, names).displayName("~litzod"))
        assertEquals(shipHandle("~litzod"), pushNames(other, us, names).displayName("~litzod"))
        assertEquals("~litzod", pushNames(other, us, names.copy(alwaysPatp = true)).displayName("~litzod"), "always @p holds")
    }
}
