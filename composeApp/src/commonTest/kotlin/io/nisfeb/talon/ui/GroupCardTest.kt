package io.nisfeb.talon.ui

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.screens.HomeRow
import io.nisfeb.talon.ui.screens.cardTarget
import io.nisfeb.talon.ui.screens.recentFaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A group's card: where its tap goes, and whose faces it shows. */
class GroupCardTest {
    private fun child(whom: String, unread: Int = 0, author: String? = null, sent: Long = 0) = HomeRow.GroupChild(
        whom = whom,
        m = author?.let { MessageEntity(whom, "$it/$sent", it, sent, "[]", "/chat") },
        unread = unread,
        groupFlag = "~bus/garden",
    )

    @Test
    fun `the channel with the most unread is opened, before the newest words`() {
        val children = listOf(child("a", unread = 2, author = "~nec", sent = 1), child("b", unread = 5, author = "~nec", sent = 2), child("c", author = "~wes", sent = 9))
        assertEquals("b", cardTarget(children))
    }

    @Test
    fun `with nothing unread the newest words are opened, and no channel opens nothing`() {
        assertEquals("c", cardTarget(listOf(child("a", author = "~nec", sent = 1), child("c", author = "~wes", sent = 9), child("d"))))
        assertEquals("d", cardTarget(listOf(child("d"))), "a channel with no messages yet still opens")
        assertNull(cardTarget(emptyList()))
    }

    @Test
    fun `faces are the latest speakers, newest first, each once, the owner left out, three at most`() {
        val children = listOf(
            child("a", author = "~nec", sent = 3),
            child("b", author = "~zod", sent = 9),
            child("c", author = "~wes", sent = 5),
            child("d", author = "~nec", sent = 1),
            child("e", author = "~bus", sent = 2),
            child("f", author = "~dev", sent = 0),
            child("g"),
        )
        assertEquals(listOf("~wes", "~nec", "~bus"), recentFaces(children, ourShip = "~zod"))
    }
}
