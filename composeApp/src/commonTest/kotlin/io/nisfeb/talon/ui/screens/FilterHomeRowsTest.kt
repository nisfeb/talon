package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the chat list's search bar keeps of the list: names, in the list's order. */
class FilterHomeRowsTest {
    private fun msg(whom: String, at: Long) = MessageEntity(whom, "$whom/$at", "~bus", at, "[]", "/chat")
    private fun head(flag: String, title: String) = HomeRow.GroupHead(flag, title, null, 2, 0, expanded = false)
    private fun kid(whom: String, flag: String, m: MessageEntity? = null) = HomeRow.GroupChild(whom, m, 0, flag)
    private val labels = mapOf(
        "chat/~bus/general" to "The Garden · general",
        "chat/~bus/seeds" to "The Garden · seeds",
        "chat/~nec/lobby" to "Lunch Club · lobby",
        "chat/~nec/menu" to "Lunch Club · menu",
        "~ridlur-figbud" to "Rosie",
    )
    private val rows = listOf(
        HomeRow.Header("Groups", "groups"),
        head("~bus/garden", "The Garden"),
        kid("chat/~bus/general", "~bus/garden", msg("chat/~bus/general", 1)),
        // No message yet: in the list, so it must be found.
        kid("chat/~bus/seeds", "~bus/garden"),
        head("~nec/lunch", "Lunch Club"),
        kid("chat/~nec/lobby", "~nec/lunch"),
        kid("chat/~nec/menu", "~nec/lunch"),
        HomeRow.Header("DMs", "dms"),
        HomeRow.Flat(msg("~ridlur-figbud", 4), 0),
    )
    private fun kept(q: String) = filterHomeRows(rows, q) { labels.getValue(it) }.map { it.key }

    @Test
    fun `a group's name keeps it, opened, with every channel, messages or not`() {
        val out = filterHomeRows(rows, "garden") { labels.getValue(it) }
        assertEquals(listOf("group:~bus/garden", "gchan:~bus/garden:chat/~bus/general", "gchan:~bus/garden:chat/~bus/seeds"), out.map { it.key })
        assertEquals(true, (out.first() as HomeRow.GroupHead).expanded, "opened, to show them")
    }

    @Test
    fun `a channel's own name keeps its group with that channel alone`() {
        assertEquals(listOf("group:~nec/lunch", "gchan:~nec/lunch:chat/~nec/menu"), kept("MENU"))
    }

    @Test
    fun `a quiet group with no messages at all is found by its name`() {
        assertEquals(listOf("group:~nec/lunch", "gchan:~nec/lunch:chat/~nec/lobby", "gchan:~nec/lunch:chat/~nec/menu"), kept("lunch"))
    }

    @Test
    fun `a person is kept by their name or their ship, and headers go`() {
        assertEquals(listOf("conv:~ridlur-figbud"), kept("rosie"))
        assertEquals(listOf("conv:~ridlur-figbud"), kept("ridlur"))
    }

    @Test
    fun `a channel is not kept for its host's ship, and blank keeps everything`() {
        assertEquals(emptyList(), kept("~bus"))
        assertEquals(rows, filterHomeRows(rows, "  ") { labels.getValue(it) })
    }
}
