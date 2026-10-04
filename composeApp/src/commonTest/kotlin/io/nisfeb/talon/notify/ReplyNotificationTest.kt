package io.nisfeb.talon.notify

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.NotifyLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A reply in a thread that counts notifies on desktop and iOS, whose
 * top-level diff never saw replies. Following is asking for it: a chat
 * at "mentions only" lets it through; muted, open in front, or stale
 * does not.
 */
class ReplyNotificationTest {
    private val now = 1_000_000L
    private val reply = MessageEntity("chat/~bus/general", "170141184507", "~nec", now - 1_000, """[{"inline":["sure"]}]""", "/chat", parentId = "170141184506")

    private fun of(m: MessageEntity = reply, level: String? = null, open: String? = null) =
        replyNotification(m, level, open, now, 5 * 60_000L, storyText = { _, _ -> "sure" }, nameFor = { "Nec" })

    @Test
    fun `a reply notifies under its author's name, saying it is in a thread`() {
        assertEquals(NotificationCandidate("chat/~bus/general", "Nec", "In a thread: sure"), of())
        assertEquals("In a thread: sure", of(level = NotifyLevel.MENTIONS)?.body, "following overrides mentions only")
    }

    @Test
    fun `muted, open in front, stale or not a reply, nothing`() {
        assertNull(of(level = NotifyLevel.NONE))
        assertNull(of(open = "chat/~bus/general"))
        assertNull(of(m = reply.copy(sentMs = now - 6 * 60_000L)))
        assertNull(of(m = reply.copy(parentId = null)))
    }
}
