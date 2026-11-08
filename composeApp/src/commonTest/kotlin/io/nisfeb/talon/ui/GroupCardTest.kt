package io.nisfeb.talon.ui

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.screens.HomeRow
import io.nisfeb.talon.ui.screens.newInLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A group's card: its line of news. */
class GroupCardTest {
    private fun child(whom: String, unread: Int = 0, author: String? = null, sent: Long = 0) = HomeRow.GroupChild(
        whom = whom,
        m = author?.let { MessageEntity(whom, "$it/$sent", it, sent, "[]", "/chat") },
        unread = unread,
        groupFlag = "~bus/garden",
    )

    @Test
    fun `the news names only the channels with something unread, in the card's order, with how many`() {
        val children = listOf(child("plans", unread = 2), child("quiet"), child("general", unread = 5))
        assertEquals("New in plans 2 · general 5", newInLine(children) { it })
        assertNull(newInLine(listOf(child("quiet"), child("still")), { it }), "nothing new, no line")
    }
}
