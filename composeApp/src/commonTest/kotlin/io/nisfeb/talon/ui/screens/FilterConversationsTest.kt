package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the chat list's search bar keeps of the list: names, not messages. */
class FilterConversationsTest {
    private fun row(whom: String, at: Long) = MessageEntity(whom, "$whom/$at", "~bus", at, "[]", "/chat") to 0
    private val labels = mapOf(
        "chat/~bus/general" to "The Garden · general",
        "chat/~bus/seeds" to "The Garden · seeds",
        "chat/~nec/lobby" to "Lunch Club · lobby",
        "~ridlur-figbud" to "Rosie",
        "~sampel-palnet" to "~sampel-palnet",
    )
    private val rows = listOf(
        row("chat/~bus/general", 1), row("chat/~bus/seeds", 3), row("chat/~nec/lobby", 2),
        row("~ridlur-figbud", 4), row("~sampel-palnet", 5),
    )
    private fun kept(q: String) = filterConversations(rows, q, { labels.getValue(it) }).map { it.first.whom }

    @Test
    fun `a group's name keeps all its channels, newest first`() {
        assertEquals(listOf("chat/~bus/seeds", "chat/~bus/general"), kept("garden"))
    }

    @Test
    fun `a channel's own name keeps that channel`() {
        assertEquals(listOf("chat/~nec/lobby"), kept("LOBBY"))
    }

    @Test
    fun `a person is kept by their name or their ship`() {
        assertEquals(listOf("~ridlur-figbud"), kept("rosie"))
        assertEquals(listOf("~ridlur-figbud"), kept("ridlur"))
    }

    @Test
    fun `a channel is not kept for its host's ship`() {
        assertEquals(emptyList(), kept("~bus"))
    }

    @Test
    fun `blank keeps everything as it was`() {
        assertEquals(rows, filterConversations(rows, "  ", { labels.getValue(it) }))
    }
}
