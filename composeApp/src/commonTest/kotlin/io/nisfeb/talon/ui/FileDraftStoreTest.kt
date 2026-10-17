package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop kept drafts in memory, and lost every one on quit or an update.
 * The file-per-ship store keeps them for the next start, and per ship.
 */
class FileDraftStoreTest {
    private val files = mutableMapOf<String, String>()
    private var active: String? = "~zod"
    private fun store() = FileDraftStore({ active }, read = { files[it] }, write = { name, text -> files[name] = text })

    @Test
    fun `a draft outlives the store, per ship, and a blank one is gone`() {
        val first = store()
        first.save("~bus", "half a thought")
        first.save("chat/~zod/general", "x")
        first.save("chat/~zod/general", "  ")
        active = "~nec"
        first.save("~bus", "another ship's")
        active = "~zod"
        val next = store()
        assertEquals("half a thought", next.load("~bus"))
        assertEquals("", next.load("chat/~zod/general"))
        assertEquals(mapOf("~bus" to "half a thought"), next.state.value, "the list's previews, for the active ship only")
        active = "~nec"
        assertEquals("another ship's", next.load("~bus"))
        next.clear("~bus")
        assertEquals("", store().load("~bus"))
        assertEquals(setOf("drafts-zod.json", "drafts-nec.json"), files.keys)
    }

    @Test
    fun `no ship signed in keeps nothing, and an unreadable file is no drafts`() {
        active = null
        store().save("~bus", "lost")
        assertTrue(files.isEmpty())
        files[draftsFileName("~zod")] = "not json"
        active = "~zod"
        assertEquals("", store().load("~bus"))
    }
}
